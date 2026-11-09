package cn.qingyue.pdf;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android storage/SAF adapter for {@link BackupArchive}. */
public final class BackupManager {
    static final Object TRANSACTION_LOCK = new Object();
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_TREE_DEPTH = 16;
    private static final int MAX_TREE_FILES = 50_000;
    private static final Pattern INK_NAME = Pattern.compile(
            "^([0-9a-fA-F-]{36})-([0-9]+)\\.ink(\\.clear)?(?:\\.(bak|new))?$");
    private static final Pattern SAFE_EXTENSION = Pattern.compile("[A-Za-z0-9]{1,10}");

    private BackupManager() { }

    public enum ConflictPolicy { KEEP_BOTH }

    public interface Cancellation { boolean isCancelled(); }

    public interface Progress {
        void onProgress(String phase, String detail, long completedBytes, long totalBytes,
                        int completedEntries, int totalEntries);
    }

    /**
     * Optional semantic rewriter supplied by the learning module. It must update card/source-link
     * contents after document IDs are remapped. Without this hook, a restore containing learning
     * data and any ID conflict fails closed instead of leaving broken links.
     */
    public interface LearningRemapper {
        void remap(File stagedLearningDirectory, Map<String, String> documentIdMap,
                   Cancellation cancellation) throws IOException;
    }

    public static final Cancellation NEVER_CANCEL = new Cancellation() {
        @Override public boolean isCancelled() { return false; }
    };
    public static final Progress NO_PROGRESS = new Progress() {
        @Override public void onProgress(String phase, String detail, long completedBytes,
                                         long totalBytes, int completedEntries,
                                         int totalEntries) { }
    };

    public static class Result {
        public final int itemCount, entryCount, conflictCopies;
        public final long bytes;
        public final String warning;

        Result(int itemCount, int entryCount, int conflictCopies, long bytes, String warning) {
            this.itemCount = itemCount;
            this.entryCount = entryCount;
            this.conflictCopies = conflictCopies;
            this.bytes = bytes;
            this.warning = warning == null ? "" : warning;
        }
    }

    /** File snapshot descriptor used by local sync transports. */
    public static final class PreparedSnapshot {
        public final File file;
        public final String stableFingerprint;
        public final BackupArchive.Manifest manifest;
        public final int itemCount;

        PreparedSnapshot(File file, String stableFingerprint, BackupArchive.Manifest manifest,
                         int itemCount) {
            this.file = file;
            this.stableFingerprint = stableFingerprint;
            this.manifest = manifest;
            this.itemCount = itemCount;
        }
    }

    public static final class RestoreResult extends Result {
        /** Durable pre-restore snapshot, or null when the caller disabled it. */
        public final File safetyCopy;

        RestoreResult(int itemCount, int entryCount, int conflictCopies, long bytes,
                      String warning, File safetyCopy) {
            super(itemCount, entryCount, conflictCopies, bytes, warning);
            this.safetyCopy = safetyCopy;
        }
    }

    /** Immutable source snapshot description. Streams are opened lazily by BackupArchive. */
    public static final class SourceSet {
        public final List<BackupArchive.EntrySource> entries;
        public final String metadataJson;
        public final int itemCount;

