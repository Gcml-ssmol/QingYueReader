package cn.qingyue.pdf;

import android.content.Context;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

/** Synthetic real PDFBox round trip; desktop OCR is injected, not a device test. */
public final class PdfEpubTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        File root = new File(System.getProperty("smoke.root")); root.mkdirs();
        File cache = new File(root, "cache"); cache.mkdirs();
        PDFBoxResourceLoader.init(new Context());
        File source = new File(root, "synthetic.pdf");
        try (PDDocument pdf = new PDDocument()) {
            for (int i = 0; i < 3; i++) pdf.addPage(new PDPage());
            PDDocumentOutline outline = new PDDocumentOutline();
            pdf.getDocumentCatalog().setDocumentOutline(outline);
            PDOutlineItem chapter = new PDOutlineItem(); chapter.setTitle("合成章节 & 一");
            chapter.setDestination(pdf.getPage(0)); outline.addLast(chapter);
            try (PDPageContentStream stream = new PDPageContentStream(pdf, pdf.getPage(0))) {
                stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(60, 700); stream.showText("Hello synthetic chapter with enough text."); stream.endText();
            }
            pdf.save(source);
        }
        byte[] original = Files.readAllBytes(source.toPath());
        PdfEpubExporter.Options options = new PdfEpubExporter.Options("题<目", "作&者", 0, 3,
                "", "", false);
        int[] calls = {0};
        PdfEpubExporter.OcrAdapter injected = (c, f, p, token) -> {
            calls[0]++;
            return p == 1 ? new PdfEpubExporter.OcrData("第二页中文小说。\n下一句延续。", 87)
                    : new PdfEpubExporter.OcrData("", 12);
        };
        File output = new File(cache, "synthetic.epub");
        List<String> stages=new ArrayList<>();
        PdfEpubExporter.Report report = PdfEpubExporter.convert(new Context(), source, output,
                options, new OcrCancellation(), (done,total,page,stage)->{
                    if(stage.equals("整理章节与段落")||stage.equals("打包 EPUB")){
                        check(done==3&&total==3&&page==2,"post-extraction progress counts changed");
                        stages.add(stage);
                    }
                }, injected);
        check(stages.equals(Arrays.asList("整理章节与段落","打包 EPUB")),"reflow/package stages missing or out of order");
        check(report.pages == 3 && report.ocrPages == 2 && report.blankPages == 1,
                "report pages/OCR/blank");
        check(report.chapters == 1 && report.chapterPreviews.get(0).title.equals("合成章节 & 一"),
                "PDF outline not used");
        check(calls[0] == 2, "unexpected OCR count");
        check(Arrays.equals(original, Files.readAllBytes(source.toPath())), "source modified");
        check(Files.readAllBytes(output.toPath()).length > 0, "EPUB missing");
        validateZip(output, false);
        File retry = new File(cache, "retry.epub");
        PdfEpubExporter.convert(new Context(), source, retry, options, new OcrCancellation(), null,
                (c, f, p, token) -> { throw new IOException("OCR cache was missed"); });
        check(calls[0] == 2, "cache retry called OCR");
        OcrSidecar.value = "短校订";
        File corrected = new File(cache, "corrected.epub");
        PdfEpubExporter.Report correctedReport = PdfEpubExporter.convert(new Context(), source,
                corrected, new PdfEpubExporter.Options("校订", "", 1, 2,
                        "00000000-0000-0000-0000-000000000001",
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", false),
                new OcrCancellation(), null,
                (c, f, p, t) -> { throw new IOException("verified short correction was ignored"); });
        check(correctedReport.ocrPages == 0 && correctedReport.blankPages == 0,
                "verified short sidecar replaced by OCR");
        try (ZipFile zip = new ZipFile(corrected)) {
            check(text(zip, "OEBPS/chapter-0001.xhtml").contains("短校订"),
                    "short sidecar missing from EPUB");
        }
        check(correctedReport.chapterPreviews.get(0).title.equals("合成章节 & 一"),
                "partial range did not inherit preceding bookmark");
        OcrSidecar.value = "";
        File cancelled = new File(cache, "cancelled.epub");
        OcrCancellation token = new OcrCancellation();
        try {
            PdfEpubExporter.convert(new Context(), source, cancelled, options, token,
                    (done, total, page, stage) -> { if (done >= 1) token.cancel(); }, injected);
            throw new AssertionError("cancelled conversion succeeded");
        } catch (InterruptedIOException expected) { check(!cancelled.exists(), "cancel left EPUB"); }
        EpubBook novel = EpubBook.fromPages("书<名", "作&者", Arrays.asList(
                "1\n第一行汉字接续\n跨页", "续句。\n\n第二段。\n12"), 0);
        check(!novel.chapters().get(0).paragraphs.get(0).contains("跨页续句"), "unlocated cross-page lines merged");
        check(novel.chapters().get(0).paragraphs.toString().contains("12"), "numeric section lost");
        EpubBook indented = EpubBook.fromPages("缩进", "", Arrays.asList(
                "第一段跨\n行中\n  第二段起\n续行。"), 0);
        check(indented.chapters().get(0).paragraphs.size() == 2,
                "indented paragraph merged with previous paragraph");
        check(indented.author().isEmpty(), "blank author was replaced");
        EpubBook repeatedDialogue = EpubBook.fromPages("对白", "", Arrays.asList(
                "他说。\n甲的正文。", "他说。\n乙的正文。", "他说。\n丙的正文。"), 0);
        check(repeatedDialogue.chapters().get(0).paragraphs.toString().contains("他说。"),
                "repeated dialogue removed as header");
        String hocr = "<div class='ocr_page' title='bbox 0 0 1000 1000'>"
                + "<span class='ocrx_word' title='bbox 100 100 300 120'>普通一行文字</span>"
                + "<span class='ocrx_word' title='bbox 140 140 360 160'>缩进第二段文字</span>"
                + "</div>";
        check(EpubOcrText.reflowHints("普通一行文字\n缩进第二段文字", hocr)
                .contains("\n  缩进第二段文字"), "hOCR indentation missing");
        check(EpubOcrText.reflowHints("普通一行文字\n缩进第二段文字\n正文不能丢", hocr)
                .contains("正文不能丢"), "partial hOCR replaced complete OCR text");
        EpubBook.Line scaled=EpubOcrText.structuredLines("普通一行文字\n缩进第二段文字",hocr,80,500,800).get(0);
        check(Math.abs(scaled.left-50)<.01f&&Math.abs(scaled.top-80)<.01f,"hOCR pixels not normalized to PDF points");
        check(EpubBook.fromPages("小节", "", Arrays.asList("1"), 0)
                .chapters().get(0).paragraphs.contains("1"), "single-line section number discarded");
        File withCover = new File(cache, "synthetic-cover.epub");
        BufferedImage image = new BufferedImage(24, 32, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream(); ImageIO.write(image, "jpg", jpeg);
        EpubWriter.write(novel, withCover, jpeg.toByteArray(), new OcrCancellation());
        validateZip(withCover, true);
        structuredReflow();
        imageFallbackAndCacheDamage(root,cache);
        System.out.println("PdfEpubTest passed; synthetic EPUBs: " + output + " and " + withCover);
    }
    static void structuredReflow() throws Exception {
        List<EpubBook.Page> pages=Arrays.asList(
                page(0,"同页第一句。",20,700,480,722,"句号以后仍续写",20,730,480,752,
                        "跨页",20,760,490,782),
                page(1,"续句完成。",20,24,480,46,"“你好吗？”",20,70,400,92,
                        "“很好。”",20,110,400,132));
        EpubBook book=EpubBook.fromStructuredPages("重排","",pages,Collections.<EpubBook.Heading>emptyList());
        List<String> paras=book.chapters().get(0).paragraphs;
        check(paras.get(0).contains("同页第一句。句号以后仍续写跨页续句完成。"),"sentence or cross-page reflow failed");
        check(paras.size()==3&&paras.get(1).startsWith("“你好吗"),"dialogue boundaries lost");
        List<EpubBook.Page> numbered=Arrays.asList(
                page(0,"奇偶页眉",10,10,80,25,"12",20,350,60,370,"正文甲",20,500,450,525),
                page(1,"奇偶页眉",10,10,80,25,"正文乙",20,500,450,525),
                page(2,"奇偶页眉",10,10,80,25,"正文丙",20,500,450,525));
        EpubBook filtered=EpubBook.fromStructuredPages("页眉","",numbered,Collections.<EpubBook.Heading>emptyList());
        check(!filtered.chapters().get(0).paragraphs.toString().contains("奇偶页眉"),"repeated positioned header retained");
        check(filtered.chapters().get(0).paragraphs.toString().contains("12"),"numeric section removed");
        List<EpubBook.Page> numberedFooters=Arrays.asList(
                page(0,"正文甲",20,300,450,325,"1",245,760,255,780),
                page(1,"正文乙",20,300,450,325,"2",245,760,255,780),
                page(2,"正文丙",20,300,450,325,"3",245,760,255,780));
        EpubBook noFooters=EpubBook.fromStructuredPages("变动页码","",numberedFooters,Collections.<EpubBook.Heading>emptyList());
        check(!noFooters.chapters().get(0).paragraphs.toString().matches(".*[123].*"),"changing bottom folios retained");
        EpubBook overridden=EpubBook.fromStructuredPages("覆盖","",Arrays.asList(
                page(0,"前言",20,100,120,125,"第二章",20,160,120,185,"正文",20,220,180,245)),
                Arrays.asList(new EpubBook.Heading("改名章节",0,"第二章")));
        check(overridden.chapters().size()==2&&overridden.chapters().get(1).title.equals("改名章节"),"in-page override missed");
        EpubWriter.write(overridden,new File(System.getProperty("smoke.root"),"cache/in-page-chapters.epub"),
                null,new OcrCancellation());
        EpubBook boundary=EpubBook.fromStructuredPages("边界","",pages,
                Arrays.asList(new EpubBook.Heading("重启章节",1,"")));
        check(boundary.chapters().size()==2&&boundary.chapters().get(1).title.equals("重启章节"),"blank restored anchor not normalized");
        EpubWriter.write(boundary,new File(System.getProperty("smoke.root"),"cache/manual-anchor.epub"),
                null,new OcrCancellation());
        check(EpubBook.fromStructuredPages("单章","",pages,Collections.<EpubBook.Heading>emptyList()).chapters().size()==1,
                "empty override did not force one chapter");
        List<EpubBook.Page> contents=Arrays.asList(
                page(0,"第一章 开始 …… 3",20,100,300,125,"第二章 继续 …… 4",20,140,300,165),
                page(1,"目录续页",20,100,300,125),
                page(2,"第一章 开始",20,100,300,125,"正文",20,140,400,165),
                page(3,"第二章 继续",20,100,300,125,"正文",20,140,400,165),
                page(4,"尾页",20,100,300,125));
        List<EpubBook.Heading> inferred=PdfEpubExporter.printedContents(contents);
        check(inferred.size()==2&&inferred.get(1).sourcePage==3,"printed TOC not matched to body");
        check(PdfEpubExporter.printedContents(contents.subList(0,3)).isEmpty(),"unverified printed TOC accepted");
    }
    static EpubBook.Page page(int index,Object... cells){
        List<EpubBook.Line> lines=new ArrayList<>();for(int i=0;i<cells.length;i+=5)
            lines.add(new EpubBook.Line((String)cells[i],((Number)cells[i+1]).floatValue(),
                    ((Number)cells[i+2]).floatValue(),((Number)cells[i+3]).floatValue(),
                    ((Number)cells[i+4]).floatValue(),88));
        return new EpubBook.Page(index,500,800,EpubBook.Status.OCR,lines,null);
    }
    static void imageFallbackAndCacheDamage(File root,File cache) throws Exception {
        File source=new File(root,"unrecognized-content.pdf");
        try(PDDocument pdf=new PDDocument()){
            PDPage p=new PDPage();pdf.addPage(p);
            try(PDPageContentStream stream=new PDPageContentStream(pdf,p)){
                stream.beginText();stream.setFont(PDType1Font.HELVETICA,12);
                stream.newLineAtOffset(60,700);stream.showText("Hi");stream.endText();}
            pdf.save(source);
        }
        PdfEpubExporter.Options options=new PdfEpubExporter.Options("保底","",0,1,"","",false);
        int[] calls={0};PdfEpubExporter.OcrAdapter noText=(c,f,p,t)->{calls[0]++;return new PdfEpubExporter.OcrData("",0);};
        File output=new File(cache,"image-fallback.epub");
        PdfEpubExporter.Report result=PdfEpubExporter.convert(new Context(),source,output,options,new OcrCancellation(),null,noText);
        check(result.blankPages==0&&result.warnings.stream().anyMatch(w->w.kind==PdfEpubExporter.PageWarning.Kind.IMAGE_FALLBACK),
                "nonblank OCR failure was omitted");
        try(ZipFile zip=new ZipFile(output)){
            check(zip.getEntry("OEBPS/page-0001.jpg")!=null,"page image absent from ZIP");
            check(text(zip,"OEBPS/content.opf").contains("page-0001.jpg"),"page image missing from OPF");
            check(text(zip,"OEBPS/nav.xhtml").contains("page-list"),"page-list absent");
            check(text(zip,"OEBPS/chapter-0001.xhtml").contains("不能重排"),"fallback label absent");
        }
        String hash=sha256(source);File cacheFile=new File(cache,"pdf-epub-ocr/"+hash+"/page-0.bin");
        check(cacheFile.isFile(),"structured OCR cache missing");Files.write(cacheFile.toPath(),new byte[]{1,2,3});
        File second=new File(cache,"image-fallback-retry.epub");
        PdfEpubExporter.convert(new Context(),source,second,options,new OcrCancellation(),null,noText);
        check(calls[0]==2,"damaged OCR cache was trusted");
        File persistentDir=new File(new File(new File(root,"files"),"epub-jobs"),UUID.randomUUID().toString());
        check(persistentDir.mkdirs(),"persistent task directory");
        File persistentSource=new File(persistentDir,"source.pdf");Files.copy(source.toPath(),persistentSource.toPath());
        File persistentOutput=new File(persistentDir,"book.epub");
        PdfEpubExporter.convert(new Context(),persistentSource,persistentOutput,options,new OcrCancellation(),null,noText);
        check(persistentOutput.isFile(),"persistent EPUB path rejected");
        File table=new File(root,"vector-table.pdf");
        try(PDDocument pdf=new PDDocument()){
            PDPage p=new PDPage();pdf.addPage(p);
            try(PDPageContentStream stream=new PDPageContentStream(pdf,p)){
                stream.beginText();stream.setFont(PDType1Font.HELVETICA,12);stream.newLineAtOffset(60,700);
                stream.showText("Long enough text layer beside a vector table.");stream.endText();
                for(int i=0;i<3;i++){stream.addRect(60,500+i*20,200,18);stream.stroke();}
            }
            pdf.save(table);
        }
        File tableOutput=new File(cache,"table.epub");
        PdfEpubExporter.Report tableReport=PdfEpubExporter.convert(new Context(),table,tableOutput,options,
                new OcrCancellation(),null,(c,f,p,t)->{throw new IOException("table should use text layer");});
        check(tableReport.warnings.stream().anyMatch(w->w.kind==PdfEpubExporter.PageWarning.Kind.PAGE_IMAGE_PRESERVED),
                "vector table page did not retain original image");
        try(ZipFile zip=new ZipFile(tableOutput)){
            check(zip.getEntry("OEBPS/page-0001.jpg")!=null,"vector table image not packaged");
            check(text(zip,"OEBPS/chapter-0001.xhtml").contains("复杂版面")
                    &&!text(zip,"OEBPS/chapter-0001.xhtml").contains("<img"),
                    "complex-page image has no reader label");
            check(text(zip,"OEBPS/source-pages.xhtml").contains("<img"),"complex-page image absent from appendix");
        }
        File scan=new File(root,"scanned-background.pdf");
        try(PDDocument pdf=new PDDocument()){
            PDPage p=new PDPage();pdf.addPage(p);
            BufferedImage raster=new BufferedImage(20,20,BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream jpg=new ByteArrayOutputStream();ImageIO.write(raster,"jpg",jpg);
            com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject scanImage=
                    com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromByteArray(pdf,jpg.toByteArray());
            try(PDPageContentStream stream=new PDPageContentStream(pdf,p)){
                stream.drawImage(scanImage,0,0,p.getMediaBox().getWidth(),p.getMediaBox().getHeight());}
            pdf.save(scan);
        }
        File scanOutput=new File(cache,"scan-reflow.epub");
        PdfEpubExporter.convert(new Context(),scan,scanOutput,options,new OcrCancellation(),null,
                (c,f,p,t)->new PdfEpubExporter.OcrData("扫描页识别成功，应保留可重排的中文正文。",88));
        try(ZipFile zip=new ZipFile(scanOutput)){
            check(zip.getEntry("OEBPS/page-0001.jpg")==null,"successful scanned page duplicated as whole-page image");
            check(text(zip,"OEBPS/chapter-0001.xhtml").contains("扫描页识别成功"),"scanned OCR text missing");
        }
        byte[] large=new byte[8_000_000];List<EpubBook.Page> oversized=new ArrayList<>();
        for(int i=0;i<9;i++)oversized.add(new EpubBook.Page(i,500,800,EpubBook.Status.IMAGE_FALLBACK,
                Collections.<EpubBook.Line>emptyList(),large));
        EpubBook tooLarge=EpubBook.fromStructuredPages("预算","",oversized,Collections.<EpubBook.Heading>emptyList());
        File rejected=new File(cache,"oversized.epub");
        try{EpubWriter.write(tooLarge,rejected,null,new OcrCancellation());
            throw new AssertionError("image budget did not fail");}
        catch(IOException expected){check(!rejected.exists(),"budget failure left partial EPUB");}
    }
    static String sha256(File file)throws Exception{
        java.security.MessageDigest hash=java.security.MessageDigest.getInstance("SHA-256");
        hash.update(Files.readAllBytes(file.toPath()));StringBuilder out=new StringBuilder();
        for(byte b:hash.digest())out.append(String.format("%02x",b&255));return out.toString();
    }
    static void validateZip(File file, boolean cover) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            ZipEntry first = entries.nextElement();
            check(first.getName().equals("mimetype") && first.getMethod() == ZipEntry.STORED,
                    "mimetype order/compression");
            check(text(zip, "mimetype").equals("application/epub+zip"), "mimetype content");
            for (String path : new String[]{"META-INF/container.xml", "OEBPS/content.opf",
                    "OEBPS/nav.xhtml", "OEBPS/toc.ncx", "OEBPS/chapter-0001.xhtml"}) {
                check(zip.getEntry(path) != null, "missing " + path);
                if (path.endsWith("xml") || path.endsWith("xhtml"))
                    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(zip.getInputStream(zip.getEntry(path)));
            }
            check(text(zip, "OEBPS/content.opf").contains("题&lt;目") || cover,
                    "title not escaped");
            check((zip.getEntry("OEBPS/cover.jpg") != null) == cover, "cover toggle");
        }
    }
    static String text(ZipFile zip, String name) throws Exception {
        try (InputStream in = zip.getInputStream(zip.getEntry(name));
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] block = new byte[4096]; int n;
            while ((n = in.read(block)) >= 0) out.write(block, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
