package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.Bitmap;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Offline, worker-thread PDF novel conversion. The input must be a private immutable snapshot. */
public final class PdfEpubExporter {
    public static final int MAX_PAGES = 2000;
    private static final long MAX_SOURCE = 512L * 1024 * 1024;
    private static final int MAX_PAGE_CHARS = 100_000, MAX_TOTAL_CHARS = 8_000_000;
    private static final String CACHE_VERSION = "QY-EPUB-OCR-3-structured\n";
    private static final int OCR_THRESHOLD = 24, LOW_CONFIDENCE = 50;
    public interface Progress { void update(int completed, int total, int pageIndex, String stage); }
    public interface OcrAdapter {
        OcrData recognize(Context context, File snapshotPdf, int pageIndex,
                OcrCancellation cancellation) throws IOException;
    }
    public static final class OcrData {
        public final String text, hocr; public final int confidence;
        public OcrData(String text, int confidence) {
            this(text, confidence, "");
        }
        public OcrData(String text, int confidence, String hocr) {
            this.text = text == null ? "" : text; this.hocr = hocr == null ? "" : hocr;
            this.confidence = Math.max(0, Math.min(100, confidence));
        }
    }
    public static final class Options {
        public final String title, author, documentId, libraryHash;
        public final int fromInclusive, toExclusive;
        public final boolean coverFirstPage;
        public final boolean deskew;
        /** null: automatic discovery; empty: one chapter; nonempty: explicit chapter boundaries. */
        public final List<EpubBook.Heading> chapterOverrides;
        public Options(String title, String author, int fromInclusive, int toExclusive,
                String documentId, String libraryHash, boolean coverFirstPage) {
            this(title,author,fromInclusive,toExclusive,documentId,libraryHash,coverFirstPage,false,null);
        }
        private Options(String title,String author,int fromInclusive,int toExclusive,String documentId,
                String libraryHash,boolean coverFirstPage,boolean deskew,List<EpubBook.Heading> overrides) {
            this.title = title == null ? "" : title.trim(); this.author = author == null ? "" : author.trim();
            this.fromInclusive = fromInclusive; this.toExclusive = toExclusive;
            this.documentId = documentId == null ? "" : documentId;
            this.libraryHash = libraryHash == null ? "" : libraryHash;
            this.coverFirstPage = coverFirstPage;
            this.deskew=deskew;
            this.chapterOverrides=overrides==null?null:Collections.unmodifiableList(new ArrayList<>(overrides));
        }
        public Options withDeskew(boolean value){return new Options(title,author,fromInclusive,toExclusive,
                documentId,libraryHash,coverFirstPage,value,chapterOverrides);}
        public Options withChapterOverrides(List<EpubBook.Heading> value){return new Options(title,author,fromInclusive,toExclusive,
                documentId,libraryHash,coverFirstPage,deskew,value);}
    }
    public static final class ChapterPreview {
        public final String title, excerpt; public final int sourcePage;
        ChapterPreview(String title, String excerpt, int page) {
            this.title = title; this.excerpt = excerpt; this.sourcePage = page;
        }
    }
    public static final class PageWarning {
        public enum Kind { OCR_USED, BLANK, LOW_CONFIDENCE, OCR_FAILED, IMAGE_NOT_PRESERVED,
            IMAGE_FALLBACK, PAGE_IMAGE_PRESERVED, CONFIRMED_BLANK }
        public final int pageIndex, confidence; public final Kind kind; public final String detail;
        PageWarning(int page, Kind kind, int confidence, String detail) {
            this.pageIndex = page; this.kind = kind; this.confidence = confidence; this.detail = detail;
        }
    }
    public static final class Report {
        public final int pages, characters, ocrPages, blankPages, lowConfidencePages, chapters;
        public final List<ChapterPreview> chapterPreviews;
        public final List<PageWarning> warnings;
        Report(int pages, int characters, int ocrPages, int blankPages, int lowConfidencePages,
                List<ChapterPreview> previews, List<PageWarning> warnings) {
            this.pages = pages; this.characters = characters; this.ocrPages = ocrPages;
            this.blankPages = blankPages; this.lowConfidencePages = lowConfidencePages;
            this.chapters = previews.size();
            this.chapterPreviews = Collections.unmodifiableList(new ArrayList<>(previews));
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        }
    }
    private PdfEpubExporter() { }