        SourceSet(List<BackupArchive.EntrySource> entries, String metadataJson, int itemCount) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.metadataJson = metadataJson;
            this.itemCount = itemCount;
        }
    }

    /**
     * Builds the complete list of payloads for a library snapshot. Notes are copied as opaque
     * files; this layer never parses or rewrites qnote data.
     */
    public static SourceSet buildSources(Context context, Collection<LibraryItem> items)
            throws IOException {
        if (context == null) throw new NullPointerException("context");
        if (items == null) throw new NullPointerException("items");
        Context app = context.getApplicationContext();
        LibraryStore.State library = LibraryStore.load(app);
        if (!library.allowSave) throw new IOException("资料库索引损坏，不能备份文件夹结构");
        return buildSources(app, items, library.folders);
    }

    private static SourceSet buildSources(Context app, Collection<LibraryItem> items,
                                          Collection<LibraryFolder> folders) throws IOException {
        ArrayList<LibraryItem> snapshot = new ArrayList<>();
        HashSet<String> ids = new HashSet<>();
        for (LibraryItem source : items) {
            if (source == null) throw new IOException("备份中存在空的资料记录");
            LibraryItem item = source.copy();
            try { item.validate(); }
            catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
            if (!ids.add(item.id)) throw new IOException("备份中存在重复的资料编号");
            snapshot.add(item);
        }
        if (snapshot.size() > LibraryIndex.MAX_ITEMS) throw new IOException("资料数量超过备份上限");
        Collections.sort(snapshot, new Comparator<LibraryItem>() {
            @Override public int compare(LibraryItem left, LibraryItem right) {
                return left.id.compareTo(right.id);
            }
        });

        ArrayList<BackupArchive.EntrySource> entries = new ArrayList<>();
        File files = app.getFilesDir();
        // Deliberate allowlist: cache, files/import-staging, files/.restore, sync state and any
        // credential/keystore locations are never traversed. Android Keystore keys are not files
        // available to this process. Only committed library payload roots below are considered.
        File attachments = LibraryStore.attachmentsDirectory(app);
        for (LibraryItem item : snapshot) {
            if (item.isNote()) {
                File note = new File(new File(files, "notes"), item.id);
                TreeMap<String, File> opaque = collectAtomicTree(note);
                if (!opaque.containsKey("document.qnote")) {
                    throw new IOException("找不到笔记原档：" + item.name);
                }
                for (Map.Entry<String, File> file : opaque.entrySet()) {
                    entries.add(BackupArchive.EntrySource.fromFile(
                            "notes/" + item.id + "/" + file.getKey(), "note-file", item.id,
                            file.getValue()));
                }
            } else {
                File pdf = new File(files, item.id + ".pdf");
                requireRegularFile(pdf, "找不到 PDF 原文：" + item.name);
                entries.add(BackupArchive.EntrySource.fromFile(
                        "documents/" + item.id + "/content.pdf", "document", item.id, pdf));
                addInkSources(files, item, entries);
                addReaderSource(files, item, entries);
                File recognition = new File(new File(files, "recognition"), item.id);
                if (recognition.isDirectory()) {
                    for (Map.Entry<String, File> sidecar : collectAtomicTree(recognition).entrySet()) {
                        if (!sidecar.getKey().matches("page-[0-9]+\\.txt"))
                            throw new IOException("未知的 OCR 侧车文件：" + sidecar.getKey());
                        entries.add(BackupArchive.EntrySource.fromFile(
                                "documents/" + item.id + "/recognition/" + sidecar.getKey(),
                                "recognition", item.id, sidecar.getValue()));
                    }
                }
            }
            if (!item.attachment.isEmpty()) {
                File attachment = contained(attachments, item.attachment);
                requireRegularFile(attachment, "找不到保留的原文件：" + item.name);
                String archiveName = "attachment." + extension(attachment.getName());
                entries.add(BackupArchive.EntrySource.fromFile(
                        "documents/" + item.id + "/original/" + archiveName,
                        "original", item.id, attachment));
            }
        }

        File learning = new File(files, "learning");
        if (learning.isDirectory()) {
            TreeMap<String, File> resources = collectRawTree(learning);
            for (Map.Entry<String, File> resource : resources.entrySet()) {
                String first = firstSegment(resource.getKey());
                String owner = LibraryItem.isUuid(first) ? first : "";
                entries.add(BackupArchive.EntrySource.fromFile(
                        "learning/" + resource.getKey(), "learning", owner,
                        resource.getValue()));
            }
        }

        String index = LibraryIndex.encodeV2(snapshot, folders, System.currentTimeMillis());
        String metadata = "{\"backupSchema\":1,\"libraryIndex\":" + jsonQuote(index) + "}";
        return new SourceSet(entries, metadata, snapshot.size());
    }

    /** Resolve legacy item selections against the committed index while holding the transaction. */
    private static ArrayList<LibraryItem> selectCurrentItems(Collection<LibraryItem> requested,
                                                               LibraryStore.State library)
            throws IOException {
        if (!library.allowSave) throw new IOException("资料库索引损坏，不能备份");
        if (requested == null) {
            ArrayList<LibraryItem> selected = new ArrayList<>();
            for (LibraryItem item : library.items) selected.add(item.copy());
            return selected;
        }
        HashMap<String, LibraryItem> current = new HashMap<>();
        for (LibraryItem item : library.items) current.put(item.id, item);
        ArrayList<LibraryItem> selected = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (LibraryItem item : requested) {
            if (item == null) throw new IOException("备份中存在空的资料记录");
            if (!seen.add(item.id)) throw new IOException("备份中存在重复的资料编号");
            LibraryItem live = current.get(item.id);
            if (live == null) throw new IOException("资料已变化，请重新备份：" + item.id);
            selected.add(live.copy());
        }
        return selected;
    }

    private static ArrayList<LibraryFolder> copyFolders(LibraryStore.State library) {
        ArrayList<LibraryFolder> folders = new ArrayList<>();
        for (LibraryFolder folder : library.folders) folders.add(folder.copy());
        return folders;
    }

    /**
     * Creates an immutable, self-validated file snapshot for sync or durable safety copies.
     * {@code target} must not exist; a sibling partial file is atomically renamed only after
     * validation succeeds.
     */
    public static PreparedSnapshot createSnapshot(Context context, Collection<LibraryItem> items,
                                                  File target, BackupArchive.Limits limits,
                                                  Cancellation cancellation, Progress progress)
            throws IOException {
        if (context == null) throw new NullPointerException("context");
        if (target == null) throw new NullPointerException("target");
        Context app = context.getApplicationContext();
        limits = limits == null ? BackupArchive.Limits.defaults() : limits;
        cancellation = cancellation == null ? NEVER_CANCEL : cancellation;
        progress = progress == null ? NO_PROGRESS : progress;
        if (target.exists()) throw new IOException("快照目标已存在，拒绝覆盖");
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法建立快照目录");
        }
        File partial = new File(parent, "." + target.getName() + ".partial-"
                + UUID.randomUUID().toString());
        SourceSet sources;
        try {
            synchronized (TRANSACTION_LOCK) {
                synchronized (NoteStore.class) { synchronized (ReaderStore.class) { synchronized (InkStore.class) {
                    checkCancelled(cancellation);
                    ArrayList<LibraryItem> selected;
                    ArrayList<LibraryFolder> folders;
                    synchronized (LibraryStore.transactionLock()) {
                        LibraryStore.State library = LibraryStore.load(app);
                        selected = selectCurrentItems(items, library);
                        folders = copyFolders(library);
                    }
                    settlePendingNotes(app, selected, cancellation);
                    settlePendingReader(app, selected, cancellation);
                    sources = buildSources(app, selected, folders);
                    try (FileOutputStream output = new FileOutputStream(partial)) {
                        BackupArchive.write(output, sources.entries, sources.metadataJson, limits,
                                archiveProgress(progress), archiveCancellation(cancellation));
                        output.getFD().sync();
                    }
                } } }
            }
            PreparedSnapshot checked = validate(partial, limits, progress, cancellation);
            checkCancelled(cancellation);
            if (target.exists() || !partial.renameTo(target)) {
                throw new IOException("无法提交已校验的快照");
            }
            return new PreparedSnapshot(target, checked.stableFingerprint, checked.manifest,
                    checked.itemCount);
        } finally {
            partial.delete();
        }
    }

    public static PreparedSnapshot createSnapshot(Context context, Collection<LibraryItem> items,
                                                  File target, Cancellation cancellation,
                                                  Progress progress) throws IOException {
        return createSnapshot(context, items, target, BackupArchive.Limits.defaults(),
                cancellation, progress);
    }

    /** Complete library snapshot; resolves the index inside the same transaction as payloads. */
    public static PreparedSnapshot createSnapshot(Context context, File target,
                                                  Cancellation cancellation, Progress progress)
            throws IOException {
        return createSnapshot(context, null, target, BackupArchive.Limits.defaults(),
                cancellation, progress);
    }

    /** Fully validates a snapshot file without changing the library or retaining extracted data. */
    public static PreparedSnapshot validate(File archive, BackupArchive.Limits limits,
                                            Progress progress, Cancellation cancellation)
            throws IOException {
        if (archive == null || !archive.isFile()) throw new IOException("找不到快照文件");
        limits = limits == null ? BackupArchive.Limits.defaults() : limits;
        progress = progress == null ? NO_PROGRESS : progress;
        cancellation = cancellation == null ? NEVER_CANCEL : cancellation;
        File parent = archive.getAbsoluteFile().getParentFile();
        if (parent == null) throw new IOException("快照文件没有父目录");
        File staging = new File(parent, ".validate-" + UUID.randomUUID().toString());
        try {
            BackupArchive.ValidatedArchive validated;
            try (InputStream input = new BufferedInputStream(new FileInputStream(archive))) {
                validated = BackupArchive.extractValidated(input, staging, limits,
                        archiveProgress(progress), archiveCancellation(cancellation));
            }
            String indexJson = validated.manifest.metadataString("libraryIndex");
            LibraryIndex.Result decoded = LibraryIndex.decodeV2(indexJson);
            if (!decoded.allowSave || !decoded.damaged.isEmpty()) {
                throw new IOException("快照中的资料索引损坏");
            }
            validateIncoming(decoded.items, validated.manifest);
            return new PreparedSnapshot(archive,
                    stableFingerprint(decoded.items, decoded.folders, validated.manifest), validated.manifest,
                    decoded.items.size());
        } finally {
            deleteTree(staging);
        }
    }

    public static PreparedSnapshot validate(File archive) throws IOException {
        return validate(archive, BackupArchive.Limits.defaults(), NO_PROGRESS, NEVER_CANCEL);
    }

    /**
     * Creates a private complete archive, validates it by extracting it again, and only then
     * copies it to the selected SAF URI. A provider may retain a partial external file if its own
     * write fails; the local library is never modified.
     */
    public static Result exportTo(Context context, Uri destination, Collection<LibraryItem> items,
                                  BackupArchive.Limits limits, Progress progress,
                                  Cancellation cancellation) throws IOException {
        if (destination == null) throw new IOException("未选择备份保存位置");
        Context app = context.getApplicationContext();
        limits = limits == null ? BackupArchive.Limits.defaults() : limits;
        progress = progress == null ? NO_PROGRESS : progress;
        cancellation = cancellation == null ? NEVER_CANCEL : cancellation;
        checkCancelled(cancellation);
        File cache = new File(app.getCacheDir(), "qingyue-backup");
        if (!cache.isDirectory() && !cache.mkdirs()) throw new IOException("无法建立备份临时目录");
        File archive = File.createTempFile("export-", ".qyb", cache);
        File verify = new File(cache, "verify-" + UUID.randomUUID().toString());
        SourceSet sources;
        BackupArchive.Manifest manifest;
        try {
            synchronized (TRANSACTION_LOCK) {
                synchronized (NoteStore.class) { synchronized (ReaderStore.class) { synchronized (InkStore.class) {
                    ArrayList<LibraryItem> selected;
                    ArrayList<LibraryFolder> folders;
                    synchronized (LibraryStore.transactionLock()) {
                        LibraryStore.State library = LibraryStore.load(app);
                        selected = selectCurrentItems(items, library);
                        folders = copyFolders(library);
                    }
                    settlePendingNotes(app, selected, cancellation);
                    settlePendingReader(app, selected, cancellation);
                    sources = buildSources(app, selected, folders);
                    progress.onProgress("prepare", "正在整理本机资料", 0, -1, 0,
                            sources.entries.size());
                    try (FileOutputStream output = new FileOutputStream(archive)) {
                        manifest = BackupArchive.write(output, sources.entries,
                                sources.metadataJson, limits, archiveProgress(progress),
                                archiveCancellation(cancellation));
                        output.getFD().sync();
                    }
                } } }
            }
            checkCancelled(cancellation);
            progress.onProgress("verify", "正在校验备份", 0, manifest.totalBytes, 0,
                    manifest.entries().size());
            try (InputStream input = new BufferedInputStream(new FileInputStream(archive))) {
                BackupArchive.extractValidated(input, verify, limits, archiveProgress(progress),
                        archiveCancellation(cancellation));
            }
            deleteTree(verify);
            checkCancelled(cancellation);
            progress.onProgress("copy", "正在写入所选位置", 0, archive.length(), 0, 1);
            ContentResolver resolver = app.getContentResolver();
            OutputStream raw = resolver.openOutputStream(destination, "w");
            if (raw == null) throw new IOException("系统无法写入所选位置");
            try (InputStream input = new BufferedInputStream(new FileInputStream(archive));
                 OutputStream output = new BufferedOutputStream(raw)) {
                copy(input, output, archive.length(), "copy", "正在写入所选位置", progress,
                        cancellation);
                output.flush();
            }
            progress.onProgress("complete", "备份已保存", manifest.totalBytes,
                    manifest.totalBytes, manifest.entries().size(), manifest.entries().size());
            return new Result(sources.itemCount, manifest.entries().size(), 0,
                    manifest.totalBytes, "");
        } finally {
            deleteTree(verify);
            archive.delete();
        }
    }

    public static Result exportTo(Context context, Uri destination, Collection<LibraryItem> items,
                                  Progress progress, Cancellation cancellation) throws IOException {
        return exportTo(context, destination, items, BackupArchive.Limits.defaults(), progress,
                cancellation);
    }

    /** Complete library export; resolves the index inside the same transaction as payloads. */
    public static Result exportTo(Context context, Uri destination, Progress progress,
                                  Cancellation cancellation) throws IOException {
        return exportTo(context, destination, null, BackupArchive.Limits.defaults(), progress,
                cancellation);
    }

    private static void settlePendingNotes(Context app, Collection<LibraryItem> items,
                                           Cancellation cancellation) throws IOException {
        for (LibraryItem item : items) {
            checkCancelled(cancellation);
            if (item != null && item.isNote())
                NoteStore.save(app, item.id, NoteStore.load(app, item.id));
        }
    }

    private static void settlePendingReader(Context app, Collection<LibraryItem> items,
                                            Cancellation cancellation) throws IOException {
        for (LibraryItem item : items) {
            checkCancelled(cancellation);
            if (item != null && !item.isNote()) {
                ReaderStore.flushPending(app.getFilesDir(), item.id);
                InkStore.flushDocument(app.getFilesDir(), item.id);
            }
        }
    }

    /**
     * Validates a selected backup into filesDir-local staging and merges it with KEEP_BOTH
     * semantics. All payloads are installed before the atomically-written library index. Any
     * in-process failure before that final index write removes every file created by this
     * transaction and leaves the prior index untouched.
     */
    public static RestoreResult restoreFrom(Context context, Uri source, ConflictPolicy policy,
                                            BackupArchive.Limits limits, Progress progress,
                                            Cancellation cancellation) throws IOException {
        return restoreFrom(context, source, policy, limits, progress, cancellation, null);
    }

    public static RestoreResult restoreFrom(Context context, Uri source, ConflictPolicy policy,
                                            BackupArchive.Limits limits, Progress progress,
                                            Cancellation cancellation,
                                            LearningRemapper learningRemapper)
            throws IOException {
        if (source == null) throw new IOException("未选择要恢复的备份");
        if (policy != ConflictPolicy.KEEP_BOTH) throw new IOException("不支持的冲突处理方式");
        Context app = context.getApplicationContext();
        limits = limits == null ? BackupArchive.Limits.defaults() : limits;
        progress = progress == null ? NO_PROGRESS : progress;
        cancellation = cancellation == null ? NEVER_CANCEL : cancellation;
        checkCancelled(cancellation);

        File restoreRoot = new File(app.getFilesDir(), ".restore");
        if (!restoreRoot.isDirectory() && !restoreRoot.mkdirs()) {
            throw new IOException("无法建立恢复临时目录");
        }
        String transactionId = UUID.randomUUID().toString();
        File transaction = new File(restoreRoot, transactionId);
        if (!transaction.mkdir()) throw new IOException("无法建立恢复事务");
        File validated = new File(transaction, "validated");
        BackupArchive.ValidatedArchive archive;
        try {
            InputStream raw = app.getContentResolver().openInputStream(source);
            if (raw == null) throw new IOException("系统无法读取所选备份");
            try (InputStream input = new BufferedInputStream(raw)) {
                archive = BackupArchive.extractValidated(input, validated, limits,
                        archiveProgress(progress), archiveCancellation(cancellation));
            }
            checkCancelled(cancellation);
            return restoreValidated(app, archive, transactionId, policy, progress, cancellation,
                    learningRemapper, true);
        } finally {
            deleteTree(transaction);
            File[] left = restoreRoot.listFiles();
            if (left != null && left.length == 0) restoreRoot.delete();
        }
    }

    public static RestoreResult restoreFrom(Context context, Uri source, ConflictPolicy policy,
                                            Progress progress, Cancellation cancellation)
            throws IOException {
        return restoreFrom(context, source, policy, BackupArchive.Limits.defaults(), progress,
                cancellation);
    }

    /** Package/file restore entry point for sync transports and private safety-copy workflows. */
    public static RestoreResult restore(Context context, File source, ConflictPolicy policy,
                                        boolean createSafetyCopy, BackupArchive.Limits limits,
                                        Progress progress, Cancellation cancellation,
                                        LearningRemapper learningRemapper) throws IOException {
        if (source == null || !source.isFile()) throw new IOException("找不到要恢复的快照");
        if (policy != ConflictPolicy.KEEP_BOTH) throw new IOException("不支持的冲突处理方式");
        Context app = context.getApplicationContext();
        limits = limits == null ? BackupArchive.Limits.defaults() : limits;
        progress = progress == null ? NO_PROGRESS : progress;
        cancellation = cancellation == null ? NEVER_CANCEL : cancellation;
        File restoreRoot = new File(app.getFilesDir(), ".restore");
        if (!restoreRoot.isDirectory() && !restoreRoot.mkdirs()) {
            throw new IOException("无法建立恢复临时目录");
        }
        String transactionId = UUID.randomUUID().toString();
        File transaction = new File(restoreRoot, transactionId);
        if (!transaction.mkdir()) throw new IOException("无法建立恢复事务");
        File validated = new File(transaction, "validated");
        try {
            BackupArchive.ValidatedArchive archive;
            try (InputStream input = new BufferedInputStream(new FileInputStream(source))) {
                archive = BackupArchive.extractValidated(input, validated, limits,
                        archiveProgress(progress), archiveCancellation(cancellation));
            }
            return restoreValidated(app, archive, transactionId, policy, progress, cancellation,
                    learningRemapper, createSafetyCopy);
        } finally {
            deleteTree(transaction);
            File[] left = restoreRoot.listFiles();
            if (left != null && left.length == 0) restoreRoot.delete();
        }
    }

    public static RestoreResult restore(Context context, File source, ConflictPolicy policy,
                                        boolean createSafetyCopy, Cancellation cancellation,
                                        Progress progress) throws IOException {
        return restore(context, source, policy, createSafetyCopy,
                BackupArchive.Limits.defaults(), progress, cancellation, null);
    }

    private static RestoreResult restoreValidated(Context app,
                                                  BackupArchive.ValidatedArchive archive,
                                                  String transactionId, ConflictPolicy policy,
                                                  Progress progress, Cancellation cancellation,
                                                  LearningRemapper learningRemapper,
                                                  boolean createSafetyCopy)
            throws IOException {
        String indexJson = archive.manifest.metadataString("libraryIndex");
        LibraryIndex.Result decoded = LibraryIndex.decodeV2(indexJson);
        if (!decoded.allowSave || !decoded.damaged.isEmpty()) {
            throw new IOException("备份中的资料索引损坏");
        }
        Incoming incoming = validateIncoming(decoded.items, archive.manifest);
        synchronized (TRANSACTION_LOCK) {
            checkCancelled(cancellation);
            LibraryStore.State current = LibraryStore.load(app);
            if (!current.allowSave) throw new IOException("当前资料库索引损坏，不能执行恢复");
            String originalFingerprint = current.revision;
            File safetyCopy = null;
            if (createSafetyCopy) {
                File safetyDirectory = new File(LibraryStore.directory(app), "backups");
                if (!safetyDirectory.isDirectory() && !safetyDirectory.mkdirs()) {
                    throw new IOException("无法建立恢复前安全副本目录");
                }
                File target = uniqueSafetyFile(safetyDirectory);
                progress.onProgress("safety", "正在建立恢复前安全副本", 0, -1, 0, 1);
                safetyCopy = createSnapshot(app, current.items, target,
                        BackupArchive.Limits.defaults(), cancellation, progress).file;
            }
            RestorePlan plan = planRestore(app, current.items, decoded.items, current.folders, decoded.folders, incoming,
                    transactionId, learningRemapper, cancellation);
            if (plan.combined.size() > LibraryIndex.MAX_ITEMS) {
                throw new IOException("恢复后资料数量超过上限");
            }
            LibraryStore.State beforeInstall = LibraryStore.load(app);
            if (!beforeInstall.allowSave
                    || !originalFingerprint.equals(beforeInstall.revision)) {
                throw new IOException("资料库在恢复期间发生变化，请重试");
            }

            ArrayList<File> installed = new ArrayList<>();
            ArrayList<File> createdDirectories = new ArrayList<>();
            boolean indexCommitted = false;
            try {
                int completed = 0;
                for (Move move : plan.moves) {
                    checkCancelled(cancellation);
                    progress.onProgress("install", move.label, completed, plan.moves.size(),
                            completed, plan.moves.size());
                    install(move.source, move.target, installed, createdDirectories,
                            cancellation);
                    completed++;
                }
                checkCancelled(cancellation);
                LibraryStore.State beforeSave = LibraryStore.load(app);
                if (!beforeSave.allowSave
                        || !originalFingerprint.equals(beforeSave.revision)) {
                    throw new IOException("资料库在恢复期间发生变化，请重试");
                }
                LibraryStore.saveIfRevision(app, beforeSave.revision, plan.combined, plan.folders);
                indexCommitted = true;
                for (LibraryItem restoredItem : plan.combined) {
                    if (restoredItem.isNote()) NoteStore.revive(app, restoredItem.id);
                    else {
                        ReaderStore.revive(app.getFilesDir(), restoredItem.id);
                        InkStore.reviveDocument(restoredItem.id);
                    }
                }
                progress.onProgress("complete", "恢复完成", archive.manifest.totalBytes,
                        archive.manifest.totalBytes, plan.moves.size(), plan.moves.size());
                String warning = plan.renamedLearning == 0 ? ""
                        : plan.renamedLearning + " 个无归属学习资源已放入独立恢复目录";
                return new RestoreResult(decoded.items.size(), archive.manifest.entries().size(),
                        plan.conflicts, archive.manifest.totalBytes, warning, safetyCopy);
            } finally {
                if (!indexCommitted) rollback(installed, createdDirectories);
            }
        }
    }

    private static final class ItemFiles {
        String content;
        String reader;
        String attachment;
        final ArrayList<String> annotations = new ArrayList<>();
        final ArrayList<String> notes = new ArrayList<>();
        final ArrayList<String> recognition = new ArrayList<>();
    }

    private static final class Incoming {
        final Map<String, ItemFiles> files = new HashMap<>();
        final ArrayList<BackupArchive.EntryRecord> learning = new ArrayList<>();
    }

    private static Incoming validateIncoming(List<LibraryItem> items,
                                             BackupArchive.Manifest manifest)
            throws IOException {
        Incoming incoming = new Incoming();
        HashMap<String, LibraryItem> byId = new HashMap<>();
        for (LibraryItem item : items) {
            item.validate();
            if (byId.put(item.id, item) != null) throw new IOException("备份资料编号重复");
            incoming.files.put(item.id, new ItemFiles());
        }
        for (BackupArchive.EntryRecord entry : manifest.entries()) {
            String[] part = entry.path.split("/", -1);
            if (part.length >= 3 && "documents".equals(part[0])) {
                LibraryItem item = byId.get(part[1]);
                ItemFiles files = incoming.files.get(part[1]);
                if (item == null || files == null || !part[1].equals(entry.ownerId)) {
                    throw new IOException("备份文档归属无效：" + entry.path);
                }
                if (part.length == 3 && "content.pdf".equals(part[2])
                        && "document".equals(entry.kind)) {
                    if (files.content != null) throw new IOException("备份 PDF 重复");
                    files.content = entry.path;
                } else if (part.length == 4 && "annotations".equals(part[2])
                        && part[3].matches("[0-9]+\\.ink(?:\\.clear)?")
                        && "annotation".equals(entry.kind)) {
                    files.annotations.add(entry.path);
                } else if (part.length == 3 && "metadata.reader".equals(part[2])
                        && "reader".equals(entry.kind)) {
                    if (files.reader != null) throw new IOException("阅读记录重复");
                    files.reader = entry.path;
                } else if (part.length == 4 && "recognition".equals(part[2])
                        && part[3].matches("page-[0-9]+\\.txt")
                        && "recognition".equals(entry.kind) && !item.isNote()) {
                    files.recognition.add(entry.path);
                } else if (part.length == 4 && "original".equals(part[2])
                        && "original".equals(entry.kind)) {
                    if (files.attachment != null) throw new IOException("备份原文件重复");
                    files.attachment = entry.path;
                } else throw new IOException("未知的文档备份条目：" + entry.path);
            } else if (part.length >= 3 && "notes".equals(part[0])) {
                LibraryItem item = byId.get(part[1]);
                ItemFiles files = incoming.files.get(part[1]);
                if (item == null || files == null || !part[1].equals(entry.ownerId)
                        || !"note-file".equals(entry.kind)) {
                    throw new IOException("备份笔记归属无效：" + entry.path);
                }
                files.notes.add(entry.path);
            } else if (part.length >= 2 && "learning".equals(part[0])
                    && "learning".equals(entry.kind)) {
                incoming.learning.add(entry);
            } else throw new IOException("未知的备份条目：" + entry.path);
        }
        for (LibraryItem item : items) {
            ItemFiles files = incoming.files.get(item.id);
            if (item.isNote()) {
                if (files.content != null || !files.annotations.isEmpty()
                        || files.reader != null || !files.recognition.isEmpty()
                        || !containsSuffix(files.notes, "/document.qnote")) {
                    throw new IOException("笔记原档不完整：" + item.name);
                }
            } else if (files.content == null || !files.notes.isEmpty()) {
                throw new IOException("PDF 原档不完整：" + item.name);
            }
            if (item.attachment.isEmpty() != (files.attachment == null)) {
                throw new IOException("原文件附件记录不一致：" + item.name);
            }
        }
        return incoming;
    }

    private static final class Move {
        final File source, target;
        final String label;
        Move(File source, File target, String label) {
            this.source = source; this.target = target; this.label = label;
        }
    }

    private static final class RestorePlan {
        final ArrayList<LibraryItem> combined = new ArrayList<>();
        final ArrayList<LibraryFolder> folders = new ArrayList<>();
        final ArrayList<Move> moves = new ArrayList<>();
        int conflicts, renamedLearning;
    }

    private static RestorePlan planRestore(Context app, List<LibraryItem> current,
                                           List<LibraryItem> restored, List<LibraryFolder> currentFolders,
                                           List<LibraryFolder> restoredFolders, Incoming incoming,
                                           String transactionId,
                                           LearningRemapper learningRemapper,
                                           Cancellation cancellation) throws IOException {
        RestorePlan plan = new RestorePlan();
        Map<String, String> folderIds = mergeFolders(plan.folders, currentFolders, restoredFolders);
        HashSet<String> occupied = new HashSet<>();
        for (LibraryItem item : current) {
            LibraryItem copy = item.copy(); copy.validate();
            plan.combined.add(copy); occupied.add(copy.id);
        }
        HashMap<String, String> ids = new HashMap<>();
        for (LibraryItem item : restored) {
            boolean conflict = occupied.contains(item.id) || physicalItemExists(app, item.id);
            if (!conflict) {
                ItemFiles files = incoming.files.get(item.id);
                conflict = learningCollision(app, item.id, item.id, incoming.learning)
                        || files != null && attachmentTargetExists(app, item.id, files.attachment);
            }
            String id = conflict ? unusedId(app, occupied) : item.id;
            if (conflict) plan.conflicts++;
            occupied.add(id); ids.put(item.id, id);
        }

        boolean remapped = false;
        for (Map.Entry<String, String> mapping : ids.entrySet()) {
            if (!mapping.getKey().equals(mapping.getValue())) { remapped = true; break; }
        }
        if (remapped && !incoming.learning.isEmpty()) {
            if (learningRemapper == null) {
                throw new IOException("备份含有关联学习资料；编号冲突时需要学习模块安全重映射来源链接");
            }
            File stagedLearning = new File(app.getFilesDir(), ".restore/" + transactionId
                    + "/validated/learning");
            learningRemapper.remap(stagedLearning,
                    Collections.unmodifiableMap(new HashMap<>(ids)), cancellation);
            checkCancelled(cancellation);
        }
        // Note TEXT.image stores a local source URI. Rewrite references while the restored
        // note is still staged, so KEEP_BOTH cannot point a note at the pre-existing document.
        if(remapped)for(LibraryItem note:restored)if(note.isNote()){
            ItemFiles files=incoming.files.get(note.id);
            if(files==null)continue;
            for(String path:files.notes)if(path.endsWith("/document.qnote")){
                checkCancelled(cancellation);
                File staged=new File(app.getFilesDir(),".restore/"+transactionId+"/validated/"+path);
                NoteDocument document;
                try(FileInputStream input=new FileInputStream(staged)){document=NoteDocument.read(input);}
                boolean changed=false;int originalPage=document.currentPage();
                for(int notePage=0;notePage<document.pageCount();notePage++){
                document.selectPage(notePage);
                for(NoteDocument.Item item:new ArrayList<>(document.items()))if(item.type==NoteDocument.TEXT&&!item.image.isEmpty()){
                    ReaderSourceLink link;
                    try{link=ReaderSourceLink.parse(item.image);}catch(IllegalArgumentException bad){throw new IOException("备份笔记含损坏的来源链接",bad);}
                    String mapped=ids.get(link.documentId);
                    if(mapped!=null&&!mapped.equals(link.documentId)){
                        ReaderSourceLink updated=new ReaderSourceLink(mapped,link.documentName,link.page,link.x,link.y,link.quote);
                        document.replace(new NoteDocument.Item(item.id,item.type,item.color,item.width,item.points,updated.toUri(),item.text));changed=true;
                    }
                }
                }
                document.selectPage(originalPage);
                if(changed)try(FileOutputStream output=new FileOutputStream(staged)){NoteDocument.write(output,document);output.getFD().sync();}
            }
        }

        File filesRoot = app.getFilesDir();
        File notesRoot = new File(filesRoot, "notes");
        File attachmentRoot = LibraryStore.attachmentsDirectory(app);
        HashSet<String> targets = new HashSet<>();
        for (LibraryItem source : restored) {
            String id = ids.get(source.id);
            LibraryItem item = source.copy();
            item.id = id;
            if (!source.folderId.isEmpty()) {
                item.folderId = folderIds.get(source.folderId);
                if (item.folderId == null) throw new IOException("恢复资料引用不存在的文件夹");
                for (LibraryFolder folder : plan.folders) if (folder.id.equals(item.folderId)) {
                    item.folder = folder.name; break;
                }
            }
            if (!id.equals(source.id)) item.name = conflictName(item.name);
            ItemFiles files = incoming.files.get(source.id);
            if (item.isNote()) {
                for (String path : files.notes) {
                    String relative = path.substring(("notes/" + source.id + "/").length());
                    addMove(plan, targets, new File(filesRoot, ".restore/" + transactionId
                                    + "/validated/" + path),
                            contained(new File(notesRoot, id), relative), item.name);
                }
            } else {
                addMove(plan, targets, new File(filesRoot, ".restore/" + transactionId
                                + "/validated/" + files.content),
                        new File(filesRoot, id + ".pdf"), item.name);
                for (String path : files.annotations) {
                    String filename = path.substring(path.lastIndexOf('/') + 1);
                    String page = filename.substring(0, filename.indexOf(".ink"));
                    String suffix = filename.endsWith(".clear") ? ".ink.clear" : ".ink";
                    addMove(plan, targets, new File(filesRoot, ".restore/" + transactionId
                                    + "/validated/" + path),
                            new File(filesRoot, id + "-" + page + suffix), item.name + " 批注");
                }
                if (files.reader != null) addMove(plan, targets,
                        new File(filesRoot, ".restore/" + transactionId + "/validated/" + files.reader),
                        ReaderStore.file(filesRoot, id), item.name + " 阅读记录");
                for (String path : files.recognition) {
                    String filename = path.substring(path.lastIndexOf('/') + 1);
                    addMove(plan, targets, new File(filesRoot, ".restore/" + transactionId
                                    + "/validated/" + path),
                            new File(new File(new File(filesRoot, "recognition"), id), filename),
                            item.name + " OCR");
                }
            }
            if (files.attachment != null) {
                String filename = files.attachment.substring(files.attachment.lastIndexOf('/') + 1);
                String relative = id + "/" + filename;
                item.attachment = relative;
                addMove(plan, targets, new File(filesRoot, ".restore/" + transactionId
                                + "/validated/" + files.attachment),
                        contained(attachmentRoot, relative), item.name + " 原文件");
            } else item.attachment = "";
            item.validate();
            plan.combined.add(item);
        }

        File learningRoot = new File(filesRoot, "learning");
        for (BackupArchive.EntryRecord entry : incoming.learning) {
            String relative = entry.path.substring("learning/".length());
            String mapped = mapFirstSegment(relative, ids);
            File target = contained(learningRoot, mapped);
            if (target.exists() || targets.contains(fold(target)))
                throw new IOException("学习资源目标冲突：" + relative);
            addMove(plan, targets, new File(filesRoot, ".restore/" + transactionId
                            + "/validated/" + entry.path), target, "学习资源");
        }
        return plan;
    }

    /** KEEP_BOTH retains the complete incoming tree, including empty folders. */
    private static Map<String, String> mergeFolders(List<LibraryFolder> combined,
                                                   List<LibraryFolder> current,
                                                   List<LibraryFolder> incoming) throws IOException {
        Set<String> occupied = new HashSet<>();
        for (LibraryFolder folder : current) { combined.add(folder.copy()); occupied.add(folder.id); }
        Map<String, String> ids = new HashMap<>();
        for (LibraryFolder folder : incoming) {
            String id = folder.id;
            while (occupied.contains(id)) id = UUID.randomUUID().toString();
            occupied.add(id); ids.put(folder.id, id);
        }
        for (LibraryFolder source : incoming) {
            String parent = source.parentId.isEmpty() ? "" : ids.get(source.parentId);
            if (parent == null) throw new IOException("恢复文件夹缺少父目录");
            String name = source.name;
            for (int suffix = 1; folderNameExists(combined, parent, name); suffix++) {
                String tail = suffix == 1 ? "（恢复副本）" : "（恢复副本 " + suffix + "）";
                int end = Math.min(source.name.length(), 100 - tail.length());
                if (end > 0 && Character.isHighSurrogate(source.name.charAt(end - 1))) end--;
                name = source.name.substring(0, end) + tail;
            }
            combined.add(new LibraryFolder(ids.get(source.id), parent, name));
        }
        if (combined.size() > LibraryIndex.MAX_ITEMS) throw new IOException("恢复后文件夹数量超过上限");
        LibraryFolder.validateTree(combined);
        return ids;
    }

    private static boolean folderNameExists(List<LibraryFolder> folders, String parent, String name) {
        for (LibraryFolder folder : folders)
            if (folder.parentId.equals(parent) && folder.name.equalsIgnoreCase(name)) return true;
        return false;
    }

    private static void addMove(RestorePlan plan, Set<String> targets, File source, File target,
                                String label) throws IOException {
        if (!source.isFile()) throw new IOException("恢复暂存文件缺失：" + source.getName());
        if (target.exists()) throw new IOException("恢复目标已存在，拒绝覆盖：" + target.getName());
        if (!targets.add(fold(target))) throw new IOException("恢复目标重复：" + target.getName());
        plan.moves.add(new Move(source, target, label));
    }

    private static void install(File source, File target, List<File> installed,
                                List<File> createdDirectories, Cancellation cancellation)
            throws IOException {
        checkCancelled(cancellation);
        ensureParents(target.getParentFile(), createdDirectories);
        if (target.exists()) throw new IOException("恢复目标已存在，拒绝覆盖：" + target.getName());
        if (source.renameTo(target)) {
            installed.add(target); return;
        }
        File temporary = new File(target.getParentFile(), "." + target.getName()
                + ".restore-" + UUID.randomUUID().toString());
        if (!temporary.createNewFile()) throw new IOException("无法建立恢复临时文件");
        boolean complete = false;
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[BUFFER_SIZE]; int count;
            while ((count = input.read(buffer)) != -1) {
                checkCancelled(cancellation); output.write(buffer, 0, count);
            }
            output.getFD().sync(); complete = true;
        } finally {
            if (!complete) temporary.delete();
        }
        if (target.exists() || !temporary.renameTo(target)) {
            temporary.delete(); throw new IOException("无法安装恢复文件：" + target.getName());
        }
        installed.add(target);
    }

    private static void ensureParents(File directory, List<File> created) throws IOException {
        if (directory.isDirectory()) return;
        ArrayList<File> missing = new ArrayList<>();
        File cursor = directory;
        while (cursor != null && !cursor.exists()) { missing.add(cursor); cursor = cursor.getParentFile(); }
        if (cursor == null || !cursor.isDirectory()) throw new IOException("恢复目标目录无效");
        for (int index = missing.size() - 1; index >= 0; index--) {
            File next = missing.get(index);
            if (!next.mkdir() && !next.isDirectory()) throw new IOException("无法建立恢复目录");
            created.add(next);
        }
    }

    private static void rollback(List<File> installed, List<File> createdDirectories) {
        for (int index = installed.size() - 1; index >= 0; index--) installed.get(index).delete();
        for (int index = createdDirectories.size() - 1; index >= 0; index--) {
            createdDirectories.get(index).delete();
        }
    }

    private static void addInkSources(File root, LibraryItem item,
                                      List<BackupArchive.EntrySource> result) throws IOException {
        File[] files = root.listFiles();
        if (files == null) throw new IOException("无法读取 PDF 批注目录");
        TreeMap<String, File> logical = new TreeMap<>();
        HashMap<String, Integer> priority = new HashMap<>();
        for (File file : files) {
            if (!file.isFile()) continue;
            Matcher matcher = INK_NAME.matcher(file.getName());
            if (!matcher.matches() || !item.id.equalsIgnoreCase(matcher.group(1))) continue;
            String suffix = matcher.group(4);
            if ("new".equals(suffix)) continue;
            String page = matcher.group(2) + (matcher.group(3) == null ? "" : ".clear");
            int rank = "bak".equals(suffix) ? 2 : 1;
            Integer previous = priority.get(page);
            if (previous == null || previous < rank) {
                priority.put(page, rank); logical.put(page, file);
            }
        }
        for (Map.Entry<String, File> ink : logical.entrySet()) {
            result.add(BackupArchive.EntrySource.fromFile("documents/" + item.id
                    + "/annotations/" + ink.getKey().replace(".clear", "") + ".ink"
                    + (ink.getKey().endsWith(".clear") ? ".clear" : ""), "annotation", item.id,
                    ink.getValue()));
        }
    }

    private static void addReaderSource(File root, LibraryItem item,
                                        List<BackupArchive.EntrySource> result) throws IOException {
        File file = ReaderStore.file(root, item.id);
        File backup = new File(file + ".bak");
        File chosen = backup.isFile() ? backup : file;
        if (chosen.isFile()) result.add(BackupArchive.EntrySource.fromFile(
                "documents/" + item.id + "/metadata.reader", "reader", item.id, chosen));
    }

    private static TreeMap<String, File> collectAtomicTree(File root) throws IOException {
        requireDirectory(root, "找不到笔记原档目录");
        TreeMap<String, File> result = new TreeMap<>();
        HashMap<String, Integer> priority = new HashMap<>();
        collectTree(root, root, 0, new int[]{0}, new TreeVisitor() {
            @Override public void file(String relative, File file) throws IOException {
                if (relative.endsWith(".new")) return;
                boolean backup = relative.endsWith(".bak");
                String logical = backup ? relative.substring(0, relative.length() - 4) : relative;
                int rank = backup ? 2 : 1;
                Integer previous = priority.get(logical);
                if (previous == null || previous < rank) {
                    priority.put(logical, rank); result.put(logical, file);
                }
            }
        });
        return result;
    }

    private static TreeMap<String, File> collectRawTree(File root) throws IOException {
        requireDirectory(root, "无法读取学习资源目录");
        TreeMap<String, File> result = new TreeMap<>();
        collectTree(root, root, 0, new int[]{0}, new TreeVisitor() {
            @Override public void file(String relative, File file) { result.put(relative, file); }
        });
        return result;
    }

    private interface TreeVisitor { void file(String relative, File file) throws IOException; }

    private static void collectTree(File root, File current, int depth, int[] count,
                                    TreeVisitor visitor) throws IOException {
        if (depth > MAX_TREE_DEPTH) throw new IOException("备份目录层级过深");
        File canonicalRoot = root.getCanonicalFile();
        File canonicalCurrent = current.getCanonicalFile();
        if (!canonicalCurrent.getPath().equals(canonicalRoot.getPath())
                && !canonicalCurrent.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
            throw new IOException("备份文件越出资料目录");
        }
        File[] children = current.listFiles();
        if (children == null) throw new IOException("无法读取备份目录：" + current.getName());
        Arrays.sort(children, new Comparator<File>() {
            @Override public int compare(File left, File right) {
                return left.getName().compareTo(right.getName());
            }
        });
        for (File child : children) {
            File canonical = child.getCanonicalFile();
            if (!canonical.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
                throw new IOException("备份文件越出资料目录");
            }
            if (child.isDirectory()) collectTree(root, child, depth + 1, count, visitor);
            else if (child.isFile()) {
                if (++count[0] > MAX_TREE_FILES) throw new IOException("备份文件数量过多");
                String relative = canonical.getPath().substring(
                        canonicalRoot.getPath().length() + 1).replace(File.separatorChar, '/');
                if (relative.endsWith("/")) throw new IOException("无效的备份文件路径");
                visitor.file(relative, child);
            } else throw new IOException("备份目录包含不支持的文件");
        }
    }

    private static boolean physicalItemExists(Context context, String id) {
        File files = context.getFilesDir();
        if (new File(files, id + ".pdf").exists()
                || new File(files, id + ".reader").exists()
                || new File(files, id + ".reader.bak").exists()
                || new File(files, id + ".reader.new").exists()
                || new File(new File(files, "notes"), id).exists()
                || new File(new File(files, "recognition"), id).exists()
                || new File(LibraryStore.attachmentsDirectory(context), id).exists()) return true;
        File[] root = files.listFiles();
        if (root != null) for (File file : root) {
            String name = file.getName();
            if (name.startsWith(id + "-")
                    && name.matches(Pattern.quote(id) + "-[0-9]+\\.ink(?:\\.clear)?(?:\\.(?:bak|new))?")) {
                return true;
            }
        }
        return false;
    }

    private static boolean attachmentTargetExists(Context context, String id, String path) {
        if (path == null) return false;
        String filename = path.substring(path.lastIndexOf('/') + 1);
        return new File(new File(LibraryStore.attachmentsDirectory(context), id), filename).exists();
    }

    private static boolean learningCollision(Context context, String oldId, String newId,
                                             List<BackupArchive.EntryRecord> learning)
            throws IOException {
        File root = new File(context.getFilesDir(), "learning");
        for (BackupArchive.EntryRecord entry : learning) {
            if (!oldId.equals(entry.ownerId)) continue;
            String relative = entry.path.substring("learning/".length());
            String mapped = replaceFirstSegment(relative, oldId, newId);
            if (contained(root, mapped).exists()) return true;
        }
        return false;
    }

    private static String unusedId(Context context, Set<String> occupied) {
        String id;
        do { id = UUID.randomUUID().toString(); }
        while (occupied.contains(id) || physicalItemExists(context, id));
        return id;
    }

    private static String conflictName(String original) {
        final String suffix = "（恢复副本）";
        String value = original == null ? "恢复的资料" : original.trim();
        int limit = 150 - suffix.length();
        if (value.length() > limit) {
            value = value.substring(0, limit);
            if (!value.isEmpty() && Character.isHighSurrogate(value.charAt(value.length() - 1))) {
                value = value.substring(0, value.length() - 1);
            }
        }
        return value + suffix;
    }

    /** Stable logical fingerprint: timestamps and ZIP encoding details are deliberately excluded. */
    private static String stableFingerprint(Collection<LibraryItem> items,
                                            Collection<LibraryFolder> folders,
                                            BackupArchive.Manifest manifest) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
        digestString(digest, BackupArchive.FORMAT);
        digestLong(digest, BackupArchive.FORMAT_VERSION);
        digestString(digest, LibraryIndex.encodeV2(items, folders, 0));
        ArrayList<BackupArchive.EntryRecord> records = new ArrayList<>(manifest.entries());
        Collections.sort(records, new Comparator<BackupArchive.EntryRecord>() {
            @Override public int compare(BackupArchive.EntryRecord left,
                                         BackupArchive.EntryRecord right) {
                int path = left.path.compareTo(right.path);
                if (path != 0) return path;
                int kind = left.kind.compareTo(right.kind);
                return kind != 0 ? kind : left.ownerId.compareTo(right.ownerId);
            }
        });
        digestLong(digest, records.size());
        for (BackupArchive.EntryRecord record : records) {
            digestString(digest, record.path);
            digestString(digest, record.kind);
            digestString(digest, record.ownerId);
            digestLong(digest, record.size);
            digestString(digest, record.sha256);
        }
        return hex(digest.digest());
    }

    private static void digestString(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digestLong(digest, bytes.length);
        digest.update(bytes);
    }

    private static void digestLong(MessageDigest digest, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) digest.update((byte) (value >>> shift));
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 255;
            result[index * 2] = digits[value >>> 4];
            result[index * 2 + 1] = digits[value & 15];
        }
        return new String(result);
    }

    private static File uniqueSafetyFile(File directory) throws IOException {
        long now = System.currentTimeMillis();
        for (int suffix = 0; suffix < 10_000; suffix++) {
            File candidate = new File(directory, "safety-" + now
                    + (suffix == 0 ? "" : "-" + suffix) + ".qyb");
            if (!candidate.exists()) return candidate;
        }
        throw new IOException("无法分配恢复前安全副本文件名");
    }

    private static boolean containsSuffix(List<String> values, String suffix) {
        for (String value : values) if (value.endsWith(suffix)) return true;
        return false;
    }

    private static String firstSegment(String value) {
        int slash = value.indexOf('/'); return slash < 0 ? value : value.substring(0, slash);
    }

    private static String mapFirstSegment(String relative, Map<String, String> ids) {
        String first = firstSegment(relative);
        String mapped = ids.get(first);
        return mapped == null ? relative : replaceFirstSegment(relative, first, mapped);
    }

    private static String replaceFirstSegment(String relative, String expected, String replacement) {
        String first = firstSegment(relative);
        if (!first.equals(expected)) return relative;
        return replacement + relative.substring(first.length());
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "bin" : name.substring(dot + 1);
        return SAFE_EXTENSION.matcher(extension).matches()
                ? extension.toLowerCase(Locale.ROOT) : "bin";
    }

    private static File contained(File root, String relative) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File target = new File(canonicalRoot,
                relative.replace('/', File.separatorChar)).getCanonicalFile();
        if (!target.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
            throw new IOException("资料路径越界");
        }
        return target;
    }

    private static String fold(File file) throws IOException {
        return file.getCanonicalPath().toLowerCase(Locale.ROOT);
    }

    private static void requireRegularFile(File file, String message) throws IOException {
        if (!file.isFile()) throw new IOException(message);
    }

    private static void requireDirectory(File file, String message) throws IOException {
        if (!file.isDirectory()) throw new IOException(message);
    }

    private static void copy(InputStream input, OutputStream output, long total, String phase,
                             String detail, Progress progress, Cancellation cancellation)
            throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE]; long completed = 0; int count;
        while ((count = input.read(buffer)) != -1) {
            checkCancelled(cancellation); output.write(buffer, 0, count); completed += count;
            progress.onProgress(phase, detail, completed, total, 0, 1);
        }
    }

    private static BackupArchive.Progress archiveProgress(final Progress progress) {
        return new BackupArchive.Progress() {
            @Override public void onProgress(String phase, String path, long completedBytes,
                                             long totalBytes, int completedEntries,
                                             int totalEntries) {
                progress.onProgress(phase, path, completedBytes, totalBytes, completedEntries,
                        totalEntries);
            }
        };
    }

    private static BackupArchive.Cancellation archiveCancellation(
            final Cancellation cancellation) {
        return new BackupArchive.Cancellation() {
            @Override public boolean isCancelled() { return cancellation.isCancelled(); }
        };
    }

    private static void checkCancelled(Cancellation cancellation)
            throws BackupArchive.CancelledException {
        if (cancellation.isCancelled()) throw new BackupArchive.CancelledException();
    }

    private static String jsonQuote(String value) {
        StringBuilder output = new StringBuilder(value.length() + 32).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"': output.append("\\\""); break;
                case '\\': output.append("\\\\"); break;
                case '\b': output.append("\\b"); break;
                case '\f': output.append("\\f"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                default:
                    if (character < 0x20) output.append(String.format(Locale.ROOT,
                            "\\u%04x", (int) character));
                    else output.append(character);
            }
        }
        return output.append('"').toString();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}
