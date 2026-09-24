package cn.qingyue.pdf;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Atomic Android storage, 1.7 migration, and explicit local-library reconstruction. */
public final class LibraryStore {
    private static final Object WRITE_LOCK = new Object();
    private static volatile String recoveryWarning="";
    private static volatile Thread recoveryThread;
    static Object transactionLock(){return WRITE_LOCK;}
    static void recoveryGate(String warning,Thread owner){recoveryThread=owner;recoveryWarning=warning==null?"":warning;}
    private static String recoveryWarning(){return Thread.currentThread()==recoveryThread?"":recoveryWarning;}
    private static final String LEGACY_PREFERENCES = "MainActivity";
    private static final String LEGACY_KEY = "library";
    private static final int MAX_RECOVERY_FILES = 64;
    private static final long MAX_RECOVERY_BYTES = 64L * 1024 * 1024;

    public static final class State {
        public final ArrayList<LibraryItem> items;
        public final ArrayList<LibraryFolder> folders;
        public final ArrayList<LibraryIndex.Damage> damaged;
        public final boolean allowSave;
        public final boolean migrated;
        public final String warning;
        /** Logical revision of the validated item set, used to reject stale full-list writes. */
        public final String revision;

        State(ArrayList<LibraryItem> items, ArrayList<LibraryIndex.Damage> damaged,
                boolean allowSave, boolean migrated, String warning) {
            this(items, new ArrayList<>(), damaged, allowSave, migrated, warning);
        }

        State(ArrayList<LibraryItem> items, ArrayList<LibraryFolder> folders,
                ArrayList<LibraryIndex.Damage> damaged, boolean allowSave, boolean migrated, String warning) {
            this.items = items;
            this.folders = folders;
            this.damaged = damaged;
            this.allowSave = allowSave;
            this.migrated = migrated;
            this.warning = warning == null ? "" : warning;
            this.revision = logicalRevision(items, folders);
        }
    }

    public interface Mutation {
        void apply(ArrayList<LibraryItem> items) throws IOException;
    }

    public static final class StaleIndexException extends IOException {
        StaleIndexException() { super("资料库已在后台更新，请重试本次操作"); }
    }

    private LibraryStore() { }

    public static File directory(Context context) {
        return new File(context.getFilesDir(), "library");
    }

    public static File indexFile(Context context) {
        return new File(directory(context), "index-v2.json");
    }

    public static File attachmentsDirectory(Context context) {
        return new File(directory(context), "attachments");
    }

    /** Loads v2 when present; otherwise performs a non-destructive one-time 1.7 migration. */
    public static State load(Context context) {
        String blocked=recoveryWarning();
        if(!blocked.isEmpty())return new State(new ArrayList<>(),new ArrayList<>(),false,false,blocked);
        // AtomicFile.openRead() may restore its .bak file before opening the stream.  That is a
        // filesystem mutation, so readers must share the same lock as startWrite()/finishWrite()
        // or a foreground reload can race a background import/restore and roll the index back.
        synchronized (WRITE_LOCK) {
            return loadLocked(context);
        }
    }