    public static Report convert(Context context, File immutableSnapshotPdf, File privateTempEpub,
            Options options, OcrCancellation cancellation, Progress progress, OcrAdapter injectedOrNull)
            throws IOException {
        if (context == null || immutableSnapshotPdf == null || !immutableSnapshotPdf.isFile()
                || privateTempEpub == null || options == null) throw new IOException("PDF 转换参数无效");
        if (immutableSnapshotPdf.length() <= 0 || immutableSnapshotPdf.length() > MAX_SOURCE)
            throw new IOException("PDF 超过 512 MB 上限或为空");
        if (options.fromInclusive < 0 || options.toExclusive <= options.fromInclusive
                || options.toExclusive - options.fromInclusive > MAX_PAGES)
            throw new IOException("页码范围超过 2000 页上限");
        if (options.title.isEmpty() || options.title.length() > 512 || options.author.length() > 512)
            throw new IOException("书名或作者无效");
        File cacheRoot = context.getCacheDir().getCanonicalFile();
        File output = privateTempEpub.getCanonicalFile();
        File sourceCanonical=immutableSnapshotPdf.getCanonicalFile();
        if (!within(cacheRoot, output) && !persistentJobPath(context,sourceCanonical,output))
            throw new IOException("EPUB 输出必须位于私有缓存或指定的持久任务目录");
        if (output.equals(immutableSnapshotPdf.getCanonicalFile()) || output.exists())
            throw new IOException("不能覆盖 PDF 或已有 EPUB");
        if (cancellation == null) cancellation = new OcrCancellation();
        final OcrCancellation token = cancellation;
        PDFBoxResourceLoader.init(context.getApplicationContext());
        File scratch = new File(cacheRoot, "pdf-epub-pdfbox");
        if (!scratch.isDirectory() && !scratch.mkdirs()) throw new IOException("无法创建 PDF 缓存");
        String actualHash = sha256(immutableSnapshotPdf, token);
        EpubOcrSession session = injectedOrNull == null ? new EpubOcrSession(options.deskew) : null;
        OcrAdapter adapter = session == null ? injectedOrNull : session;
        List<EpubBook.Page> pages = new ArrayList<>(); List<PageWarning> warnings = new ArrayList<>();
        List<File> imageFiles=new ArrayList<>();long imageBytes=0;
        int ocrPages = 0, blankPages = 0, lowPages = 0, totalChars = 0;
        byte[] cover = null;
        try {
            List<EpubBook.Heading> headings = options.chapterOverrides == null
                    ? loadHeadings(context, immutableSnapshotPdf,options.fromInclusive,options.toExclusive)
                    : options.chapterOverrides;
            try (PDDocument pdf = PDDocument.load(immutableSnapshotPdf,
                    MemoryUsageSetting.setupMixed(16L * 1024 * 1024).setTempDir(scratch))) {
                token.throwIfCancelled();
                if (!pdf.getCurrentAccessPermission().canExtractContent())
                    throw new IOException("此 PDF 限制文字提取");
                if (options.toExclusive > pdf.getNumberOfPages()) throw new IOException("页码范围超出 PDF");
                int total = options.toExclusive - options.fromInclusive;
                List<EpubBook.Page> layers = new PageTextExtractor(options.fromInclusive,
                        options.toExclusive, token, progress).extract(pdf);
                for (int page = options.fromInclusive; page < options.toExclusive; page++) {
                    token.throwIfCancelled();
                    notify(progress, page - options.fromInclusive, total, page, "读取文字层");
                    EpubBook.Page layer=layers.get(page-options.fromInclusive);
                    String text=join(layer.lines);
                    List<EpubBook.Line> lines=layer.lines;
                    EpubBook.Status status=EpubBook.Status.TEXT_LAYER;
                    File pageImage=null;
                    boolean shortLayerOnly=false;
                    if (text.length() > MAX_PAGE_CHARS) throw new IOException("第 " + (page + 1) + " 页文字超过上限");
                    String sidecar = verifiedSidecar(context, options, page, actualHash);
                    if (visibleLength(sidecar) > 0) {text=clean(sidecar);lines=EpubOcrText.plainLines(text);status=EpubBook.Status.SIDECAR;}
                    else if (visibleLength(text) < OCR_THRESHOLD) {
                        String shortLayer = text;
                        notify(progress, page - options.fromInclusive, total, page, "正在识别扫描页（本机 OCR）");
                        OcrData recognized;
                        try {recognized=cachedOrRecognize(context, immutableSnapshotPdf,
                                actualHash,page,adapter,options.deskew,
                                correctionSignature(options,sidecar),token);}
                        catch(IOException failed){token.throwIfCancelled();recognized=new OcrData("",0);
                            warnings.add(new PageWarning(page,PageWarning.Kind.OCR_FAILED,0,failed.getMessage()));}
                        text=clean(recognized.text);ocrPages++;status=EpubBook.Status.OCR;
                        lines=EpubOcrText.structuredLines(text,recognized.hocr,recognized.confidence,
                                layer.width,layer.height);
                        if (visibleLength(text) == 0 && visibleLength(shortLayer) > 0){text=shortLayer;lines=layer.lines;status=EpubBook.Status.TEXT_LAYER;shortLayerOnly=true;}
                        warnings.add(new PageWarning(page, PageWarning.Kind.OCR_USED,
                                recognized.confidence, visibleLength(recognized.text) > 0
                                        ? "此页使用本地 OCR" : "OCR 未得到文字，保留已有短文字层"));
                        if (recognized.confidence < LOW_CONFIDENCE && visibleLength(recognized.text) > 0) {
                            lowPages++; warnings.add(new PageWarning(page,
                                    PageWarning.Kind.LOW_CONFIDENCE, recognized.confidence,
                                    "OCR 识别置信度较低，请核对正文"));
                        }
                    }
                    if (visibleLength(text) == 0 || shortLayerOnly) {
                        PageImage rendered=renderPage(immutableSnapshotPdf,page,token);
                        if(rendered.blank&&isEmptyPageStream(pdf.getPage(page))&&!hasPageArtwork(pdf.getPage(page))){blankPages++;status=EpubBook.Status.CONFIRMED_BLANK;
                            warnings.add(new PageWarning(page,PageWarning.Kind.CONFIRMED_BLANK,-1,"像素采样确认空白页"));}
                        else {status=EpubBook.Status.IMAGE_FALLBACK;
                            imageBytes=checkImageBudget(imageBytes,rendered.jpeg.length,page);
                            pageImage=savePageImage(cacheRoot,rendered.jpeg,imageFiles);
                            warnings.add(new PageWarning(page,PageWarning.Kind.IMAGE_FALLBACK,-1,"OCR 无可用文字，已保留原页图片；不可调整字号"));}
                    } else if (preserveArtwork(pdf.getPage(page),status) || hasRulingGrid(pdf.getPage(page))
                            || looksComplex(lines,layer.width,token)) {
                        PageImage rendered=renderPage(immutableSnapshotPdf,page,token);
                        imageBytes=checkImageBudget(imageBytes,rendered.jpeg.length,page);
                        pageImage=savePageImage(cacheRoot,rendered.jpeg,imageFiles);
                        warnings.add(new PageWarning(page,PageWarning.Kind.PAGE_IMAGE_PRESERVED,-1,
                                "页面含插图或复杂版面，附原页图片供核对；表格未语义重建"));
                    }
                    if (text.length() > MAX_PAGE_CHARS) throw new IOException("第 " + (page + 1) + " 页文字超过上限");
                    totalChars += text.length(); if (totalChars > MAX_TOTAL_CHARS)
                        throw new IOException("所选正文超过 800 万字符上限");
                    pages.add(pageImage==null?new EpubBook.Page(page,layer.width,layer.height,status,lines,(byte[])null)
                            :EpubBook.Page.withImageFile(page,layer.width,layer.height,status,lines,pageImage));
                    notify(progress, page - options.fromInclusive + 1, total, page, "提取完成");
                }
            }
            token.throwIfCancelled();
            if(options.chapterOverrides==null&&headings.isEmpty())headings=printedContents(pages);
            if (options.coverFirstPage) {
                notify(progress, pages.size(), pages.size(), options.fromInclusive, "制作封面");
                Bitmap bitmap = null;
                try {
                    bitmap = OcrImageLoader.renderPdfPreview(immutableSnapshotPdf,
                            options.fromInclusive, token).bitmap;
                    ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)) throw new IOException("封面编码失败");
                    cover = out.toByteArray();
                    if (cover.length > 8_000_000) throw new IOException("封面超过 8 MB 上限");
                } finally { if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); }
            }
            EpubBook book;
            notify(progress,pages.size(),pages.size(),options.toExclusive-1,"整理章节与段落");
            token.throwIfCancelled();
            try { book = EpubBook.fromStructuredPages(options.title,options.author,pages,
                    headings.isEmpty()&&options.chapterOverrides==null?null:headings); }
            catch (IllegalArgumentException empty) { throw new IOException(empty.getMessage(), empty); }
            notify(progress,pages.size(),pages.size(),options.toExclusive-1,"打包 EPUB");
            token.throwIfCancelled();
            EpubWriter.write(book, output, cover, token);
            List<ChapterPreview> previews = new ArrayList<>();
            for (EpubBook.Chapter chapter : book.chapters()) {
                String excerpt = chapter.paragraphs.isEmpty() ? "" : chapter.paragraphs.get(0);
                if (excerpt.length() > 180) excerpt = excerpt.substring(0, 180);
                previews.add(new ChapterPreview(chapter.title, excerpt, chapter.sourcePage));
            }
            return new Report(pages.size(), book.characters(), ocrPages, blankPages, lowPages,
                    previews, warnings);
        } catch (InvalidPasswordException password) {
            output.delete(); throw new IOException("此 PDF 需要密码", password);
        } catch (IOException | RuntimeException failure) {
            output.delete(); throw failure;
        } catch (OutOfMemoryError exhausted) {
            output.delete(); throw new IOException("转换时设备内存不足", exhausted);
        } finally {for(File image:imageFiles)image.delete();if(session!=null)session.close();}
    }
    private static final class PageTextExtractor extends PDFTextStripper {
        final int from, to; final OcrCancellation token; final Progress progress;
        final StringWriter writer = new StringWriter(); final List<EpubBook.Page> pages = new ArrayList<>();
        final List<EpubBook.Line> positioned=new ArrayList<>();
        int glyphs, extractedChars;
        PageTextExtractor(int from, int to, OcrCancellation token, Progress progress) throws IOException {
            this.from = from; this.to = to; this.token = token; this.progress = progress;
            setStartPage(from + 1); setEndPage(to);
            setSortByPosition(true); setShouldSeparateByBeads(false); setParagraphStart("\n\n");
            for (int i = from; i < to; i++) pages.add(null);
        }
        List<EpubBook.Page> extract(PDDocument pdf) throws IOException {
            writeText(pdf, writer);
            for(int i=0;i<pages.size();i++)if(pages.get(i)==null){
                PDPage source=pdf.getPage(from+i);
                pages.set(i,new EpubBook.Page(from+i,source.getCropBox().getWidth(),
                        source.getCropBox().getHeight(),EpubBook.Status.TEXT_LAYER,
                        Collections.<EpubBook.Line>emptyList(),null));
            }
            return pages;
        }
        @Override public void processPage(PDPage page) throws IOException {
            token.throwIfCancelled(); writer.getBuffer().setLength(0); glyphs = 0;positioned.clear();
            try { super.processPage(page); }
            catch (PageTooLarge excessive) {
                token.throwIfCancelled();
                throw new IOException("第 " + getCurrentPageNo() + " 页字形超过上限", excessive);
            }
            String value = clean(writer.toString());
            if (value.length() > MAX_PAGE_CHARS)
                throw new IOException("第 " + getCurrentPageNo() + " 页文字超过上限");
            extractedChars += value.length();
            if (extractedChars > MAX_TOTAL_CHARS) throw new IOException("所选正文超过 800 万字符上限");
            int index = getCurrentPageNo() - 1 - from;
            List<EpubBook.Line> lines=compact(join(positioned)).equals(compact(value))
                    ?new ArrayList<>(positioned):EpubOcrText.plainLines(value);
            if(index>=0&&index<pages.size())pages.set(index,new EpubBook.Page(getCurrentPageNo()-1,
                    page.getCropBox().getWidth(),page.getCropBox().getHeight(),EpubBook.Status.TEXT_LAYER,lines,null));
            PdfEpubExporter.notify(progress, Math.max(0, index + 1), to - from,
                    getCurrentPageNo() - 1, "解析文字层");
        }
        @Override protected void processTextPosition(TextPosition position) {
            if (++glyphs > 200_000 || (glyphs & 255) == 0 && token.isCancelled())
                throw new PageTooLarge();
            super.processTextPosition(position);
        }
        @Override protected void writeString(String text,List<TextPosition> positions) throws IOException {
            super.writeString(text,positions);
            if(text==null||text.trim().isEmpty()||positions==null||positions.isEmpty())return;
            float x0=Float.MAX_VALUE,y0=Float.MAX_VALUE,x1=0,y1=0;
            for(TextPosition p:positions){x0=Math.min(x0,p.getXDirAdj());y0=Math.min(y0,p.getYDirAdj());
                x1=Math.max(x1,p.getXDirAdj()+p.getWidthDirAdj());y1=Math.max(y1,p.getYDirAdj()+p.getHeightDir());}
            if(x1>x0&&y1>y0)positioned.add(new EpubBook.Line(text,x0,y0,x1,y1,-1));
        }
    }
    private static final class PageTooLarge extends RuntimeException { }
    private static String join(List<EpubBook.Line> lines){StringBuilder s=new StringBuilder();
        for(EpubBook.Line l:lines){if(s.length()>0)s.append('\n');s.append(l.text);}return s.toString();}
    private static String compact(String source){StringBuilder s=new StringBuilder();for(int i=0;i<source.length();i++)
        if(!Character.isWhitespace(source.charAt(i)))s.append(source.charAt(i));return s.toString();}
    private static final class PageImage {final byte[] jpeg;final boolean blank;
        PageImage(byte[] jpeg,boolean blank){this.jpeg=jpeg;this.blank=blank;}}
    private static PageImage renderPage(File source,int page,OcrCancellation token)throws IOException{
        Bitmap bitmap=null;try{
            bitmap=OcrImageLoader.renderPdfPage(source,page,token).bitmap;
            if(bitmap==null)throw new IOException("第 "+(page+1)+" 页原图读取失败，已停止导出以免漏页");
            int w=bitmap.getWidth(),h=bitmap.getHeight();boolean blank=true;
            // A strict near-white test: grayscale scans, diagrams and faint ink remain nonblank.
            for(int y=0;y<h&&blank;y+=Math.max(1,h/100))for(int x=0;x<w;x+=Math.max(1,w/100)){
                int pixel=bitmap.getPixel(x,y),r=(pixel>>16)&255,g=(pixel>>8)&255,b=pixel&255;
                if(r<246||g<246||b<246||Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))>5){blank=false;break;}}
            ByteArrayOutputStream out=new ByteArrayOutputStream(256*1024);
            if(!bitmap.compress(Bitmap.CompressFormat.JPEG,78,out))throw new IOException("第 "+(page+1)+" 页原图编码失败");
            byte[] jpeg=out.toByteArray();if(jpeg.length>8_000_000)throw new IOException("第 "+(page+1)+" 页图片超过 8 MB 上限");
            return new PageImage(jpeg,blank);
        }finally{if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
    }
    private static boolean hasPageArtwork(PDPage page){
        try{if(page.getResources()==null)return false;
            for(com.tom_roush.pdfbox.cos.COSName name:page.getResources().getXObjectNames())
            if(page.getResources().getXObject(name) instanceof com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject)return true;
        }catch(IOException|RuntimeException ignored){return true;}return false;
    }
    private static boolean preserveArtwork(PDPage page,EpubBook.Status status){
        if(!hasPageArtwork(page))return false;
        if(status!=EpubBook.Status.OCR)return true;
        try{int images=0;for(com.tom_roush.pdfbox.cos.COSName name:page.getResources().getXObjectNames())
            if(page.getResources().getXObject(name) instanceof com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject)images++;
            // A lone raster behind successful OCR is normally the scan itself.
            return images>1;
        }catch(IOException|RuntimeException failed){return true;}
    }
    private static long checkImageBudget(long current,int size,int page)throws IOException{
        if(size<=0||size>8_000_000)throw new IOException("第 "+(page+1)+" 页原图超过 8 MB 上限");
        long next=current+size;if(next>48L*1024*1024)
            throw new IOException("原页图片累计超过 48 MB 预算，已停止导出以免漏页");
        return next;
    }
    private static File savePageImage(File cacheRoot,byte[] bytes,List<File> created)throws IOException{
        File target=File.createTempFile("pdf-epub-page-",".jpg",cacheRoot);created.add(target);
        try(FileOutputStream out=new FileOutputStream(target)){out.write(bytes);out.getFD().sync();}
        return target;
    }
    private static boolean isEmptyPageStream(PDPage page){
        try(InputStream in=page.getContents()){if(in==null)return true;return in.read()==-1;}
        catch(IOException|RuntimeException failed){return false;}
    }
    private static boolean hasRulingGrid(PDPage page){
        try(InputStream in=page.getContents()){
            if(in==null)return false;ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1){if(bytes.size()+n>2_000_000)return true;bytes.write(buffer,0,n);}
            String ops=new String(bytes.toByteArray(),StandardCharsets.ISO_8859_1);
            java.util.regex.Matcher rectangles=java.util.regex.Pattern.compile("(?:^|\\s)re(?:\\s|$)").matcher(ops);
            int count=0;while(rectangles.find())if(++count>=3)return true;
            java.util.regex.Matcher lines=java.util.regex.Pattern.compile("(?:^|\\s)[ml](?:\\s|$)").matcher(ops);
            count=0;while(lines.find())if(++count>=18)return true;
            return false;
        }catch(IOException|RuntimeException failed){return true;}
    }
    private static boolean looksComplex(List<EpubBook.Line> lines,float width,OcrCancellation token)throws IOException{
        if(width<=0||lines.size()<6)return false;int shortMiddle=0,columns=0;
        if(lines.size()>4096)return true;
        List<EpubBook.Line> positioned=new ArrayList<>();
        for(EpubBook.Line l:lines)if(l.hasGeometry()){
            positioned.add(l);if(l.left>width*.3f&&l.right<width*.75f&&l.text.length()<30)shortMiddle++;}
        Collections.sort(positioned,(a,b)->Float.compare(a.top,b.top));
        for(int i=0;i<positioned.size();i++){
            if((i&255)==0)token.throwIfCancelled();EpubBook.Line a=positioned.get(i);
            for(int j=i+1;j<Math.min(positioned.size(),i+5);j++){
                EpubBook.Line b=positioned.get(j);
                float h=Math.max(a.bottom-a.top,b.bottom-b.top);
                if(b.top-a.top>h*.6f)break;
                if(Math.abs(a.top-b.top)<=h*.6f&&(a.right+width*.03f<b.left||b.right+width*.03f<a.left)){
                    columns++;break;
                }
            }
        }
        return shortMiddle>=Math.max(4,lines.size()/3)||columns>=3;
    }
    private static List<EpubBook.Heading> loadHeadings(Context context, File source,
            int from, int to) throws IOException {
        List<EpubBook.Heading> result = new ArrayList<>();
        PdfNavigationIndex index = PdfNavigationIndex.load(context, source);
        Set<Integer> seen = new HashSet<>();
        PdfNavigationIndex.Item preceding = null;
        for (PdfNavigationIndex.Item item : index.outline) {
            if (item.page < from && (preceding == null || item.page > preceding.page)) preceding = item;
            if (item.page >= from && item.page < to && seen.add(item.page)
                    && item.title.length() <= 100)
                result.add(new EpubBook.Heading(item.title, item.page));
        }
        if (preceding != null && !seen.contains(from) && preceding.title.length() <= 100)
            result.add(0, new EpubBook.Heading(preceding.title, from));
        return result;
    }
    /** Printed contents are accepted only when multiple titles reappear as body lines in order. */
    static List<EpubBook.Heading> printedContents(List<EpubBook.Page> pages){
        if(pages.size()<4)return Collections.emptyList();
        List<String> candidates=new ArrayList<>();int front=Math.min(12,Math.max(2,pages.size()/10));
        for(int i=0;i<front;i++)for(EpubBook.Line line:pages.get(i).lines){
            String raw=line.text.trim();String title=raw.replaceFirst("[.．·…\\s]{2,}[0-9０-９一二三四五六七八九十]+$","").trim();
            if(title.length()>=3&&title.length()<=60&&title.matches("^(第[零〇一二三四五六七八九十百千万两0-9０-９]+[章节回卷部篇集]|序章|楔子|尾声|后记|番外).*$")
                    &&!candidates.contains(title))candidates.add(title);
            if(candidates.size()>=100)break;
        }
        if(candidates.size()<2)return Collections.emptyList();
        List<EpubBook.Heading> found=new ArrayList<>();int next=front;
        for(String title:candidates){
            boolean matched=false;
            for(int p=next;p<pages.size()&&!matched;p++)for(EpubBook.Line line:pages.get(p).lines){
                String value=line.text.trim();if(value.replaceAll("\\s+","").equals(title.replaceAll("\\s+",""))){
                    found.add(new EpubBook.Heading(title,pages.get(p).sourcePage,value));next=p+1;matched=true;break;}
            }
        }
        return found.size()>=2?found:Collections.<EpubBook.Heading>emptyList();
    }
    private static String verifiedSidecar(Context context, Options options, int page,
            String actualHash) throws IOException {
        if (!options.documentId.matches("[0-9a-fA-F-]{36}")
                || !options.libraryHash.matches("[0-9a-fA-F]{64}")) return "";
        return OcrSidecar.loadForPdf(context, options.documentId, page,
                options.libraryHash, actualHash, false);
    }
    private static OcrData cachedOrRecognize(Context context, File snapshot, String hash,
            int page, OcrAdapter adapter, boolean deskew,String correctionSignature,
            OcrCancellation token) throws IOException {
        File dir = new File(context.getCacheDir(), "pdf-epub-ocr/" + hash);
        File target = new File(dir, "page-" + page + ".bin");
        String config=(adapter instanceof EpubOcrSession?EpubOcrSession.cacheKey(deskew):"injected-ocr-v1")
                +":correction="+correctionSignature;
        if (target.isFile() && target.length() <= 9_000_000L) {
            try {
                byte[] bytes=readBounded(target,9_000_000,token);
                DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
                if(in.readInt()==0x45504233&&CACHE_VERSION.equals(in.readUTF())
                        &&hash.equals(in.readUTF())&&page==in.readInt()&&config.equals(in.readUTF())){
                    int confidence=in.readInt(),textLength=in.readInt(),hocrLength=in.readInt();
                    if(confidence>=0&&confidence<=100&&textLength>=0&&textLength<=MAX_PAGE_CHARS*4
                            &&hocrLength>=0&&hocrLength<=8_000_000&&textLength+hocrLength<=in.available()-32){
                        byte[] textBytes=new byte[textLength],hocrBytes=new byte[hocrLength];
                        in.readFully(textBytes);in.readFully(hocrBytes);byte[] signature=new byte[32];in.readFully(signature);
                        if(in.available()==0&&Arrays.equals(signature,digest(textBytes,hocrBytes,confidence)))
                            {target.setLastModified(System.currentTimeMillis());
                            return new OcrData(new String(textBytes,StandardCharsets.UTF_8),confidence,
                                    new String(hocrBytes,StandardCharsets.UTF_8));}
                    }
                }
            } catch (IOException|RuntimeException damaged) { token.throwIfCancelled(); /* Recompute damaged cache. */ }
        }
        token.throwIfCancelled();
        OcrData recognized = adapter.recognize(context, snapshot, page, token);
        token.throwIfCancelled();
        if (recognized == null || recognized.text.length() > MAX_PAGE_CHARS)
            throw new IOException("第 " + (page + 1) + " 页 OCR 结果无效或过长");
        recognized = new OcrData(EpubOcrText.reflowHints(recognized.text, recognized.hocr),
                recognized.confidence,recognized.hocr);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建 OCR 缓存");
        File temp = File.createTempFile("page-", ".tmp", dir);
        boolean moved = false;
        try {
            byte[] textBytes=recognized.text.getBytes(StandardCharsets.UTF_8),hocrBytes=recognized.hocr.getBytes(StandardCharsets.UTF_8);
            if(textBytes.length>MAX_PAGE_CHARS*4||hocrBytes.length>8_000_000)throw new IOException("OCR 缓存超过页面上限");
            try(FileOutputStream out=new FileOutputStream(temp);DataOutputStream data=new DataOutputStream(out)){
                data.writeInt(0x45504233);data.writeUTF(CACHE_VERSION);data.writeUTF(hash);data.writeInt(page);
                data.writeUTF(config);data.writeInt(recognized.confidence);data.writeInt(textBytes.length);
                data.writeInt(hocrBytes.length);data.write(textBytes);data.write(hocrBytes);
                data.write(digest(textBytes,hocrBytes,recognized.confidence));data.flush();out.getFD().sync();token.throwIfCancelled();
            }
            synchronized (PdfEpubExporter.class) {
                if (!temp.renameTo(target)){
                    try{java.nio.file.Files.move(temp.toPath(),target.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
                    catch(IOException failed){throw new IOException("无法原子更新 OCR 缓存",failed);}
                }
                pruneCache(new File(context.getCacheDir(),"pdf-epub-ocr"),target,token);
            }
            moved = true;
        } finally { if (!moved) temp.delete(); }
        return recognized;
    }
    private static void pruneCache(File root,File keep,OcrCancellation token)throws IOException{
        File[] sources=root.listFiles();if(sources==null)return;
        List<File> files=new ArrayList<>();long total=0;
        for(File source:sources){token.throwIfCancelled();
            if(!source.isDirectory()||!source.getName().matches("[0-9a-f]{64}"))continue;
            File[] pages=source.listFiles();if(pages==null)continue;
            for(File page:pages)if(page.isFile()&&page.getName().matches("page-[0-9]+\\.bin")){
                total+=page.length();files.add(page);
                if(files.size()>30_000)throw new IOException("OCR 缓存文件过多，请清理应用缓存");
            }
        }
        if(total<=128L*1024*1024)return;
        Collections.sort(files,(a,b)->Long.compare(a.lastModified(),b.lastModified()));
        for(File file:files){token.throwIfCancelled();if(total<=128L*1024*1024)break;
            if(file.equals(keep))continue;long size=file.length();
            if(!file.delete())throw new IOException("无法限制 OCR 缓存占用");total-=size;
        }
        if(total>128L*1024*1024)throw new IOException("OCR 缓存超过 128 MB 限额");
    }
    private static byte[] digest(byte[] first,byte[] second){try{
        MessageDigest hash=MessageDigest.getInstance("SHA-256");hash.update(first);hash.update(second);return hash.digest();
    }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static byte[] digest(byte[] first,byte[] second,int confidence){try{
        MessageDigest hash=MessageDigest.getInstance("SHA-256");hash.update((byte)confidence);
        hash.update(first);hash.update(second);return hash.digest();
    }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static String correctionSignature(Options options,String sidecar){
        byte[] bytes=(options.documentId+"\n"+options.libraryHash+"\n"+sidecar).getBytes(StandardCharsets.UTF_8);
        byte[] value=digest(bytes,new byte[0]);StringBuilder out=new StringBuilder(64);
        for(byte b:value)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static byte[] readBounded(File file, int limit, OcrCancellation token) throws IOException {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192]; int n;
            while ((n = in.read(block)) != -1) {
                token.throwIfCancelled(); if (out.size() + n > limit) throw new IOException("OCR 缓存过大");
                out.write(block, 0, n);
            }
            return out.toByteArray();
        }
    }
    private static String clean(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            int cp = value.codePointAt(i); if (cp > 0xffff) i++;
            if (cp == 9 || cp == 10 || cp == 13 || (cp >= 32 && cp <= 0xd7ff)
                    || (cp >= 0xe000 && cp <= 0xfffd) || (cp >= 0x10000 && cp <= 0x10ffff)) out.appendCodePoint(cp);
        }
        return out.toString().replace("\r\n", "\n").replace('\r', '\n').trim();
    }
    private static int visibleLength(String text) {
        int count = 0; for (int i = 0; i < text.length(); i++) if (!Character.isWhitespace(text.charAt(i))) count++;
        return count;
    }
    private static String sha256(File file, OcrCancellation token) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] block = new byte[65536]; int n;
                while ((n = in.read(block)) != -1) { token.throwIfCancelled(); digest.update(block, 0, n); }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static boolean within(File root, File file) {
        try { return file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator); }
        catch (IOException error) { return false; }
    }
    private static boolean persistentJobPath(Context context,File source,File output)throws IOException{
        File jobs=new File(context.getFilesDir().getCanonicalFile(),"epub-jobs").getCanonicalFile();
        File dir=output.getParentFile();if(dir==null||!dir.getParentFile().equals(jobs))return false;
        if(!dir.getName().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))return false;
        return output.equals(new File(dir,"book.epub").getCanonicalFile())
                &&source.equals(new File(dir,"source.pdf").getCanonicalFile());
    }
    private static void notify(Progress progress, int completed, int total, int page, String stage) {
        if (progress != null) progress.update(completed, total, page, stage);
    }
}
