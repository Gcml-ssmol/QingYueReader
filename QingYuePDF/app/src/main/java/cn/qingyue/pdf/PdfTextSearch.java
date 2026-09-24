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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
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
    public static final int MAX_HITS_PER_PAGE = 200, MAX_COORDINATE_HITS = 2000;
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
        /** Every concrete occurrence in reading order. Empty only for unsupported glyph maps. */
        public final List<Hit> hits;
        public final boolean coordinatesLimited;
        Result(int page, String snippet, int matches) { this(page,snippet,matches,Collections.<Hit>emptyList(),false); }
        Result(int page, String snippet, int matches,List<Hit> hits,boolean coordinatesLimited) {
            this.page = page; this.snippet = snippet; this.matches = matches;
            this.hits=Collections.unmodifiableList(new ArrayList<Hit>(hits));
            this.coordinatesLimited=coordinatesLimited;
        }
    }

    public static final class Hit {
        public final int page,ordinal;public final String text;public final List<ReaderDocument.Box> boxes;
        Hit(int page,int ordinal,String text,List<ReaderDocument.Box> boxes){
            this.page=page;this.ordinal=ordinal;this.text=text==null?"":text;
            this.boxes=Collections.unmodifiableList(new ArrayList<ReaderDocument.Box>(boxes));
        }
    }

    public static final class Token {
        public final String text;public final ReaderDocument.Box box;public final int start,end;
        Token(String text,ReaderDocument.Box box,int start,int end){this.text=text;this.box=box;this.start=start;this.end=end;}
    }

    public static final class PageText {
        public final int page;public final String text;public final List<Token> tokens;
        PageText(int page,String text,List<Token> tokens){this.page=page;this.text=text;this.tokens=Collections.unmodifiableList(new ArrayList<Token>(tokens));}
        public boolean hasText(){return !tokens.isEmpty();}
    }

    public interface Listener {
        /** Main thread; batch contains only newly found pages, not all previous results. */
        void onProgress(int done, int total, List<Result> batch);
        /** Main thread. searchablePages counts pages with extractable text that were examined. */
        void onComplete(int totalPages, int searchablePages, boolean limited, String error);
    }

    public PdfTextSearch(Context context) { this.context = context.getApplicationContext(); }

    /** Blocking single-page extraction; call from a background executor, never the UI thread. */
    public PageText extractPage(File file,int zeroBasedPage)throws IOException {
        if(closed)throw new IOException("Search is closed");if(file==null||!file.isFile())throw new FileNotFoundException("PDF");
        PDFBoxResourceLoader.init(context);File scratch=new File(context.getCacheDir(),"pdf-text-search");
        if(!scratch.isDirectory()&&!scratch.mkdirs())throw new IOException("Cannot create PDF scratch directory");
        MemoryUsageSetting memory=MemoryUsageSetting.setupMixed(MEMORY_BYTES).setTempDir(scratch);
        try(PDDocument document=PDDocument.load(file,memory)){
            if(zeroBasedPage<0||zeroBasedPage>=document.getNumberOfPages())throw new IndexOutOfBoundsException("page");
            if(!document.getCurrentAccessPermission().canExtractContent())throw new IOException("PDF forbids text extraction");
            PageExtractor extractor=new PageExtractor(zeroBasedPage);extractor.setStartPage(zeroBasedPage+1);extractor.setEndPage(zeroBasedPage+1);
            try{extractor.writeText(document,extractor.text);return extractor.pageText();}
            catch(SearchCancelled cancelled){throw new IOException("Text extraction cancelled",cancelled);}
            catch(PageTooLarge tooLarge){throw new IOException("Page text is too large",tooLarge);}
        }
    }

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
        int searchablePages, resultPages, failedPages, lastPage, glyphs,coordinateHits;
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
                    int allowed=Math.max(0,Math.min(MAX_HITS_PER_PAGE,MAX_COORDINATE_HITS-coordinateHits));
                    List<PdfTextGeometry.Match> geometry=PdfTextGeometry.find(text.content.toString(),text.boxes,query,allowed);
                    ArrayList<Hit> hits=new ArrayList<Hit>();int ordinal=0;
                    for(PdfTextGeometry.Match item:geometry)hits.add(new Hit(lastPage-1,ordinal++,item.text,item.boxes));
                    coordinateHits+=hits.size();batch.add(new Result(lastPage - 1, match.snippet, match.count,hits,match.count>hits.size()));
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

        @Override protected void writeString(String value,List<TextPosition> positions)throws IOException {
            text.begin(value,positions);try{super.writeString(value,positions);}finally{text.end();}
        }

        void flushProgress(int done) {
            progress(token, listener, done, total, batch);
            batch.clear();
            lastUpdate = SystemClock.uptimeMillis();
        }
    }

    private final class PageWriter extends Writer {
        final StringBuilder content = new StringBuilder();
        final ArrayList<ReaderDocument.Box> boxes=new ArrayList<ReaderDocument.Box>();
        final int token;
        ReaderDocument.Box[] active;int activeOffset;
        PageWriter(int token) { this.token = token; }
        void clear() { content.setLength(0);boxes.clear();active=null;activeOffset=0; }
        void begin(String value,List<TextPosition> positions){active=map(value,positions);activeOffset=0;}
        void end(){active=null;activeOffset=0;}
        @Override public void write(char[] buffer, int offset, int count) {
            if(token>=0)check(token);else if(closed||Thread.currentThread().isInterrupted())throw new SearchCancelled();
            if (content.length() + (long) count > MAX_PAGE_CHARACTERS) throw new PageTooLarge();
            content.append(buffer, offset, count);for(int i=0;i<count;i++)boxes.add(active!=null&&activeOffset<active.length?active[activeOffset++]:null);
        }
        @Override public void flush() {}
        @Override public void close() {}
    }

    /** Extracts selectable text without retaining PDFBox objects after the document closes. */
    private final class PageExtractor extends PDFTextStripper {
        final int requested;final PageWriter text=new PageWriter(-1);int glyphs;
        PageExtractor(int requested)throws IOException{this.requested=requested;setSortByPosition(true);setShouldSeparateByBeads(false);}
        @Override protected void writeString(String value,List<TextPosition> positions)throws IOException{
            text.begin(value,positions);try{super.writeString(value,positions);}finally{text.end();}
        }
        @Override protected void processTextPosition(TextPosition position){if(++glyphs>MAX_PAGE_GLYPHS)throw new PageTooLarge();super.processTextPosition(position);}
        PageText pageText(){
            return PdfTextGeometry.selectablePage(requested,text.content.toString(),text.boxes);
        }
    }

    private static ReaderDocument.Box[] map(String value,List<TextPosition> positions){
        ReaderDocument.Box[] result=new ReaderDocument.Box[value.length()];if(value.length()==0||positions==null||positions.isEmpty())return result;
        StringBuilder joined=new StringBuilder();for(TextPosition position:positions)if(position.getUnicode()!=null)joined.append(position.getUnicode());
        if(joined.toString().equals(value)){
            int offset=0;for(TextPosition position:positions){String unicode=position.getUnicode();if(unicode==null)continue;ReaderDocument.Box box=box(position);for(int i=0;i<unicode.length()&&offset<result.length;i++)result[offset++]=box;}
        }else{
            ReaderDocument.Box fallback=null;for(TextPosition position:positions){ReaderDocument.Box item=box(position);fallback=fallback==null?item:union(fallback,item);}
            Arrays.fill(result,fallback);
        }return result;
    }
    private static ReaderDocument.Box box(TextPosition position){
        int rotation=((position.getRotation()%360)+360)%360;float pageWidth=position.getPageWidth(),pageHeight=position.getPageHeight();
        float displayWidth=Math.max(1,rotation==90||rotation==270?pageHeight:pageWidth);
        float[] start=display(position.getTextMatrix().getTranslateX(),position.getTextMatrix().getTranslateY(),pageWidth,pageHeight,rotation);
        float[] end=display(position.getEndX(),position.getEndY(),pageWidth,pageHeight,rotation);
        float height=Math.max(.1f,position.getHeight()),left=Math.min(start[0],end[0])-height*.08f,right=Math.max(start[0],end[0])+height*.08f;
        float top=Math.min(start[1],end[1])-height,bottom=Math.max(start[1],end[1])+height*.12f;
        float padding=Math.min(displayWidth*.0025f,height*.08f);
        return new ReaderDocument.Box(clamp((left-padding)/displayWidth,0,1),Math.max(0,(top-padding)/displayWidth),clamp((right+padding)/displayWidth,0,1),Math.max(0,(bottom+padding)/displayWidth));
    }
    private static float[] display(float x,float y,float width,float height,int rotation){
        if(rotation==90)return new float[]{y,x};if(rotation==180)return new float[]{width-x,y};if(rotation==270)return new float[]{height-y,width-x};return new float[]{x,height-y};
    }
    private static ReaderDocument.Box union(ReaderDocument.Box a,ReaderDocument.Box b){
        return new ReaderDocument.Box(Math.min(a.left,b.left),Math.min(a.top,b.top),Math.max(a.right,b.right),Math.max(a.bottom,b.bottom));
    }
    private static float clamp(float value,float min,float max){return Math.max(min,Math.min(max,value));}

    private static final class SearchCancelled extends RuntimeException {}
    private static final class ResultLimit extends RuntimeException {}
    private static final class PageTooLarge extends RuntimeException {}
}
