package cn.qingyue.pdf;

import android.content.Context;
import android.net.Uri;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real one-to-two-page insertion raced with production snapshot and SAF export paths. */
public final class StorageAuditTest {
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    private static final String ORPHAN = "22222222-2222-4222-8222-222222222222";
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("storage-audit-").toFile();
        Context context = new Context(root);
        PDFBoxResourceLoader.init(context);
        File original = new File(root, ID + ".pdf");
        onePage(original);
        LibraryItem item = new LibraryItem();
        item.id = ID; item.name = item.originalName = "Book"; item.pages = 1;
        LibraryStore.save(context, Collections.singletonList(item));
        LibraryStore.State stale = LibraryStore.load(context);
        PdfPageInsertion.awaitRecovery(context);

        CountDownLatch prepared = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> insertionFailure = new AtomicReference<>();
        PdfPageInsertion.testFault = phase -> {
            if (!phase.equals("prepared")) return;
            prepared.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("test release timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
        };
        Thread inserter = new Thread(() -> {
            try { PdfPageInsertion.insertBlankPage(context, ID, 1, 1); }
            catch (Throwable failure) { insertionFailure.set(failure); }
        }, "production-insertion");
        inserter.start();
        check(prepared.await(10, TimeUnit.SECONDS), "insertion did not reach prepared phase");

        File snapshot = new File(root, "raced.qyb");
        AtomicReference<Throwable> backupFailure = new AtomicReference<>();
        Thread backup = new Thread(() -> {
            try { BackupManager.createSnapshot(context, stale.items, snapshot,
                    BackupManager.NEVER_CANCEL, BackupManager.NO_PROGRESS); }
            catch (Throwable failure) { backupFailure.set(failure); }
        }, "production-snapshot");
        backup.start();
        Thread.sleep(150);
        check(backup.isAlive() && !snapshot.exists(), "snapshot bypassed insertion transaction");
        release.countDown();
        inserter.join(20000); backup.join(20000); PdfPageInsertion.testFault = null;
        check(!inserter.isAlive() && !backup.isAlive(), "transaction deadlock");
        if (insertionFailure.get() != null) throw new AssertionError("insertion failed", insertionFailure.get());
        if (backupFailure.get() != null) throw new AssertionError("snapshot failed", backupFailure.get());
        check(LibraryStore.load(context).items.get(0).pages == 2, "live index did not commit two pages");
        verifyArchive(snapshot, 2);

        File metadataSnapshot = new File(root, "metadata-snapshot.qyb");
        AtomicBoolean snapshotMutation = new AtomicBoolean();
        BackupManager.createSnapshot(context, metadataSnapshot,
                BackupManager.NEVER_CANCEL, mutateMetadataDuringWrite(context, "Updated", snapshotMutation));
        check(snapshotMutation.get(), "snapshot did not exercise archive write callback");
        check(archiveName(metadataSnapshot).equals("Book"), "snapshot lost captured metadata");
        check(LibraryStore.load(context).items.get(0).name.equals("Updated"), "snapshot metadata mutation missing");

        File exported = new File(root, "export.qyb");
        AtomicBoolean exportMutation = new AtomicBoolean();
        BackupManager.exportTo(context, new Uri(exported),
                mutateMetadataDuringWrite(context, "Newer", exportMutation),
                BackupManager.NEVER_CANCEL);
        check(exportMutation.get(), "export did not exercise archive write callback");
        check(archiveName(exported).equals("Updated"), "export lost captured metadata");
        check(LibraryStore.load(context).items.get(0).name.equals("Newer"), "export metadata mutation missing");
        verifyArchive(exported, 2);

        onePage(new File(root, ORPHAN + ".pdf"));
        LibraryItem second = new LibraryItem();
        second.id = ORPHAN; second.name = second.originalName = "Second"; second.pages = 1;
        LibraryStore.mutate(context, entries -> entries.add(second));
        File subset = new File(root, "subset.qyb");
        BackupManager.createSnapshot(context, stale.items, subset,
                BackupManager.NEVER_CANCEL, BackupManager.NO_PROGRESS);
        verifyArchive(subset, 2);
        check(BackupManager.validate(subset).itemCount == 1,
                "legacy selection unexpectedly expanded to all current items");
        LibraryStore.mutate(context, entries -> {
            entries.removeIf(entry -> ORPHAN.equals(entry.id));
            entries.get(0).trashedAt = 1234;
        });
        LibraryStore.State rebuilt = LibraryStore.rebuild(context, LibraryStore.load(context).items);
        check(rebuilt.items.size() == 2, "orphan was not recovered");
        LibraryItem trashed = null, orphan = null;
        for (LibraryItem candidate : rebuilt.items) {
            if (ID.equals(candidate.id)) trashed = candidate;
            if (ORPHAN.equals(candidate.id)) orphan = candidate;
        }
        check(trashed != null && trashed.trashedAt == 1234, "valid trash hint was discarded");
        check(orphan != null && orphan.trashedAt == 0, "orphan must be active");
        check(LibraryStore.load(context).items.stream().anyMatch(entry ->
                ID.equals(entry.id) && entry.trashedAt == 1234), "saved rebuild lost trash date");
        System.out.println("PASS: raced insertion, snapshot/export index and PDF pages, concurrent metadata writes, legacy subset, trash hint, orphan recovery");
    }

    private static BackupManager.Progress mutateMetadataDuringWrite(Context context, String name,
                                                                     AtomicBoolean reached) {
        return (phase, detail, bytes, total, entries, count) -> {
            if (!phase.equals("export") || !reached.compareAndSet(false, true)) return;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread writer = new Thread(() -> {
                try {
                    check(!LibraryStore.load(context).items.isEmpty(), "concurrent index load failed");
                    LibraryStore.mutate(context, items -> items.get(0).name = name);
                } catch (Throwable problem) { failure.set(problem); }
            }, "metadata-during-archive-write");
            writer.start();
            try { writer.join(2000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            check(!writer.isAlive(), "archive write blocked index load or metadata mutation");
            if (failure.get() != null) throw new AssertionError("concurrent metadata mutation failed", failure.get());
        };
    }

    private static String archiveName(File archive) throws Exception {
        BackupManager.PreparedSnapshot checked = BackupManager.validate(archive);
        LibraryIndex.Result index = LibraryIndex.decodeV2(checked.manifest.metadataString("libraryIndex"));
        return index.items.get(0).name;
    }

    private static void onePage(File target) throws Exception {
        try (PDDocument pdf = new PDDocument()) { pdf.addPage(new PDPage()); pdf.save(target); }
    }

    private static void verifyArchive(File archive, int expectedPages) throws Exception {
        BackupManager.PreparedSnapshot checked = BackupManager.validate(archive);
        check(checked.itemCount == 1, "archive validation item count");
        LibraryIndex.Result index = LibraryIndex.decodeV2(checked.manifest.metadataString("libraryIndex"));
        check(index.items.size() == 1 && index.items.get(0).pages == expectedPages,
                "archive index has stale page count");
        File extracted = new File(Files.createTempDirectory("storage-audit-extracted-").toFile(), "payload");
        try (InputStream input = Files.newInputStream(archive.toPath())) {
            BackupArchive.extractValidated(input, extracted, BackupArchive.Limits.defaults(), null, null);
        }
        try (PDDocument pdf = PDDocument.load(new File(extracted,
                "documents/" + ID + "/content.pdf"))) {
            check(pdf.getNumberOfPages() == expectedPages, "archive payload has mixed PDF revision");
        }
    }
}
