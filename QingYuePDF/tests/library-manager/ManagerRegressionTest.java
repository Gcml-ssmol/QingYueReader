package cn.qingyue.pdf;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/** Desktop JDK regression checks for the stateful library managers. */
public final class ManagerRegressionTest {
    private static final String A = "11111111-1111-4111-8111-111111111111";
    private static final String B = "22222222-2222-4222-8222-222222222222";
    private static final String C = "33333333-3333-4333-8333-333333333333";

    public static void main(String[] arguments) throws Exception {
        staleSnapshotCannotOverwriteMutation();
        emptyFoldersSurviveOldItemWrites();
        deletingTrashOnlyFolderPreservesRecovery();
        deletingFolderProtectsActiveChildrenAndStaleState();
        failedFolderDeletionLeavesIndexAndPayloadIntact();
        damagedIndexCannotDeleteFolders();
        oldFolderNamesBecomeStableIds();
        readsShareTheAtomicFileWriteLock();
        malformedUtf8IsRejectedAndPreserved();
        repeatedDamagedLoadsDeduplicateRecoveryCopies();
        attachmentMustBelongToItsItem();
        rollbackRetainsUniqueStagedCopyUntilRetry();
        liveTrashTransactionIsNotRecoveredByAnotherActivity();
        damagedIndexNeverCleansAnAmbiguousTrashTransaction();
        startupRecoversOrCleansDurableTrashTransactions();
        deletingLastPdfLeavesReadableEmptyLibrary();
        System.out.println("PASS: revision guard, synchronized atomic reads, damaged-index safety, attachment ownership, retryable and durable trash transactions, trash-only folder deletion and restore");
    }

