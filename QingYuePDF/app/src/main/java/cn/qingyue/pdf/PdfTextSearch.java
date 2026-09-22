package cn.qingyue.pdf;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Local text-layer search. This class does not upload documents or perform OCR. */
public final class PdfTextSearch implements AutoCloseable {
    public static final int MAX_RESULT_PAGES = 200;
    private static final int MAX_PAGE_CHARACTERS = 1000000;
    private static final int MAX_PAGE_GLYPHS = 200000;
    private static final long MEMORY_BYTES = 16L * 1024 * 1024;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>(), new ThreadFactory() {
                @Override public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "qingyue-pdf-search");
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                }
            });
    private volatile int generation;
    private volatile boolean closed;
    private Future<?> running;

    public static final class Result {
        /** Zero-based PDF page index. */
        public final int page;
        public final String snippet;
        public final int matches;
        Result(int page, String snippet, int matches) {
            this.page = page; this.snippet = snippet; this.matches = matches;
        }
    }

    public interface Listener {
        /** Main thread; batch contains only newly found pages, not all previous results. */
        void onProgress(int done, int total, List<Result> batch);
        /** Main thread. searchablePages counts pages with extractable text that were examined. */
        void onComplete(int totalPages, int searchablePages, boolean limited, String error);
    }

    public PdfTextSearch(Context context) { this.context = context.getApplicationContext(); }

    /** Replaces the current search and discards every previous queued callback. */
    public synchronized void start(final File file, final String query, final Listener listener) {
        if (listener == null) throw new IllegalArgumentException("listener");
        cancel();
        if (closed) return;
        final int token = generation;
        running = worker.submit(new Runnable() {
            @Override public void run() { search(file, query, listener, token); }
        });
    }

    public synchronized void cancel() {
        generation++;
        if (running != null) { running.cancel(true); running = null; }
        worker.purge();
        main.removeCallbacksAndMessages(null);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        cancel();
        worker.shutdownNow();
    }

    private boolean current(int token) { return !closed && token == generation; }
    private void check(int token) {
        if (!current(token) || Thread.currentThread().isInterrupted()) throw new SearchCancelled();
    }

    private void progress(final int token, final Listener listener, final int done, final int total,
            final List<Result> batch) {
        if (!current(token)) return;
        final List<Result> snapshot = Collections.unmodifiableList(new ArrayList<Result>(batch));
        main.post(new Runnable() {
            @Override public void run() { if (current(token)) listener.onProgress(done, total, snapshot); }
        });
    }

    private void complete(final int token, final Listener listener, final int total, final int searchable,
            final boolean limited, final String error) {
        if (!current(token)) return;
        main.post(new Runnable() {
            @Override public void run() {
                if (current(token)) listener.onComplete(total, searchable, limited, error);
            }
        });
    }

    private void search(File file, String query, Listener listener, int token) {
        int total = 0;
        PageSearch stripper = null;
        String error = null;
        try {
            check(token);
            String normalized = SearchText.normalize(query);
            if (normalized.length() == 0) { complete(token, listener, 0, 0, false, "请输入搜索内容"); return; }
            if (file == null || !file.isFile()) { complete(token, listener, 0, 0, false, "找不到这份 PDF 文件"); return; }
            PDFBoxResourceLoader.init(context);
            File scratch = new File(context.getCacheDir(), "pdf-text-search");
            if (!scratch.isDirectory() && !scratch.mkdirs()) throw new IOException("Cannot create PDF scratch directory");
            MemoryUsageSetting memory = MemoryUsageSetting.setupMixed(MEMORY_BYTES).setTempDir(scratch);
            try (PDDocument document = PDDocument.load(file, memory)) {
                check(token);
                total = document.getNumberOfPages();
                progress(token, listener, 0, total, Collections.<Result>emptyList());
                if (!document.getCurrentAccessPermission().canExtractContent()) {
                    complete(token, listener, total, 0, false, "这份 PDF 限制文字提取，无法搜索内容");
                    return;
                }
                stripper = new PageSearch(token, normalized, listener, total);
                try { stripper.writeText(document, stripper.text); }
                catch (ResultLimit reached) { stripper.limited = true; }
                check(token);
                if (!stripper.limited) stripper.lastPage = total;
                stripper.flushProgress(stripper.lastPage);
                if (stripper.failedPages > 0) error = "有 " + stripper.failedPages + " 页无法提取文字，已显示其余页面的结果";
            }
        } catch (SearchCancelled ignored) {
            return;
        } catch (InvalidPasswordException password) {
            error = "这份 PDF 需要密码，暂时无法搜索";
        } catch (IOException problem) {
            error = "无法读取 PDF 文字，请确认文件完整后重试";
        } catch (RuntimeException problem) {
            error = "这份 PDF 的文字结构暂不支持搜索";
        } catch (OutOfMemoryError problem) {
            error = "这份 PDF 的文字结构过大，请关闭其他文档后重试";
        }
        if (stripper != null && !stripper.batch.isEmpty()) stripper.flushProgress(stripper.lastPage);
        complete(token, listener, total, stripper == null ? 0 : stripper.searchablePages,
                stripper != null && stripper.limited, error);
    }

    /** A single traversal of the page tree. Only the current page's text is retained. */
    private final class PageSearch extends PDFTextStripper {
        final int token, total;
        final String query;
        final Listener listener;
        final PageWriter text;
        final ArrayList<Result> batch = new ArrayList<Result>();
        int searchablePages, resultPages, failedPages, lastPage, glyphs;
        long lastUpdate;
        boolean limited;

        PageSearch(int token, String query, Listener listener, int total) throws IOException {
            this.token = token; this.query = query; this.listener = listener; this.total = total;
            text = new PageWriter(token);
            setSortByPosition(true);
            setShouldSeparateByBeads(false);
        }

        @Override public void processPage(PDPage page) throws IOException {
            check(token);
            text.clear();
            glyphs = 0;
            lastPage = getCurrentPageNo();
            try {
                super.processPage(page);
                String pageText = SearchText.normalize(text.content.toString());
                if (pageText.length() > 0) searchablePages++;
                SearchText.Match match = SearchText.find(pageText, query);
                if (match != null) {
                    batch.add(new Result(lastPage - 1, match.snippet, match.count));
                    resultPages++;
                }
            } catch (PageTooLarge tooLarge) {
                failedPages++;
            } catch (IOException unreadable) {
                failedPages++;
            }
            text.clear();
            check(token);
            long now = SystemClock.uptimeMillis();
            if (now - lastUpdate >= 100 || batch.size() >= 20) flushProgress(lastPage);
            if (resultPages >= MAX_RESULT_PAGES && lastPage < total) throw new ResultLimit();
        }

        @Override protected void processTextPosition(TextPosition position) {
            check(token);
            if (++glyphs > MAX_PAGE_GLYPHS) throw new PageTooLarge();
            super.processTextPosition(position);
        }

        void flushProgress(int done) {
            progress(token, listener, done, total, batch);
            batch.clear();
            lastUpdate = SystemClock.uptimeMillis();
        }
    }

    private final class PageWriter extends Writer {
        final StringBuilder content = new StringBuilder();
        final int token;
        PageWriter(int token) { this.token = token; }
        void clear() { content.setLength(0); }
        @Override public void write(char[] buffer, int offset, int count) {
            check(token);
            if (content.length() + (long) count > MAX_PAGE_CHARACTERS) throw new PageTooLarge();
            content.append(buffer, offset, count);
        }
        @Override public void flush() {}
        @Override public void close() {}
    }

    private static final class SearchCancelled extends RuntimeException {}
    private static final class ResultLimit extends RuntimeException {}
    private static final class PageTooLarge extends RuntimeException {}
}