    private static State loadLocked(Context context) {
        Context app = context.getApplicationContext();
        File index = indexFile(app);
        AtomicFile atomic = new AtomicFile(index);
        if (index.isFile() || new File(index.getPath() + ".bak").isFile()) {
            byte[] raw = null;
            try {
                String json;
                try (InputStream input = atomic.openRead()) { raw = readBounded(input); }
                json = decodeUtf8(raw);
                LibraryIndex.Result result = LibraryIndex.decodeV2(json);
                boolean preserved = preserveIfNeeded(app, result);
                if (!preserved) {
                    return new State(result.items, result.damaged, false, false,
                            "损坏记录暂时无法写入恢复区，已禁止覆盖索引");
                }
                return state(result, false, result.allowSave ? damageWarning(result) : "资料库索引损坏，已禁止自动覆盖");
            } catch (IOException | RuntimeException failure) {
                if (raw != null) preserveRawIndex(app, raw);
                ArrayList<LibraryIndex.Damage> damaged = new ArrayList<>();
                damaged.add(new LibraryIndex.Damage(-1, "", "无法读取资料库索引：" + message(failure)));
                return new State(new ArrayList<>(), damaged, false, false, "资料库索引无法读取，已禁止自动覆盖");
            }
        }

        SharedPreferences preferences = app.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE);
        String legacy = preferences.getString(LEGACY_KEY, "[]");
        long now = System.currentTimeMillis();
        LibraryIndex.Result result = LibraryIndex.migrateLegacy(legacy, now);
        boolean preserved = preserveIfNeeded(app, result);
        if (!preserved) {
            return new State(result.items, result.damaged, false, false,
                    "损坏的旧记录暂时无法写入恢复区，旧索引保持不变");
        }
        if (!result.allowSave) {
            return state(result, false, "旧版资料库索引损坏，原文已保留且不会被覆盖");
        }
        Map<String, Long> migratedInkTimes = inkModifiedTimes(app.getFilesDir());
        for (LibraryItem item : result.items) enrichMigratedTimes(app, item, now,
                migratedInkTimes.get(item.id.toLowerCase(Locale.ROOT)));
        try {
            save(app, result.items, result.folders);
            return state(result, true, damageWarning(result));
        } catch (IOException | RuntimeException failure) {
            return new State(result.items, result.damaged, false, false,
                    "旧资料仍保持不变，但新版索引建立失败：" + message(failure));
        }
    }

    public static void save(Context context, Collection<LibraryItem> items) throws IOException {
        synchronized (WRITE_LOCK) {
            save(context, items, loadLocked(context).folders);
        }
    }

    public static void save(Context context, Collection<LibraryItem> items,
                            Collection<LibraryFolder> folders) throws IOException {
        if(!recoveryWarning().isEmpty())throw new IOException(recoveryWarning());
        String json;
        try {
            json = LibraryIndex.encodeV2(items, folders, System.currentTimeMillis());
        } catch (IllegalArgumentException problem) {
            throw new IOException(problem.getMessage(), problem);
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LibraryIndex.MAX_JSON_CHARS) throw new IOException("资料库索引超过大小限制");
        synchronized (WRITE_LOCK) {
            File directory = directory(context);
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建资料库目录");
            AtomicFile atomic = new AtomicFile(indexFile(context));
            FileOutputStream output = null;
            try {
                output = atomic.startWrite();
                output.write(bytes);
                output.flush();
                atomic.finishWrite(output);
            } catch (IOException | RuntimeException failure) {
                if (output != null) atomic.failWrite(output);
                if (failure instanceof IOException) throw (IOException) failure;
                throw new IOException("无法保存资料库索引", failure);
            }
        }
    }

    /**
     * Atomically replaces the index only when the caller still owns the revision it loaded.
     * This prevents a paused Activity from overwriting an import, restore, or notes update.
     *
     * @return the revision of the newly committed item set
     */
    public static String saveIfRevision(Context context, String expectedRevision,
                                        Collection<LibraryItem> items) throws IOException {
        return replaceIfRevision(context, expectedRevision, items, null, true);
    }

    public static String saveIfRevision(Context context, String expectedRevision,
                                        Collection<LibraryItem> items,
                                        Collection<LibraryFolder> folders) throws IOException {
        return replaceIfRevision(context, expectedRevision, items, folders, true);
    }

    private static String replaceIfRevision(Context context, String expectedRevision,
                                            Collection<LibraryItem> items,
                                            Collection<LibraryFolder> folders,
                                            boolean requireWritable) throws IOException {
        if (expectedRevision == null) throw new StaleIndexException();
        synchronized (WRITE_LOCK) {
            State current = load(context);
            if (requireWritable && !current.allowSave) {
                throw new IOException(current.warning.isEmpty()
                        ? "资料库索引当前不可写" : current.warning);
            }
            if (!expectedRevision.equals(current.revision)) throw new StaleIndexException();
            Collection<LibraryFolder> nextFolders = folders == null ? current.folders : folders;
            save(context, items, nextFolders);
            return loadLocked(context).revision;
        }
    }

    /** Runs an incremental read-modify-write under the same index lock. */
    public static State mutate(Context context, Mutation mutation) throws IOException {
        if (mutation == null) throw new NullPointerException("mutation");
        synchronized (WRITE_LOCK) {
            State current = load(context);
            if (!current.allowSave) {
                throw new IOException(current.warning.isEmpty()
                        ? "资料库索引当前不可写" : current.warning);
            }
            ArrayList<LibraryItem> next = new ArrayList<>();
            for (LibraryItem item : current.items) next.add(item.copy());
            mutation.apply(next);
            save(context, next);
            return loadLocked(context);
        }
    }

    /**
     * Deletes a folder with no active documents or child folders. Trashed documents keep all
     * payloads and their trash timestamps; their restore destination moves to the parent (or
     * unfiled at the root). The destination and folder removal share one atomic index commit.
     */
    public static State deleteEmptyFolder(Context context, String folderId,
                                          String expectedRevision) throws IOException {
        if (!LibraryItem.isUuid(folderId)) throw new IOException("无效的文件夹编号");
        if (expectedRevision == null) throw new StaleIndexException();
        synchronized (WRITE_LOCK) {
            State current = loadLocked(context);
            // An isolated unreadable item could still belong to this folder. Do not decide
            // that it is empty from a partial index, even if that damage was preserved.
            if (!current.allowSave || !current.damaged.isEmpty()) {
                throw new IOException("资料库索引含有损坏记录，请先修复后再删除文件夹");
            }
            if (!expectedRevision.equals(current.revision)) throw new StaleIndexException();
            LibraryFolder target = null;
            for (LibraryFolder folder : current.folders) if (folderId.equals(folder.id)) target = folder;
            if (target == null) throw new IOException("文件夹已不存在，请刷新资料库");
            for (LibraryFolder folder : current.folders) {
                if (folderId.equals(folder.parentId)) throw new IOException("请先移走子文件夹，再删除空文件夹");
            }
            for (LibraryItem item : current.items) {
                if (folderId.equals(item.folderId) && !item.isTrashed()) {
                    throw new IOException("文件夹仍有资料，请先移动或移入最近删除");
                }
            }
            String parentName = "";
            ArrayList<LibraryFolder> nextFolders = new ArrayList<>();
            for (LibraryFolder folder : current.folders) {
                if (target.parentId.equals(folder.id)) parentName = folder.name;
                if (!folderId.equals(folder.id)) nextFolders.add(folder.copy());
            }
            ArrayList<LibraryItem> nextItems = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (LibraryItem item : current.items) {
                LibraryItem copy = item.copy();
                if (folderId.equals(copy.folderId)) {
                    copy.folderId = target.parentId;
                    // Clear the legacy name too, otherwise the v2 reader recreates a root folder.
                    copy.folder = parentName;
                    copy.modified = Math.max(copy.modified, now);
                }
                nextItems.add(copy);
            }
            save(context, nextItems, nextFolders);
            return loadLocked(context);
        }
    }

    /** Updates content modification time without letting a damaged top-level index be overwritten. */
    public static boolean touchModified(Context context, String id, long modifiedAt) throws IOException {
        if (!LibraryItem.isUuid(id)) throw new IOException("无效的文档编号");
        if (modifiedAt < 0) throw new IOException("无效的修改时间");
        synchronized (WRITE_LOCK) {
            State state = load(context);
            if (!state.allowSave) throw new IOException(state.warning.isEmpty() ? "资料库索引当前不可写" : state.warning);
            for (LibraryItem item : state.items) {
                if (!id.equals(item.id)) continue;
                long next = Math.max(item.modified, modifiedAt);
                if (next != item.modified) {
                    item.modified = next;
                    save(context, state.items);
                }
                return true;
            }
            return false;
        }
    }

    public static boolean touchModified(Context context, String id) throws IOException {
        return touchModified(context, id, System.currentTimeMillis());
    }

    /** Read-only reconstruction. Invalid local files are reported and never deleted. */
    public static State scan(Context context) {
        return scan(context, Collections.emptyList());
    }

    public static State scan(Context context, Collection<LibraryItem> metadataHints) {
        Context app = context.getApplicationContext();
        Map<String, LibraryItem> hints = validHints(metadataHints);
        ArrayList<LibraryItem> found = new ArrayList<>();
        ArrayList<LibraryIndex.Damage> damaged = new ArrayList<>();
        Set<String> foundIds = new HashSet<>();
        Map<String, Long> inkTimes = inkModifiedTimes(app.getFilesDir());
        File[] root = app.getFilesDir().listFiles();
        if (root == null) {
            damaged.add(new LibraryIndex.Damage(-1, "", "无法读取应用文件目录"));
            return new State(found, damaged, false, false, "无法扫描本机资料");
        }
        Arrays.sort(root, Comparator.comparing(File::getName));
        for (File file : root) {
            String name = file.getName();
            if (!file.isFile() || !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) continue;
            String id = name.substring(0, name.length() - 4);
            if (!LibraryItem.isUuid(id)) continue;
            try {
                String normalizedId = id.toLowerCase(Locale.ROOT);
                LibraryItem item = scanPdf(file, id, hints.get(normalizedId), inkTimes.get(normalizedId));
                if (foundIds.add(id.toLowerCase(Locale.ROOT))) found.add(item);
                else damaged.add(new LibraryIndex.Damage(-1, name, "重复的本机文档编号"));
            } catch (IOException | RuntimeException | LinkageError failure) {
                damaged.add(new LibraryIndex.Damage(-1, name, "PDF 无法重建：" + message(failure)));
            }
        }

        File notes = new File(app.getFilesDir(), "notes");
        File[] noteDirectories = notes.listFiles();
        if (noteDirectories != null) {
            Arrays.sort(noteDirectories, Comparator.comparing(File::getName));
            for (File note : noteDirectories) {
                String id = note.getName();
                if (!note.isDirectory() || !LibraryItem.isUuid(id)) continue;
                File document = new File(note, "document.qnote");
                if (!document.isFile() && !new File(document.getPath() + ".bak").isFile()) continue;
                try {
                    LibraryItem item = scanNote(app, note, id, hints.get(id.toLowerCase(Locale.ROOT)));
                    if (foundIds.add(id.toLowerCase(Locale.ROOT))) found.add(item);
                    else damaged.add(new LibraryIndex.Damage(-1, "notes/" + id, "重复的本机文档编号"));
                } catch (IOException | RuntimeException | LinkageError failure) {
                    damaged.add(new LibraryIndex.Damage(-1, "notes/" + id, "笔记无法重建：" + message(failure)));
                }
            }
        }
        return new State(found, damaged, true, false,
                damaged.isEmpty() ? "" : damaged.size() + " 个本机项目无法重建，原文件未改动");
    }

    /** Scans first, preserves scan failures, and atomically replaces only the index file. */
    public static State rebuild(Context context, Collection<LibraryItem> metadataHints) throws IOException {
        State before = load(context);
        State scanned = scan(context, metadataHints);
        if (!scanned.allowSave) throw new IOException(scanned.warning);
        if (!scanned.damaged.isEmpty()) preserveScanDamage(context, scanned.damaged);
        replaceIfRevision(context, before.revision, scanned.items, before.folders, false);
        return new State(scanned.items, before.folders, scanned.damaged, true, false, scanned.warning);
    }

    private static LibraryItem scanPdf(File file, String id, LibraryItem hint, Long inkModified) throws IOException {
        int pages;
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            pages = renderer.getPageCount();
        }
        if (pages < 1) throw new IOException("文档没有页面");
        long modified = Math.max(file.lastModified(), inkModified == null ? 0 : inkModified);
        LibraryItem item = base(hint, id, false, modified);
        item.pages = pages;
        item.page = Math.max(0, Math.min(item.page, pages - 1));
        item.size = Math.max(0, file.length());
        item.hash = sha256(file);
        if (hint == null) {
            item.name = "恢复的 PDF " + id.substring(0, 8);
            item.originalName = item.name;
        }
        item.validate();
        return item;
    }

    private static LibraryItem scanNote(Context context, File directory, String id, LibraryItem hint) throws IOException {
        NoteDocument document = NoteStore.load(context, id);
        long[] budget = new long[]{0};
        long modified = newest(directory, 0, budget);
        LibraryItem item = base(hint, id, true, modified);
        item.template = document.template;
        item.paper = document.size;
        item.pages = notePageCount(document, Math.max(1, item.pages));
        item.page = Math.max(0, Math.min(item.page, item.pages - 1));
        item.size = Math.max(0, treeSize(directory, 0, new long[]{0}));
        item.hash = "";
        if (hint == null) {
            item.name = "恢复的笔记 " + id.substring(0, 8);
            item.originalName = item.name;
        }
        item.validate();
        return item;
    }

    private static int notePageCount(NoteDocument document, int fallback) {
        try {
            Method method = document.getClass().getMethod("pageCount");
            Object value = method.invoke(document);
            if (value instanceof Number) {
                int pages = ((Number) value).intValue();
                if (pages > 0 && pages <= 1000000) return pages;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return fallback;
    }

    private static LibraryItem base(LibraryItem hint, String id, boolean note, long sourceModified) {
        LibraryItem item = hint == null ? new LibraryItem() : hint.copy();
        item.id = id;
        item.type = note ? "note" : "pdf";
        item.format = note ? "NOTE" : (empty(item.format) ? "PDF" : item.format);
        item.created = item.created > 0 ? item.created : Math.max(1, sourceModified);
        item.modified = Math.max(item.modified, Math.max(1, sourceModified));
        item.opened = Math.max(0, item.opened);
        item.trashedAt = 0;
        item.name = empty(item.name) ? (note ? "恢复的笔记" : "恢复的 PDF") : item.name;
        item.originalName = empty(item.originalName) ? item.name : item.originalName;
        return item;
    }

    private static Map<String, LibraryItem> validHints(Collection<LibraryItem> hints) {
        HashMap<String, LibraryItem> result = new HashMap<>();
        if (hints == null) return result;
        for (LibraryItem hint : hints) {
            if (hint == null) continue;
            try {
                hint.validate();
                result.put(hint.id.toLowerCase(Locale.ROOT), hint);
            } catch (RuntimeException ignored) { }
        }
        return result;
    }

    private static Map<String, Long> inkModifiedTimes(File directory) {
        HashMap<String, Long> result = new HashMap<>();
        File[] files = directory.listFiles();
        if (files == null) return result;
        for (File file : files) {
            String name = file.getName();
            if (name.length() <= 36 || !LibraryItem.isUuid(name.substring(0, 36))) continue;
            String tail = name.substring(36);
            if (!tail.matches("-[0-9]+\\.ink(?:\\.(?:bak|new))?")) continue;
            String id = name.substring(0, 36).toLowerCase(Locale.ROOT);
            Long previous = result.get(id);
            if (previous == null || previous < file.lastModified()) result.put(id, file.lastModified());
        }
        return result;
    }

    private static void enrichMigratedTimes(Context context, LibraryItem item, long fallback, Long inkModified) {
        File source = item.isNote()
                ? new File(new File(new File(context.getFilesDir(), "notes"), item.id), "document.qnote")
                : new File(context.getFilesDir(), item.id + ".pdf");
        long stamp = source.lastModified();
        if (stamp <= 0) stamp = new File(source.getPath() + ".bak").lastModified();
        if (!item.isNote() && inkModified != null) stamp = Math.max(stamp, inkModified);
        if (stamp <= 0) stamp = fallback;
        item.created = stamp;
        item.modified = stamp;
        if (item.originalName.isEmpty()) item.originalName = item.name;
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("本机不支持 SHA-256", impossible);
        }
    }

    private static String logicalRevision(Collection<LibraryItem> items, Collection<LibraryFolder> folders) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = LibraryIndex.encodeV2(items, folders, 0).getBytes(StandardCharsets.UTF_8);
            byte[] hashed = digest.digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte value : hashed) {
                result.append(String.format(Locale.ROOT, "%02x", value & 255));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static long newest(File file, int depth, long[] visited) {
        if (depth > 8 || visited[0]++ > 100000) return 0;
        long newest = Math.max(0, file.lastModified());
        if (!file.isDirectory()) return newest;
        File[] children = file.listFiles();
        if (children == null) return newest;
        for (File child : children) newest = Math.max(newest, newest(child, depth + 1, visited));
        return newest;
    }

    private static long treeSize(File file, int depth, long[] visited) {
        if (depth > 8 || visited[0]++ > 100000) return 0;
        if (file.isFile()) return Math.max(0, file.length());
        File[] children = file.listFiles();
        if (children == null) return 0;
        long total = 0;
        for (File child : children) {
            long size = treeSize(child, depth + 1, visited);
            total = Long.MAX_VALUE - total < size ? Long.MAX_VALUE : total + size;
        }
        return total;
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count, total = 0;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > LibraryIndex.MAX_JSON_CHARS * 4L) throw new IOException("资料库索引超过大小限制");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("资料库索引不是有效的 UTF-8", malformed);
        }
    }

    private static State state(LibraryIndex.Result result, boolean migrated, String warning) {
        return new State(result.items, result.folders, result.damaged, result.allowSave, migrated, warning);
    }

    private static String damageWarning(LibraryIndex.Result result) {
        return result.damaged.isEmpty() ? "" : result.damaged.size() + " 条损坏记录已隔离并保留";
    }

    private static boolean preserveIfNeeded(Context context, LibraryIndex.Result result) {
        if (result.damaged.isEmpty()) return true;
        try {
            writeRecovery(context, result.legacy ? "legacy" : "index",
                    LibraryIndex.encodeRecovery(result, 0));
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static void preserveScanDamage(Context context, ArrayList<LibraryIndex.Damage> damaged) {
        LibraryIndex.Result result = new LibraryIndex.Result(new ArrayList<>(), damaged, true, false, "");
        try { writeRecovery(context, "scan", LibraryIndex.encodeRecovery(result, 0)); }
        catch (IOException | RuntimeException ignored) { }
    }

    private static void preserveRawIndex(Context context, byte[] bytes) {
        try {
            String name = digestName(bytes);
            File recovery = new File(new File(directory(context), "damaged"), "recovery");
            if (!recovery.isDirectory() && !recovery.mkdirs()) return;
            File target = new File(recovery, "index-raw-" + name + ".bin");
            if (target.exists()) return;
            if (!withinRecoveryBudget(recovery, bytes.length)) return;
            AtomicFile atomic = new AtomicFile(target);
            FileOutputStream output = null;
            try {
                output = atomic.startWrite();
                output.write(bytes);
                output.flush();
                atomic.finishWrite(output);
            } catch (IOException | RuntimeException failure) {
                if (output != null) atomic.failWrite(output);
            }
        } catch (Exception ignored) { }
    }

    private static void writeRecovery(Context context, String prefix, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        synchronized (WRITE_LOCK) {
            File recovery = new File(new File(directory(context), "damaged"), "recovery");
            if (!recovery.isDirectory() && !recovery.mkdirs()) throw new IOException("无法创建索引恢复目录");
            File target = new File(recovery, prefix + "-" + digestName(bytes) + ".json");
            if (target.exists()) return;
            if (!withinRecoveryBudget(recovery, bytes.length)) throw new IOException("索引恢复区已达到安全上限");
            AtomicFile atomic = new AtomicFile(target);
            FileOutputStream output = null;
            try {
                output = atomic.startWrite();
                output.write(bytes);
                output.flush();
                atomic.finishWrite(output);
            } catch (IOException | RuntimeException failure) {
                if (output != null) atomic.failWrite(output);
                if (failure instanceof IOException) throw (IOException) failure;
                throw new IOException("无法保存恢复记录", failure);
            }
        }
    }

    private static String digestName(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder name = new StringBuilder(64);
            for (byte value : digest.digest(bytes)) name.append(String.format(Locale.ROOT, "%02x", value & 255));
            return name.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("设备不支持 SHA-256", impossible);
        }
    }

    private static boolean withinRecoveryBudget(File directory, long additionalBytes) {
        if (additionalBytes < 0 || additionalBytes > MAX_RECOVERY_BYTES) return false;
        File[] files = directory.listFiles();
        if (files == null) return false;
        int count = 0;
        long bytes = 0;
        for (File file : files) {
            if (!file.isFile()) continue;
            count++;
            long size = Math.max(0, file.length());
            if (Long.MAX_VALUE - bytes < size) return false;
            bytes += size;
        }
        return count < MAX_RECOVERY_FILES && bytes <= MAX_RECOVERY_BYTES - additionalBytes;
    }

    private static String message(Throwable failure) {
        String value = failure.getMessage();
        return value == null || value.trim().isEmpty() ? failure.getClass().getSimpleName() : value;
    }

    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