    private static void readsShareTheAtomicFileWriteLock() throws Exception {
        withContext("read-lock", context -> {
            LibraryStore.save(context, items(item(A, "initial")));
            CountDownLatch mutationEntered = new CountDownLatch(1);
            CountDownLatch releaseMutation = new CountDownLatch(1);
            CountDownLatch readFinished = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Thread writer = new Thread(() -> {
                try {
                    LibraryStore.mutate(context, current -> {
                        mutationEntered.countDown();
                        try {
                            if (!releaseMutation.await(5, TimeUnit.SECONDS)) {
                                throw new IOException("test timed out waiting to release mutation");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException("test mutation interrupted", interrupted);
                        }
                        current.add(item(B, "committed"));
                    });
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            }, "library-test-writer");
            Thread reader = new Thread(() -> {
                try { LibraryStore.load(context); }
                catch (Throwable problem) { failure.compareAndSet(null, problem); }
                finally { readFinished.countDown(); }
            }, "library-test-reader");

            writer.start();
            yes(mutationEntered.await(5, TimeUnit.SECONDS), "writer must enter locked mutation");
            reader.start();
            no(readFinished.await(150, TimeUnit.MILLISECONDS),
                    "AtomicFile reader must not run while an index write owns the lock");
            releaseMutation.countDown();
            writer.join(5000);
            reader.join(5000);
            no(writer.isAlive() || reader.isAlive(), "concurrency test threads must finish");
            if (failure.get() != null) throw new AssertionError("concurrency test failed", failure.get());
            equal(2, LibraryStore.load(context).items.size(),
                    "reader must observe the completed atomic mutation");
        });
    }

    private static void staleSnapshotCannotOverwriteMutation() throws Exception {
        withContext("revision", context -> {
            LibraryStore.save(context, items(item(A, "initial")));
            LibraryStore.State stale = LibraryStore.load(context);

            LibraryStore.State mutated = LibraryStore.mutate(context,
                    current -> current.add(item(B, "concurrent mutation")));
            no(stale.revision.equals(mutated.revision), "mutation must advance logical revision");

            ArrayList<LibraryItem> obsoleteReplacement = new ArrayList<>();
            obsoleteReplacement.add(item(A, "obsolete full snapshot"));
            expect(LibraryStore.StaleIndexException.class,
                    () -> LibraryStore.saveIfRevision(context, stale.revision, obsoleteReplacement),
                    "old snapshot must be rejected");

            LibraryStore.State after = LibraryStore.load(context);
            equal(mutated.revision, after.revision, "failed stale save must not rewrite the index");
            equal(2, after.items.size(), "concurrent mutation must remain present");
            equal("initial", find(after, A).name, "stale replacement must not alter the original item");
            equal("concurrent mutation", find(after, B).name, "concurrent item must not be lost");
        });
    }

    private static void malformedUtf8IsRejectedAndPreserved() throws Exception {
        withContext("invalid-utf8", context -> {
            File index = LibraryStore.indexFile(context);
            yes(index.getParentFile().mkdirs(), "library directory creation");
            byte[] invalid = new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xc3, 0x28, '"', '}'};
            Files.write(index.toPath(), invalid);

            LibraryStore.State state = LibraryStore.load(context);
            no(state.allowSave, "malformed UTF-8 index must disable writes");
            equal(0, state.items.size(), "malformed index must not expose partial items");
            yes(!state.damaged.isEmpty(), "malformed index must be reported as damage");
            bytes(invalid, Files.readAllBytes(index.toPath()), "load must preserve original index bytes");

            Path recovery = LibraryStore.directory(context).toPath().resolve("damaged/recovery");
            yes(Files.isDirectory(recovery), "raw recovery directory must exist");
            try (Stream<Path> files = Files.list(recovery)) {
                Path raw = files.filter(path -> path.getFileName().toString().startsWith("index-raw-"))
                        .filter(path -> path.getFileName().toString().endsWith(".bin"))
                        .findFirst().orElseThrow(() -> new AssertionError("raw recovery copy missing"));
                bytes(invalid, Files.readAllBytes(raw), "raw recovery copy must be byte-for-byte exact");
            }
        });
    }

    private static void attachmentMustBelongToItsItem() throws Exception {
        LibraryItem crossReference = item(A, "unsafe attachment owner");
        crossReference.attachment = B + "/original.docx";
        expect(IllegalArgumentException.class, crossReference::validate,
                "one item must not be allowed to claim another item's attachment");
    }

    private static void repeatedDamagedLoadsDeduplicateRecoveryCopies() throws Exception {
        withContext("damage-dedup", context -> {
            String valid = LibraryIndex.encodeV2(items(item(A, "valid")), 1);
            int end = valid.lastIndexOf(']');
            String damaged = valid.substring(0, end) + ",{\"id\":\"broken\"}" + valid.substring(end);
            File index = LibraryStore.indexFile(context);
            yes(index.getParentFile().mkdirs(), "create library directory for damaged fixture");
            Files.write(index.toPath(), damaged.getBytes(StandardCharsets.UTF_8));

            LibraryStore.State first = LibraryStore.load(context);
            yes(!first.damaged.isEmpty(), "fixture must create isolated damage");
            Path recovery = LibraryStore.directory(context).toPath().resolve("damaged/recovery");
            long before;
            try (Stream<Path> files = Files.list(recovery)) { before = files.count(); }
            LibraryStore.load(context);
            LibraryStore.load(context);
            long after;
            try (Stream<Path> files = Files.list(recovery)) { after = files.count(); }
            equal(before, after, "reloading identical damage must not amplify recovery storage");
        });
    }

    private static void rollbackRetainsUniqueStagedCopyUntilRetry() throws Exception {
        withContext("trash-rollback", context -> {
            LibraryItem item = item(C, "delete me");
            File source = new File(context.getFilesDir(), C + ".pdf");
            byte[] original = "unique original bytes".getBytes(StandardCharsets.UTF_8);
            byte[] conflict = "later path conflict".getBytes(StandardCharsets.UTF_8);
            Files.write(source.toPath(), original);

            TrashManager.Deletion deletion = TrashManager.stagePermanentDelete(context, item);
            File transaction = deletion.recoveryDirectory();
            File staged = new File(transaction, "content.pdf");
            no(source.exists(), "stage must remove content from its original path");
            bytes(original, Files.readAllBytes(staged.toPath()), "stage must hold the unique original copy");

            Files.write(source.toPath(), conflict);
            expect(IOException.class, deletion::rollback, "path conflict must make first rollback fail");
            yes(transaction.isDirectory(), "failed rollback must preserve transaction directory");
            yes(staged.isFile(), "failed rollback must preserve the unique staged copy");
            bytes(original, Files.readAllBytes(staged.toPath()), "failed rollback must not damage staged data");
            bytes(conflict, Files.readAllBytes(source.toPath()), "failed rollback must not overwrite conflict");
            no(deletion.isRolledBack(), "incomplete rollback must not report success");

            Files.delete(source.toPath());
            deletion.rollback();
            yes(deletion.isRolledBack(), "retry must complete rollback");
            no(transaction.exists(), "successful retry must clean the transaction directory");
            bytes(original, Files.readAllBytes(source.toPath()), "retry must restore the original bytes");
        });
    }

    private static void liveTrashTransactionIsNotRecoveredByAnotherActivity() throws Exception {
        withContext("trash-live", context -> {
            LibraryItem live = item(A, "active delete");
            LibraryStore.save(context, items(live));
            File source = new File(context.getFilesDir(), A + ".pdf");
            byte[] original = "active transaction bytes".getBytes(StandardCharsets.UTF_8);
            Files.write(source.toPath(), original);

            TrashManager.Deletion deletion = TrashManager.stagePermanentDelete(context, live);
            TrashManager.RecoveryResult concurrent = TrashManager.recoverPending(context);
            equal(0, concurrent.rolledBack, "recovery must skip an in-process active delete");
            equal(0, concurrent.cleaned, "recovery must not clean an in-process active delete");
            no(source.exists(), "active delete remains staged for its owning worker");
            yes(new File(deletion.recoveryDirectory(), "content.pdf").isFile(),
                    "active transaction retains its unique staged file");

            deletion.rollback();
            bytes(original, Files.readAllBytes(source.toPath()),
                    "owning worker can still roll back after a concurrent recovery scan");
        });
    }

    private static void damagedIndexNeverCleansAnAmbiguousTrashTransaction() throws Exception {
        withContext("trash-damaged-index", context -> {
            LibraryItem live = item(A, "damaged record owner");
            String valid = LibraryIndex.encodeV2(items(live), 1);
            LibraryStore.save(context, items(live));
            File source = new File(context.getFilesDir(), A + ".pdf");
            byte[] original = "only staged copy".getBytes(StandardCharsets.UTF_8);
            Files.write(source.toPath(), original);
            TrashManager.Deletion deletion = TrashManager.stagePermanentDelete(context, live);
            deletion.abandonForRecovery();

            int end = valid.lastIndexOf(']');
            String damaged = valid.substring(0, end) + ",{\"id\":\"broken\"}" + valid.substring(end);
            Files.write(LibraryStore.indexFile(context).toPath(), damaged.getBytes(StandardCharsets.UTF_8));
            LibraryStore.State partial = LibraryStore.load(context);
            yes(partial.allowSave && !partial.damaged.isEmpty(),
                    "fixture must be a partially readable item-damaged index");
            expect(IOException.class, () -> TrashManager.recoverPending(context),
                    "damaged index makes an absent transaction id ambiguous");
            yes(deletion.recoveryDirectory().isDirectory(),
                    "ambiguous transaction directory must be preserved");
            yes(new File(deletion.recoveryDirectory(), "content.pdf").isFile(),
                    "ambiguous transaction's unique bytes must not be deleted");

            Files.write(LibraryStore.indexFile(context).toPath(), valid.getBytes(StandardCharsets.UTF_8));
            deletion.rollback();
            bytes(original, Files.readAllBytes(source.toPath()),
                    "preserved ambiguous transaction remains recoverable");
        });
    }

    private static void startupRecoversOrCleansDurableTrashTransactions() throws Exception {
        withContext("trash-startup", context -> {
            byte[] first = "rollback after process death".getBytes(StandardCharsets.UTF_8);
            LibraryItem live = item(A, "still indexed");
            LibraryStore.save(context, items(live));
            File liveSource = new File(context.getFilesDir(), A + ".pdf");
            Files.write(liveSource.toPath(), first);
            TrashManager.Deletion interrupted = TrashManager.stagePermanentDelete(context, live);
            File interruptedRoot = interrupted.recoveryDirectory();
            interrupted.abandonForRecovery();

            TrashManager.RecoveryResult rollback = TrashManager.recoverPending(context);
            equal(1, rollback.rolledBack, "startup must roll back a transaction whose item remains indexed");
            equal(0, rollback.retained, "safe startup rollback must not retain warnings");
            bytes(first, Files.readAllBytes(liveSource.toPath()), "startup rollback must restore content");
            no(interruptedRoot.exists(), "startup rollback must remove its transaction directory");

            byte[] second = "cleanup after committed index".getBytes(StandardCharsets.UTF_8);
            LibraryItem removed = item(B, "index committed");
            LibraryStore.save(context, items(removed));
            File removedSource = new File(context.getFilesDir(), B + ".pdf");
            Files.write(removedSource.toPath(), second);
            TrashManager.Deletion committed = TrashManager.stagePermanentDelete(context, removed);
            File committedRoot = committed.recoveryDirectory();
            LibraryStore.save(context, new ArrayList<>());
            committed.abandonForRecovery();

            TrashManager.RecoveryResult cleanup = TrashManager.recoverPending(context);
            equal(1, cleanup.cleaned, "startup must clean a transaction after index commit");
            equal(0, cleanup.retained, "safe committed cleanup must not retain warnings");
            no(removedSource.exists(), "committed deletion must not restore removed content");
            no(committedRoot.exists(), "committed cleanup must remove its transaction directory");
        });
    }

    private static void oldFolderNamesBecomeStableIds() throws Exception {
        withContext("legacy-folder-write", context -> {
            LibraryItem old = item(A, "old");old.folder = "课程/高数";
            LibraryStore.save(context, items(old));
            LibraryStore.State first = LibraryStore.load(context);
            equal(1, first.folders.size(), "old folder must become one root folder");
            equal("课程/高数", first.folders.get(0).name, "old slash stays literal");
            String nextRevision = LibraryStore.saveIfRevision(context, first.revision, first.items);
            equal(nextRevision, LibraryStore.load(context).revision, "returned revision matches normalized stored index");
        });
    }

    private static void emptyFoldersSurviveOldItemWrites() throws Exception {
        withContext("empty-folders", context -> {
            LibraryFolder root = new LibraryFolder(B, "", "空目录");
            LibraryFolder nested = new LibraryFolder(C, B, "子目录");
            LibraryStore.save(context, items(item(A, "initial")), Arrays.asList(root, nested));
            LibraryStore.State first = LibraryStore.load(context);
            equal(2, first.folders.size(), "empty folders must load");
            LibraryStore.saveIfRevision(context, first.revision, items(item(A, "renamed")));
            LibraryStore.State after = LibraryStore.load(context);
            equal(2, after.folders.size(), "older item-only writer must retain empty folders");
            equal("子目录", after.folders.get(1).name, "nested empty folder must retain its name");
        });
    }

    private static void deletingLastPdfLeavesReadableEmptyLibrary() throws Exception {
        withContext("last-pdf", context -> {
            LibraryItem only = item(A, "last PDF");
            LibraryStore.save(context, items(only));
            File source = new File(context.getFilesDir(), A + ".pdf");
            Files.write(source.toPath(), "PDF fixture".getBytes(StandardCharsets.UTF_8));

            TrashManager.moveToTrash(only, System.currentTimeMillis());
            LibraryStore.save(context, items(only));
            LibraryStore.State trash = LibraryStore.load(context);
            equal(1, trash.items.size(), "soft delete must retain one trash entry");
            yes(trash.items.get(0).isTrashed(), "soft delete must mark the last PDF as trashed");

            TrashManager.Deletion deletion = TrashManager.stagePermanentDelete(context, trash.items.get(0));
            LibraryStore.mutate(context, items -> items.removeIf(item -> A.equals(item.id)));
            yes(deletion.commit().cleanupComplete, "last PDF cleanup must complete");
            LibraryStore.State empty = LibraryStore.load(context);
            yes(empty.allowSave, "empty library must remain writable");
            equal(0, empty.items.size(), "last PDF must disappear from the index");
            no(source.exists(), "last PDF payload must be gone");
        });
    }

    private static void deletingTrashOnlyFolderPreservesRecovery() throws Exception {
        withContext("delete-trash-folder", context -> {
            LibraryFolder parent = new LibraryFolder(B, "", "父目录");
            LibraryFolder child = new LibraryFolder(C, B, "待删除目录");
            LibraryItem document = item(A, "回收站资料");
            document.folderId = C; document.folder = child.name;
            File payload = new File(context.getFilesDir(), A + ".pdf");
            byte[] original = "PDF payload kept in trash".getBytes(StandardCharsets.UTF_8);
            Files.write(payload.toPath(), original);
            LibraryStore.save(context, items(document), Arrays.asList(parent, child));
            LibraryStore.State trash = LibraryStore.mutate(context, entries -> TrashManager.moveToTrash(entries.get(0), 1234));
            LibraryStore.State after = LibraryStore.deleteEmptyFolder(context, C, trash.revision);
            equal(1, after.folders.size(), "only the empty child folder is removed");
            equal(1, after.items.size(), "trash item stays in the index");
            LibraryItem kept = find(after, A);
            equal(B, kept.folderId, "trashed document falls back to parent folder");
            equal(parent.name, kept.folder, "legacy folder name follows parent");
            equal(1234L, kept.trashedAt, "folder deletion does not reset trash timestamp");
            bytes(original, Files.readAllBytes(payload.toPath()), "folder deletion cannot touch content");
            equal(C, find(trash, A).folderId, "loaded caller snapshot is not mutated");
            no(trash.revision.equals(after.revision), "folder deletion advances the revision");
            LibraryStore.State restored = LibraryStore.mutate(context, entries -> TrashManager.restore(entries.get(0), 2000));
            no(find(restored, A).isTrashed(), "trash restore makes document active");
            equal(B, find(restored, A).folderId, "restored document appears in parent folder");
            LibraryStore.State trashedAgain = LibraryStore.mutate(context, entries -> TrashManager.moveToTrash(entries.get(0), 3000));
            LibraryStore.State rootDeleted = LibraryStore.deleteEmptyFolder(context, B, trashedAgain.revision);
            equal(0, rootDeleted.folders.size(), "top-level empty folder can also be deleted");
            equal("", find(rootDeleted, A).folderId, "root deletion falls back to unfiled");
            equal("", find(rootDeleted, A).folder, "legacy name must not recreate deleted root folder");
            LibraryStore.State unfiled = LibraryStore.mutate(context, entries -> TrashManager.restore(entries.get(0), 4000));
            yes(unfiled.allowSave, "unfiled restored index remains writable");
            equal(0, unfiled.folders.size(), "reload must not resurrect the deleted folder");
            no(find(unfiled, A).isTrashed(), "unfiled document can be restored");
            bytes(original, Files.readAllBytes(payload.toPath()), "restored content survives both folder deletions");
        });
    }

    private static void deletingFolderProtectsActiveChildrenAndStaleState() throws Exception {
        withContext("delete-folder-protection", context -> {
            LibraryFolder parent = new LibraryFolder(B, "", "父目录");
            LibraryFolder child = new LibraryFolder(C, B, "空子目录");
            LibraryItem document = item(A, "活跃资料"); document.folderId = B; document.folder = parent.name;
            LibraryStore.save(context, items(document), Arrays.asList(parent));
            LibraryStore.State active = LibraryStore.load(context);
            expect(IOException.class, () -> LibraryStore.deleteEmptyFolder(context, B, active.revision), "active document blocks deletion");
            equal(active.revision, LibraryStore.load(context).revision, "blocked active delete leaves index unchanged");
            LibraryStore.State trash = LibraryStore.mutate(context, entries -> TrashManager.moveToTrash(entries.get(0), 1000));
            LibraryStore.saveIfRevision(context, trash.revision, trash.items, Arrays.asList(parent, child));
            LibraryStore.State nested = LibraryStore.load(context);
            expect(IOException.class, () -> LibraryStore.deleteEmptyFolder(context, B, nested.revision), "even an empty child folder blocks parent deletion");
            equal(nested.revision, LibraryStore.load(context).revision, "blocked child-folder delete leaves index unchanged");
            LibraryStore.State noChild = LibraryStore.deleteEmptyFolder(context, C, nested.revision);
            expect(LibraryStore.StaleIndexException.class, () -> LibraryStore.deleteEmptyFolder(context, B, nested.revision), "stale UI cannot delete after a folder change");
            LibraryStore.State restored = LibraryStore.mutate(context, entries -> TrashManager.restore(entries.get(0), 2000));
            expect(LibraryStore.StaleIndexException.class, () -> LibraryStore.deleteEmptyFolder(context, B, noChild.revision), "concurrent restore invalidates the old trash-only snapshot");
            no(find(LibraryStore.load(context), A).isTrashed(), "concurrent restored item remains active");
            expect(IOException.class, () -> LibraryStore.deleteEmptyFolder(context, B, restored.revision), "fresh snapshot still protects active item");
        });
    }

    private static void failedFolderDeletionLeavesIndexAndPayloadIntact() throws Exception {
        withContext("delete-folder-write-failure", context -> {
            LibraryFolder folder = new LibraryFolder(B, "", "目录");
            LibraryItem document = item(A, "回收站资料"); document.folderId = B; document.folder = folder.name;
            TrashManager.moveToTrash(document, 1000);
            LibraryStore.save(context, items(document), Arrays.asList(folder));
            File payload = new File(context.getFilesDir(), A + ".pdf");
            byte[] original = new byte[]{4, 5, 6}; Files.write(payload.toPath(), original);
            File index = LibraryStore.indexFile(context);
            byte[] indexBefore = Files.readAllBytes(index.toPath());
            LibraryStore.State before = LibraryStore.load(context);
            // Occupy AtomicFile's staging path with a directory: startWrite must fail on a real filesystem.
            File blocked = new File(index.getPath() + ".new"); yes(blocked.mkdir(), "create index write failure fixture");
            expect(IOException.class, () -> LibraryStore.deleteEmptyFolder(context, B, before.revision), "failed index commit must report failure");
            bytes(indexBefore, Files.readAllBytes(index.toPath()), "failed delete preserves exact original index bytes");
            bytes(original, Files.readAllBytes(payload.toPath()), "failed delete preserves document payload");
            LibraryStore.State after = LibraryStore.load(context);
            equal(before.revision, after.revision, "failed delete preserves original revision");
            equal(B, find(after, A).folderId, "failed delete preserves restore destination");
            equal(1, after.folders.size(), "failed delete preserves folder");
            yes(blocked.delete(), "remove owned failure fixture");
            equal(0, LibraryStore.deleteEmptyFolder(context, B, after.revision).folders.size(), "same operation can retry after storage recovers");
        });
    }

    private static void damagedIndexCannotDeleteFolders() throws Exception {
        withContext("delete-folder-damaged-index", context -> {
            LibraryFolder folder = new LibraryFolder(B, "", "目录");
            LibraryItem document = item(A, "回收站资料"); document.folderId = B; document.folder = folder.name;
            TrashManager.moveToTrash(document, 1000);
            LibraryStore.save(context, items(document), Arrays.asList(folder));
            File index = LibraryStore.indexFile(context);
            String damaged = new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8).replace("\"items\":[", "\"items\":[{},");
            Files.write(index.toPath(), damaged.getBytes(StandardCharsets.UTF_8));
            LibraryStore.State partial = LibraryStore.load(context);
            yes(!partial.damaged.isEmpty(), "fixture must contain an isolated unreadable record");
            expect(IOException.class, () -> LibraryStore.deleteEmptyFolder(context, B, partial.revision), "partial index cannot establish that folder is empty");
            bytes(damaged.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(index.toPath()), "partial damaged index remains byte-identical");
            byte[] malformed = "{not-json".getBytes(StandardCharsets.UTF_8); Files.write(index.toPath(), malformed);
            LibraryStore.State unreadable = LibraryStore.load(context);
            no(unreadable.allowSave, "malformed index is write-protected");
            expect(IOException.class, () -> LibraryStore.deleteEmptyFolder(context, B, unreadable.revision), "write-protected index cannot delete folders");
            bytes(malformed, Files.readAllBytes(index.toPath()), "malformed index remains byte-identical");
        });
    }

