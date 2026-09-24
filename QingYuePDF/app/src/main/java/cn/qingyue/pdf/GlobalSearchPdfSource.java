package cn.qingyue.pdf;

import android.content.Context;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;
import java.io.*;
import java.util.*;

/** Real, offline PDF text-layer source. It does not OCR pages and never uploads a document. */
public final class GlobalSearchPdfSource implements GlobalSearchSource {
    public static final String SOURCE_ID = "pdf-text-v1";
    private static final int MAX_PAGE_CHARACTERS = 1000000;
    private static final int MAX_PAGE_GLYPHS = 200000;
    private static final long MEMORY_BYTES = 16L * 1024 * 1024;

    private final Context context;
    private final List<Item> items;
    private final Map<String, Item> byId = new HashMap<String, Item>();

    public GlobalSearchPdfSource(Context context, List<Item> items) {
        if (context == null || items == null) throw new IllegalArgumentException();
        this.context = context.getApplicationContext();
        this.items = Collections.unmodifiableList(new ArrayList<Item>(items));
        for (Item item : items) {
            if (item == null || byId.put(item.id, item) != null)
                throw new IllegalArgumentException("重复或空 PDF 条目");
        }
    }

    public static final class Item {
        public final String id, name, libraryHash;
        public final File file;
        public final int pageCount;
        public Item(String id, String name, File file, String libraryHash, int pageCount) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.file = file;
            this.libraryHash = libraryHash == null ? "" : libraryHash;
            this.pageCount = pageCount;
        }
    }

    @Override public String sourceId() { return SOURCE_ID; }

    @Override public List<Document> scan(Cancellation cancellation) throws IOException {
        ArrayList<Document> result = new ArrayList<Document>();
        for (Item item : items) {
            cancelled(cancellation);
            if (item.file == null || !item.file.isFile()) continue;
            String fingerprint = RecognitionOcrFingerprint.sha256(item.file, cancellation)
                    + ":" + item.pageCount;
            result.add(new Document(item.id, item.name, "pdf", fingerprint,
                    Math.max(0, item.pageCount), JUMP_PDF_PAGE));
        }
        return result;
    }

    @Override public void read(Document document, Sink sink, Cancellation cancellation)
            throws IOException {
        Item item = byId.get(document.id);
        if (item == null || item.file == null || !item.file.isFile())
            throw new FileNotFoundException("找不到 PDF 文件");
        cancelled(cancellation);
        PDFBoxResourceLoader.init(context);
        File scratch = new File(context.getCacheDir(), "global-search-pdfbox");
        if (!scratch.isDirectory() && !scratch.mkdirs()) throw new IOException("无法创建 PDF 临时目录");
        MemoryUsageSetting memory = MemoryUsageSetting.setupMixed(MEMORY_BYTES).setTempDir(scratch);
        try (PDDocument pdf = PDDocument.load(item.file, memory)) {
            cancelled(cancellation);
            if (!pdf.getCurrentAccessPermission().canExtractContent())
                throw new IOException("这份 PDF 限制文字提取");
            PageExtractor extractor = new PageExtractor(sink, cancellation);
            try {
                extractor.writeText(pdf, extractor.writer);
            } catch (Cancelled cancelled) {
                throw interrupted();
            }
        } catch (InvalidPasswordException password) {
            throw new IOException("这份 PDF 需要密码", password);
        } catch (Cancelled cancelled) {
            throw interrupted();
        } catch (OutOfMemoryError memoryFailure) {
            throw new IOException("PDF 文字结构过大，无法建立索引", memoryFailure);
        }
    }

    private final class PageExtractor extends PDFTextStripper {
        final Sink sink;
        final Cancellation cancellation;
        final PageWriter writer = new PageWriter();
        int glyphs;

        PageExtractor(Sink sink, Cancellation cancellation) throws IOException {
            this.sink = sink; this.cancellation = cancellation;
            setSortByPosition(true); setShouldSeparateByBeads(false);
        }

        @Override public void processPage(PDPage page) throws IOException {
            checkRuntime(); writer.clear(); glyphs = 0;
            int pageIndex = getCurrentPageNo() - 1;
            try {
                super.processPage(page);
                checkRuntime();
                sink.accept(new Fragment(pageIndex, CHANNEL_PDF_TEXT, "", writer.content.toString(),
                        JUMP_PDF_PAGE, ""));
            } catch (PageTooLarge ignored) {
                // One pathological page must not prevent the rest of the library being indexed.
            } finally {
                writer.clear();
            }
            checkRuntime();
        }

        @Override protected void processTextPosition(TextPosition position) {
            checkRuntime();
            if (++glyphs > MAX_PAGE_GLYPHS) throw new PageTooLarge();
            super.processTextPosition(position);
        }

        void checkRuntime() {
            if (cancellation != null && cancellation.isCancelled()) throw new Cancelled();
        }

        final class PageWriter extends Writer {
            final StringBuilder content = new StringBuilder();
            void clear() { content.setLength(0); }
            @Override public void write(char[] buffer, int offset, int count) {
                checkRuntime();
                if (content.length() + (long)count > MAX_PAGE_CHARACTERS)
                    throw new PageTooLarge();
                content.append(buffer, offset, count);
            }
            @Override public void flush() { }
            @Override public void close() { }
        }
    }

    private static void cancelled(Cancellation cancellation) throws InterruptedIOException {
        if (cancellation != null && cancellation.isCancelled()) throw interrupted();
    }

    private static InterruptedIOException interrupted() {
        return new InterruptedIOException("搜索索引已取消");
    }

    private static final class Cancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
    private static final class PageTooLarge extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
