package cn.qingyue.pdf;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Process-level, Activity-independent document import engine.
 *
 * <p>Callers retain a session id in saved instance state, then use {@link #find(String)}
 * and {@link ImportSession#attach(ImportSession.Listener)} after recreation.  A successful
 * session owns private staging files until exactly one caller obtains a claim.  The caller
 * commits those files and its library metadata transaction, calls {@code claim.complete()},
 * or calls {@code claim.release()} if its transaction failed. Terminal snapshots stay in
 * the process registry until the UI consumes them with {@link #forget(String)}; staging
 * files are cleaned independently as soon as they are no longer needed.</p>
 */
public final class ImportCoordinator {
    public static final long MAX_SOURCE_BYTES = 200L * 1024 * 1024;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long PROGRESS_STEP = 256L * 1024;
    private static final long STALE_AGE_MILLIS = 24L * 60 * 60 * 1000;
    private static final String STAGING = "import-staging";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // A hostile/broken ContentProvider can ignore interruption inside open/read. A small bounded
    // pool plus active stream closing prevents one canceled provider from wedging every later
    // import for the lifetime of the process.
    private static final ExecutorService WORKER = new ThreadPoolExecutor(0, 4, 30L,
            TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
        Thread thread = new Thread(runnable, "qingyue-import");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private static final ExecutorService CLEANUP = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "qingyue-import-cleanup");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private static final ConcurrentHashMap<String, ImportSession> SESSIONS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, File> DIRECTORIES =
            new ConcurrentHashMap<>();

    private static final ImportSession.Owner OWNER = new ImportSession.Owner() {
        @Override public void cancel(ImportSession session) { cleanupLater(session); }
        @Override public void consumed(ImportSession session) { cleanupLater(session); }
        @Override public void discarded(ImportSession session) { cleanupLater(session); }
    };

    private ImportCoordinator() { }

    public static ImportSession startUri(Context context, Uri uri, boolean keepOriginal) {
        return startUri(context,uri,keepOriginal,null);
    }

    /** Layout applies to new convertible input only; a source PDF remains untouched. */
    public static ImportSession startUri(Context context, Uri uri, boolean keepOriginal,
            DocumentImport.LayoutOptions layoutOptions) {
        if (context == null) throw new IllegalArgumentException("context == null");
        final Context app = application(context);
        final String id = UUID.randomUUID().toString();
        final ImportSession session = create(app, id);
        if (uri == null) {
            session.fail("没有收到要导入的文件");
            return session;
        }
        try {
            Future<?> future = WORKER.submit(() -> runUri(app, uri, keepOriginal, layoutOptions, session));
            session.setFuture(future);
        } catch (RejectedExecutionException busy) {
            session.fail("导入任务繁忙，请稍后重试");
        }
        return session;
    }

    public static ImportSession startUri(Context context, Uri uri) {
        return startUri(context, uri, false);
    }

    /** Starts an EXTRA_TEXT-style import.  The text is copied away from the Intent immediately. */
    public static ImportSession startText(Context context, CharSequence text, String displayName,
            boolean keepOriginal) {
        return startText(context,text,displayName,keepOriginal,null);
    }

    public static ImportSession startText(Context context, CharSequence text, String displayName,
            boolean keepOriginal, DocumentImport.LayoutOptions layoutOptions) {
        if (context == null) throw new IllegalArgumentException("context == null");
        final Context app = application(context);
        final String id = UUID.randomUUID().toString();
        final ImportSession session = create(app, id);
        final String copiedText = text == null ? null : text.toString();
        final String copiedName = displayName == null ? "" : displayName;
        try {
            Future<?> future = WORKER.submit(() -> runText(app, copiedText, copiedName,
                    keepOriginal, layoutOptions, session));
            session.setFuture(future);
        } catch (RejectedExecutionException busy) {
            session.fail("导入任务繁忙，请稍后重试");
        }
        return session;
    }

    public static ImportSession startText(Context context, CharSequence text, String displayName) {
        return startText(context, text, displayName, false);
    }

    public static ImportSession find(String sessionId) {
        return sessionId == null ? null : SESSIONS.get(sessionId);
    }

    /** Snapshot ordered newest first, useful when an Activity was recreated without saved state. */
    public static List<ImportSession> sessions() {
        ArrayList<ImportSession> result = new ArrayList<>(SESSIONS.values());
        Collections.sort(result, new Comparator<ImportSession>() {
            @Override public int compare(ImportSession left, ImportSession right) {
                return Long.compare(right.startedAt(), left.startedAt());
            }
        });
        return result;
    }

    /** Removes a consumed completion, failure or cancellation from the process registry. */
    public static boolean forget(String sessionId) {
        ImportSession session = find(sessionId);
        if (session == null) return false;
        ImportSession.Phase phase = session.phase();
        if (phase != ImportSession.Phase.FAILED && phase != ImportSession.Phase.CANCELED
                && phase != ImportSession.Phase.COMPLETED) return false;
        if (!SESSIONS.remove(sessionId, session)) return false;
        cleanupLater(session);
        return true;
    }

    /** Best-effort cleanup for staging left by a process death; live sessions are never touched. */
    public static void cleanupStale(Context context) {
        if (context == null) return;
        final Context app = application(context);
        CLEANUP.execute(() -> {
            File root = new File(app.getFilesDir(), STAGING);
            File[] children = root.listFiles();
            if (children == null) return;
            long cutoff = System.currentTimeMillis() - STALE_AGE_MILLIS;
            for (File child : children) {
                if (SESSIONS.containsKey(child.getName()) || child.lastModified() > cutoff) continue;
                try { deleteTree(child); }
                catch (IOException ignored) { }
            }
            File[] remaining = root.listFiles();
            if (remaining != null && remaining.length == 0) root.delete();
        });
    }

    private static ImportSession create(Context app, String id) {
        ImportSession session = new ImportSession(id, runnable -> MAIN.post(runnable), OWNER);
        File root = new File(app.getFilesDir(), STAGING);
        File directory = new File(root, id);
        DIRECTORIES.put(id, directory);
        SESSIONS.put(id, session);
        return session;
    }

    private static Context application(Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }

    private static void runUri(Context app, Uri uri, boolean keepOriginal,
            DocumentImport.LayoutOptions layoutOptions,
            ImportSession session) {
        File directory = DIRECTORIES.get(session.id());
        try {
            prepare(directory);
            SourceInfo metadata = query(app.getContentResolver(), uri);
            File source = new File(directory, "source.bin");
            CopyResult copied;
            InputStream raw = app.getContentResolver().openInputStream(uri);
            if (raw == null) throw new IOException("无法读取所选文件");
            if (!session.beginResource(raw)) throw new InterruptedIOException("已取消导入");
            try (InputStream opened = raw; InputStream input = new BufferedInputStream(opened)) {
                copied = copy(input, source, metadata.size, session);
            } finally {
                session.endResource(raw);
            }
            process(app, session, directory, source, metadata.name, metadata.mime,
                    copied, keepOriginal, false, layoutOptions);
        } catch (Exception | LinkageError error) {
            stopped(session, directory, error);
        } catch (OutOfMemoryError memory) {
            stopped(session, directory, new IOException("导入内容较大，当前设备内存不足", memory));
        }
    }

    private static void runText(Context app, String text, String displayName,
            boolean keepOriginal, DocumentImport.LayoutOptions layoutOptions, ImportSession session) {
        File directory = DIRECTORIES.get(session.id());
        try {
            prepare(directory);
            String normalized = DocumentText.prepareSharedText(text);
            byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > DocumentText.MAX_TEXT_BYTES)
                throw new IOException("分享文字超过 8 MB，请分段导入");
            String name = cleanDisplayName(displayName);
            if (name.isEmpty()) name = "分享文字.txt";
            File source = new File(directory, "source.bin");
            CopyResult copied = copy(new java.io.ByteArrayInputStream(bytes), source,
                    bytes.length, session);
            process(app, session, directory, source, name, "text/plain", copied,
                    keepOriginal, true, layoutOptions);
        } catch (Exception | LinkageError error) {
            stopped(session, directory, error);
        } catch (OutOfMemoryError memory) {
            stopped(session, directory, new IOException("分享文字较大，当前设备内存不足", memory));
        }
    }

    private static void process(Context app, ImportSession session, File directory, File source,
            String displayName, String mime, CopyResult copied, boolean keepOriginal,
            boolean forceText, DocumentImport.LayoutOptions layoutOptions) throws Exception {
        check(session);
        boolean pdfInput = !forceText && looksLikePdf(source);
        File pdf = new File(directory, "document.pdf");
        String format;
        File attachment = null;
        String attachmentName = "";

        if (pdfInput) {
            format = "PDF";
            move(source, pdf, session);
        } else {
            File converted = new File(directory, "converted.tmp.pdf");
            session.progress(ImportSession.Phase.EXTRACTING, ImportSession.Unit.BYTES,
                    0, copied.bytes, "正在提取正文");
            DocumentImport.Callback callback =
                    new DocumentImport.Callback() {
                        @Override public boolean isCancelled() {
                            return session.cancellationRequested();
                        }
                        @Override public void extracting(long completed, long total) {
                            session.progress(ImportSession.Phase.EXTRACTING,
                                    ImportSession.Unit.BYTES, completed, total,
                                    "正在提取正文");
                        }
                        @Override public void page(int completed, int total) {
                            session.progress(ImportSession.Phase.CONVERTING,
                                    ImportSession.Unit.PAGES, completed, total,
                                    "正在生成第 " + completed + " 页");
                        }
                    };
            format = layoutOptions==null
                    ?DocumentImport.convert(app,source,displayName,mime,converted,callback)
                    :DocumentImport.convert(app,source,displayName,mime,converted,callback,layoutOptions);
            check(session);
            move(converted, pdf, session);
            if (keepOriginal) {
                attachmentName = cleanDisplayName(displayName);
                if (attachmentName.isEmpty()) attachmentName = "原文档." + format.toLowerCase(Locale.ROOT);
                attachment = new File(directory, "original." + extension(format));
                move(source, attachment, session);
            } else if (source.exists() && !source.delete()) {
                throw new IOException("无法清理导入临时文件");
            }
        }

        check(session);
        session.progress(ImportSession.Phase.VALIDATING, ImportSession.Unit.NONE,
                0, -1, "正在校验生成的 PDF");
        int pages;
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(pdf,
                ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            pages = renderer.getPageCount();
        }
        if (pages < 1) throw new IOException("这份文档没有可阅读的页面");
        check(session);

        // A domain-separated hash keeps layout variants distinct and repeated imports stable
        // even if PdfDocument writes non-deterministic metadata into equivalent output files.
        String libraryHash=pdfInput||layoutOptions==null?copied.hash
                :ImportVariantKey.hash(copied.hash,
                        layoutOptions.page.name(),layoutOptions.font.name());
        ImportSession.Result result = new ImportSession.Result(session.id(), displayName,
                libraryHash, pages, copied.bytes, format, pdf, attachment,
                attachmentName, directory);
        if (!session.ready(result)) deleteTree(directory);
    }

    private static CopyResult copy(InputStream input, File target, long statedTotal,
            ImportSession session) throws Exception {
        if (statedTotal > MAX_SOURCE_BYTES)
            throw new IOException("每份文档最大支持 200 MB");
        long total = statedTotal > 0 ? statedTotal : -1;
        session.progress(ImportSession.Phase.COPYING, ImportSession.Unit.BYTES,
                0, total, "正在复制到本机");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long bytes = 0, lastReported = 0;
        boolean success = false;
        try (FileOutputStream file = new FileOutputStream(target);
             OutputStream output = new BufferedOutputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) {
                check(session);
                bytes += count;
                if (bytes > MAX_SOURCE_BYTES)
                    throw new IOException("每份文档最大支持 200 MB");
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                if (bytes - lastReported >= PROGRESS_STEP) {
                    lastReported = bytes;
                    session.progress(ImportSession.Phase.COPYING, ImportSession.Unit.BYTES,
                            bytes, total, "正在复制到本机");
                }
            }
            output.flush();
            file.getFD().sync();
            success = true;
        } finally {
            if (!success) target.delete();
        }
        check(session);
        session.progress(ImportSession.Phase.COPYING, ImportSession.Unit.BYTES,
                bytes, bytes, "文件复制完成");
        return new CopyResult(bytes, hex(digest.digest()));
    }

    private static boolean looksLikePdf(File source) throws IOException {
        byte[] signature = new byte[1024];
        int count;
        try (InputStream input = new FileInputStream(source)) { count = input.read(signature); }
        if (count <= 0) return false;
        String probe = new String(signature, 0, count, StandardCharsets.ISO_8859_1);
        return probe.contains("%PDF-");
    }

    private static void move(File source, File target, ImportSession session) throws Exception {
        check(session);
        if (target.exists()) throw new IOException("导入暂存文件已存在");
        if (source.renameTo(target)) return;
        boolean success = false;
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             FileOutputStream file = new FileOutputStream(target);
             OutputStream output = new BufferedOutputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) {
                check(session);
                output.write(buffer, 0, count);
            }
            output.flush();
            file.getFD().sync();
            success = true;
        } finally {
            if (!success) target.delete();
        }
        if (!source.delete()) {
            target.delete();
            throw new IOException("无法移动导入暂存文件");
        }
    }

    private static SourceInfo query(ContentResolver resolver, Uri uri) {
        String name = "";
        long size = -1;
        try (Cursor cursor = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn);
            }
        } catch (RuntimeException ignored) { }
        name = cleanDisplayName(name);
        if (name.isEmpty()) name = cleanDisplayName(uri.getLastPathSegment());
        if (name.isEmpty()) name = "未命名文档";
        String mime;
        try { mime = resolver.getType(uri); }
        catch (RuntimeException ignored) { mime = null; }
        return new SourceInfo(name, mime, size);
    }

    private static String cleanDisplayName(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(Math.min(150, value.length()));
        for (int offset = 0; offset < value.length() && out.length() < 150;) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == '/' || cp == '\\' || cp == '\r' || cp == '\n'
                    || Character.getType(cp) == Character.CONTROL) continue;
            out.appendCodePoint(cp);
        }
        return out.toString().trim();
    }

    private static String extension(String format) {
        if ("DOC".equals(format)) return "doc";
        if ("DOCX".equals(format)) return "docx";
        if ("EPUB".equals(format)) return "epub";
        return "txt";
    }

    private static void check(ImportSession session) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || session.cancellationRequested())
            throw new InterruptedIOException("已取消导入");
    }

    private static void stopped(ImportSession session, File directory, Throwable error) {
        if (!session.cancellationRequested()) session.fail(message(error));
        try { deleteTree(directory); }
        catch (IOException ignored) { }
        DIRECTORIES.remove(session.id(), directory);
    }

    private static String message(Throwable error) {
        if (error instanceof SecurityException)
            return "文件无访问权限，或文档已加密。请先解密后导入。";
        String message = error == null ? null : error.getMessage();
        if (message == null || message.trim().isEmpty())
            return "无法导入：请确认是完整、未加密的 PDF、DOC、DOCX 或 TXT 文件。";
        return message;
    }

    private static void prepare(File directory) throws IOException {
        if (directory == null) throw new IOException("无法创建导入暂存目录");
        File parent = directory.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs()))
            throw new IOException("无法创建导入暂存目录");
        if (directory.exists() || !directory.mkdir())
            throw new IOException("导入暂存目录已存在");
    }

    private static void cleanupLater(ImportSession session) {
        File directory = DIRECTORIES.remove(session.id());
        if (directory == null) return;
        CLEANUP.execute(() -> {
            try { deleteTree(directory); }
            catch (IOException ignored) { }
        });
    }

    private static void deleteTree(File file) throws IOException {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("无法读取导入暂存目录");
            for (File child : children) deleteTree(child);
        }
        if (!file.delete()) throw new IOException("无法清理导入暂存文件");
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte item : value) out.append(String.format(Locale.ROOT, "%02x", item & 255));
        return out.toString();
    }

    private static final class SourceInfo {
        final String name, mime;
        final long size;
        SourceInfo(String name, String mime, long size) {
            this.name = name;
            this.mime = mime;
            this.size = size;
        }
    }

    private static final class CopyResult {
        final long bytes;
        final String hash;
        CopyResult(long bytes, String hash) { this.bytes = bytes; this.hash = hash; }
    }
}
