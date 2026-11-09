package cn.qingyue.pdf;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** EPUB 3.0 ZIP writer with EPUB 2 NCX compatibility. */
public final class EpubWriter {
    private static final long MAX_OUTPUT = 64L * 1024 * 1024;
    private EpubWriter() { }
    public static void write(EpubBook book, File destination, byte[] coverJpeg,
            OcrCancellation cancellation) throws IOException {
        if (book == null || destination == null) throw new IOException("电子书输出无效");
        if (coverJpeg != null && coverJpeg.length > 8_000_000) throw new IOException("封面超过 8 MB");
        long imageBudget=coverJpeg==null?0:coverJpeg.length;
        for(EpubBook.Page p:book.pages())if(p.imageJpeg!=null||p.imageFile!=null){
            long size=p.imageJpeg!=null?p.imageJpeg.length:p.imageFile.length();
            if(size<=0||size>8_000_000)throw new IOException("第 "+(p.sourcePage+1)+" 页图片无效或超过 8 MB");
            imageBudget+=size;
        }
        if(imageBudget>MAX_OUTPUT)throw new IOException("原页图片超过 EPUB 64 MB 预算，已停止导出以免漏页");
        if (destination.exists()) throw new IOException("临时 EPUB 已存在");
        String id = "urn:uuid:" + UUID.randomUUID();
        boolean done = false;
        try (FileOutputStream file = new FileOutputStream(destination);
             ZipOutputStream zip = new ZipOutputStream(file)) {
            byte[] mime = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            ZipEntry first = new ZipEntry("mimetype"); first.setMethod(ZipEntry.STORED);
            first.setSize(mime.length); first.setCompressedSize(mime.length);
            CRC32 crc = new CRC32(); crc.update(mime); first.setCrc(crc.getValue());
            zip.putNextEntry(first); zip.write(mime); zip.closeEntry();
            put(zip, "META-INF/container.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles>"
                    + "<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>"
                    + "</rootfiles></container>", cancellation);
            put(zip, "OEBPS/styles.css", "html{font-family:serif}body{line-height:1.7;margin:5%}"
                    + "p{text-indent:2em;margin:0 0 .7em;orphans:2;widows:2}h1{text-align:center;line-height:1.4;page-break-after:avoid}"
                    + "img.cover,img.source{display:block;max-width:100%;height:auto;margin:auto}"
                    + "figure.source{margin:1em 0;text-align:center}figure.source figcaption{font-size:.8em}"
                    + "p.source-ref{text-indent:0;font-size:.85em}"
                    + ".pagebreak{display:block;height:0}p.blank{color:#777;text-align:center;text-indent:0}", cancellation);
            if (coverJpeg != null) put(zip, "OEBPS/cover.jpg", coverJpeg, cancellation);
            StringBuilder manifest = new StringBuilder(), spine = new StringBuilder();
            Map<Integer,String> pageLinks=new LinkedHashMap<>();Set<Integer> emittedMarks=new HashSet<>();
            for(EpubBook.Page sourcePage:book.pages())if(sourcePage.imageJpeg!=null||sourcePage.imageFile!=null){
                String image=String.format(java.util.Locale.ROOT,"page-%04d.jpg",sourcePage.sourcePage+1);
                if(sourcePage.imageFile!=null)put(zip,"OEBPS/"+image,sourcePage.imageFile,cancellation);
                else put(zip,"OEBPS/"+image,sourcePage.imageJpeg,cancellation);
                manifest.append("<item id=\"pimg").append(sourcePage.sourcePage).append("\" href=\"")
                        .append(image).append("\" media-type=\"image/jpeg\"/>");
            }
            StringBuilder nav = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"zh-CN\">"
                    + "<head><title>目录</title></head><body><nav epub:type=\"toc\" id=\"toc\"><h1>目录</h1><ol>");
            StringBuilder ncx = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\"><head>"
                    + "<meta name=\"dtb:uid\" content=\"" + id + "\"/></head><docTitle><text>"
                    + xml(book.title()) + "</text></docTitle><navMap>");
            if (coverJpeg != null) {
                put(zip, "OEBPS/cover.xhtml", page("封面", "<img class=\"cover\" src=\"cover.jpg\" alt=\"封面\"/>"), cancellation);
                manifest.append("<item id=\"coverimage\" href=\"cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>")
                        .append("<item id=\"coverpage\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>");
                spine.append("<itemref idref=\"coverpage\"/>");
            }
            int index = 0;
            for (EpubBook.Chapter chapter : book.chapters()) {
                check(cancellation);
                String filename = String.format(java.util.Locale.ROOT, "chapter-%04d.xhtml", ++index);
                StringBuilder body = new StringBuilder("<h1>").append(xml(chapter.title)).append("</h1>");
                for(EpubBook.Element element:chapter.elements){
                    switch(element.kind){
                        case PAGE_MARK:
                            if(emittedMarks.add(element.sourcePage)){
                                String anchor="page-"+(element.sourcePage+1);
                                body.append("<span epub:type=\"pagebreak\" class=\"pagebreak\" id=\"")
                                        .append(anchor).append("\" title=\"").append(element.sourcePage+1).append("\"/>");
                                pageLinks.put(element.sourcePage,filename+"#"+anchor);
                            }break;
                        case PARAGRAPH:body.append("<p>").append(xml(element.text)).append("</p>");break;
                        case BLANK:body.append("<p class=\"blank\">已确认空白页</p>");break;
                        case IMAGE:
                            if(element.status==EpubBook.Status.IMAGE_FALLBACK){
                                body.append("<figure class=\"source\"><img class=\"source\" src=\"")
                                        .append(String.format(java.util.Locale.ROOT,"page-%04d.jpg",element.sourcePage+1))
                                        .append("\" alt=\"PDF 原页 ").append(element.sourcePage+1)
                                        .append("\"/><figcaption>").append(xml(element.text)).append("</figcaption></figure>");
                            }else{
                                body.append("<p class=\"source-ref\"><a href=\"source-pages.xhtml#source-page-")
                                        .append(element.sourcePage+1).append("\">查看第 ").append(element.sourcePage+1)
                                        .append(" 页原图（插图或复杂版面）</a></p>");
                            }break;
                    }
                }
                put(zip, "OEBPS/" + filename, page(chapter.title, body.toString()), cancellation);
                manifest.append("<item id=\"c").append(index).append("\" href=\"").append(filename)
                        .append("\" media-type=\"application/xhtml+xml\"/>");
                spine.append("<itemref idref=\"c").append(index).append("\"/>");
                nav.append("<li><a href=\"").append(filename).append("\">").append(xml(chapter.title)).append("</a></li>");
                ncx.append("<navPoint id=\"n").append(index).append("\" playOrder=\"").append(index)
                        .append("\"><navLabel><text>").append(xml(chapter.title)).append("</text></navLabel><content src=\"")
                        .append(filename).append("\"/></navPoint>");
                if (destination.length() > MAX_OUTPUT) throw new IOException("EPUB 超过 64 MB 上限");
            }
            StringBuilder appendix=new StringBuilder();
            for(EpubBook.Page sourcePage:book.pages())if((sourcePage.imageJpeg!=null||sourcePage.imageFile!=null)
                    &&sourcePage.status!=EpubBook.Status.IMAGE_FALLBACK){
                appendix.append("<figure class=\"source\" id=\"source-page-")
                        .append(sourcePage.sourcePage+1).append("\"><img class=\"source\" src=\"")
                        .append(String.format(java.util.Locale.ROOT,"page-%04d.jpg",sourcePage.sourcePage+1))
                        .append("\" alt=\"PDF 原页 ").append(sourcePage.sourcePage+1)
                        .append("\"/><figcaption>PDF 第 ").append(sourcePage.sourcePage+1)
                        .append(" 页原图；供核对插图或复杂版面，不能重排，表格未语义重建</figcaption></figure>");
            }
            if(appendix.length()>0){
                put(zip,"OEBPS/source-pages.xhtml",page("原页图附录","<h1>原页图附录</h1>"+appendix),cancellation);
                manifest.append("<item id=\"sourcepages\" href=\"source-pages.xhtml\" media-type=\"application/xhtml+xml\"/>");
                spine.append("<itemref idref=\"sourcepages\"/>");
                nav.append("<li><a href=\"source-pages.xhtml\">原页图附录</a></li>");
                ncx.append("<navPoint id=\"sourcepages\" playOrder=\"").append(index+1)
                        .append("\"><navLabel><text>原页图附录</text></navLabel><content src=\"source-pages.xhtml\"/></navPoint>");
            }
            nav.append("</ol></nav><nav epub:type=\"page-list\" id=\"pages\"><h2>原 PDF 页码</h2><ol>");
            for(EpubBook.Page sourcePage:book.pages()){
                String link=pageLinks.get(sourcePage.sourcePage);
                if(link==null)throw new IOException("第 "+(sourcePage.sourcePage+1)+" 页缺少 EPUB 定位");
                nav.append("<li><a href=\"").append(link).append("\">").append(sourcePage.sourcePage+1).append("</a></li>");
            }
            nav.append("</ol></nav></body></html>");ncx.append("</navMap></ncx>");
            put(zip, "OEBPS/nav.xhtml", nav.toString(), cancellation);
            put(zip, "OEBPS/toc.ncx", ncx.toString(), cancellation);
            String opf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"bookid\" xml:lang=\"zh-CN\">"
                    + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"bookid\">" + id
                    + "</dc:identifier><dc:title>" + xml(book.title()) + "</dc:title>"
                    + (book.author().isEmpty() ? "" : "<dc:creator>" + xml(book.author()) + "</dc:creator>")
                    + "<dc:language>zh-CN</dc:language><meta property=\"dcterms:modified\">"
                    + modifiedUtc()
                    + "</meta></metadata><manifest><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>"
                    + "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>"
                    + "<item id=\"css\" href=\"styles.css\" media-type=\"text/css\"/>" + manifest
                    + "</manifest><spine toc=\"ncx\">" + spine + "</spine></package>";
            put(zip, "OEBPS/content.opf", opf, cancellation);
            zip.finish(); file.getFD().sync(); check(cancellation);
            if (destination.length() > MAX_OUTPUT) throw new IOException("EPUB 超过 64 MB 上限");
            done = true;
        } finally { if (!done) destination.delete(); }
    }
    private static String page(String title, String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"zh-CN\">"
                + "<head><title>" + xml(title) + "</title><link rel=\"stylesheet\" type=\"text/css\" href=\"styles.css\"/></head><body>"
                + body + "</body></html>";
    }
    private static String modifiedUtc() {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT);
        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return format.format(new java.util.Date());
    }
    private static void put(ZipOutputStream zip, String name, String value, OcrCancellation cancellation) throws IOException {
        put(zip, name, value.getBytes(StandardCharsets.UTF_8), cancellation);
    }
    private static void put(ZipOutputStream zip, String name, byte[] data, OcrCancellation cancellation) throws IOException {
        check(cancellation); zip.putNextEntry(new ZipEntry(name));
        for (int offset = 0; offset < data.length; offset += 16384) {
            check(cancellation); zip.write(data, offset, Math.min(16384, data.length - offset));
        }
        zip.closeEntry();
    }
    private static void put(ZipOutputStream zip,String name,File source,OcrCancellation cancellation)throws IOException{
        check(cancellation);zip.putNextEntry(new ZipEntry(name));
        try(FileInputStream in=new FileInputStream(source)){byte[] block=new byte[16384];int n;long count=0;
            while((n=in.read(block))!=-1){check(cancellation);count+=n;
                if(count>8_000_000)throw new IOException("原页图片超过 8 MB 上限");zip.write(block,0,n);}}
        zip.closeEntry();
    }
    private static void check(OcrCancellation cancellation) throws IOException { if (cancellation != null) cancellation.throwIfCancelled(); }
    static String xml(String source) {
        StringBuilder out = new StringBuilder(); if (source == null) return "";
        for (int i = 0; i < source.length(); i++) {
            int cp = source.codePointAt(i); if (cp > 0xffff) i++;
            if (cp == '&') out.append("&amp;"); else if (cp == '<') out.append("&lt;");
            else if (cp == '>') out.append("&gt;"); else if (cp == '"') out.append("&quot;");
            else if (cp == '\'') out.append("&apos;");
            else if (cp == 9 || cp == 10 || cp == 13 || (cp >= 32 && cp <= 0xd7ff)
                    || (cp >= 0xe000 && cp <= 0xfffd) || (cp >= 0x10000 && cp <= 0x10ffff)) out.appendCodePoint(cp);
        }
        return out.toString();
    }
}