    private static LibraryItem find(LibraryStore.State state, String id) {
        for (LibraryItem item : state.items) if (id.equals(item.id)) return item;
        throw new AssertionError("item missing: " + id);
    }

    private static ArrayList<LibraryItem> items(LibraryItem... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static LibraryItem item(String id, String name) {
        LibraryItem item = new LibraryItem();
        item.id = id;
        item.name = name;
        item.originalName = name;
        item.created = 1;
        item.modified = 1;
        return item;
    }

    private interface ContextCheck { void run(Context context) throws Exception; }
    private interface ThrowingAction { void run() throws Exception; }

    private static void withContext(String label, ContextCheck check) throws Exception {
        Path root = Files.createTempDirectory("qingyue-manager-" + label + "-");
        try {
            check.run(new Context(root.toFile()));
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (IOException failure) { throw new RuntimeException(failure); }
                });
            }
        }
    }

    private static void expect(Class<? extends Throwable> type, ThrowingAction action, String message)
            throws Exception {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError(message + ": wrong failure " + failure, failure);
        }
        throw new AssertionError(message + ": no failure thrown");
    }

    private static void bytes(byte[] expected, byte[] actual, String message) {
        if (!Arrays.equals(expected, actual)) throw new AssertionError(message);
    }

    private static void yes(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void no(boolean value, String message) {
        if (value) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": " + actual + " != " + expected);
        }
    }
}
