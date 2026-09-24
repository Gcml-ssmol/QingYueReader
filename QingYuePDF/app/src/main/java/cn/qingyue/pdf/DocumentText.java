package cn.qingyue.pdf;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.ext.DefaultHandler2;
import org.apache.poi.hwpf.OldWordFileFormatException;
import org.apache.poi.hwpf.extractor.Word6Extractor;
import org.apache.poi.hwpf.extractor.WordExtractor;

/** Offline, bounded text extraction; never invokes Office, macros or external URLs. */
public final class DocumentText {
    public static final int MAX_CHARACTERS = 2000000;
    private static final long MAX_DOC_BYTES = 32L * 1024 * 1024;
    private static final int MAX_XML_BYTES = 16 * 1024 * 1024;
    public static final int MAX_TEXT_BYTES = 8 * 1024 * 1024;
    private DocumentText() {}
    public interface Monitor {
        boolean isCancelled();
        /** Byte progress where available; total is -1 when the parser cannot measure it. */
        void progress(long completed,long total);
    }
    public static final class Result {
        public final String format, text;
        Result(String format, String text) { this.format=format; this.text=text; }
    }
    public static Result extract(File input, String name, String mime) throws IOException {
        return extract(input,name,mime,null);
    }
    public static Result extract(File input, String name, String mime, Monitor monitor) throws IOException {
        if(input==null || !input.isFile()) throw new IOException("找不到要导入的文档");
        active(monitor);report(monitor,0,input.length());
        String extension=name==null?"":name.toLowerCase(Locale.ROOT);
        byte[] signature=new byte[8]; int count;
        try(InputStream in=new FileInputStream(input)) { count=in.read(signature); }
        boolean ole=count==8 && (signature[0]&255)==0xd0 && (signature[1]&255)==0xcf && (signature[2]&255)==0x11 && (signature[3]&255)==0xe0 && (signature[4]&255)==0xa1 && (signature[5]&255)==0xb1 && (signature[6]&255)==0x1a && (signature[7]&255)==0xe1;
        boolean zip=count>=4 && signature[0]=='P' && signature[1]=='K' && signature[2]==3 && signature[3]==4;
        String format,text;
        try {
            if(ole) { format="DOC"; text=doc(input,monitor); }
            else if(zip && (extension.endsWith(".epub") || "application/epub+zip".equals(mime))) { format="EPUB"; text=EpubText.extract(input,monitor); }
            else if(zip) { format="DOCX"; text=docx(input,monitor); }
            else if(extension.endsWith(".txt") || "text/plain".equals(mime)) { format="TXT"; text=txt(input,monitor); }
            else throw new IOException("支持 PDF、DOC、DOCX、EPUB 和 TXT；这个文件的实际内容不属于可导入的格式");
        } catch(IOException e) { throw e; }
        catch(RuntimeException e) { throw new IOException("文档可能已损坏、已加密，或内容超过可处理范围。请另存为未加密的 DOCX / PDF 后重试",e); }
        text=clean(text,monitor);
        if(!hasVisibleText(text)) throw new IOException("没有找到可导入的正文文字。仅含图片的文档请先导出为 PDF");
        active(monitor);report(monitor,input.length(),input.length());
        return new Result(format,text);
    }
    /** Validates and normalizes ACTION_SEND/EXTRA_TEXT before it is written as UTF-8. */
    public static String prepareSharedText(CharSequence value) throws IOException {
        if(value==null)throw new IOException("没有收到可导入的分享文字");
        if(value.length()>MAX_CHARACTERS)throw new IOException("分享文字超过 200 万字符，请分段导入");
        String source=value.toString();
        if(source.indexOf('\u0000')>=0)throw new IOException("分享文字包含无效的二进制字符");
        String text=clean(source,null);
        if(!hasVisibleText(text))throw new IOException("没有收到可导入的分享文字");
        return text;
    }
    private static String doc(File file,Monitor monitor) throws IOException {
        if(file.length()>MAX_DOC_BYTES) throw new IOException("DOC 文件较大，请先转换为 DOCX 或 PDF（DOC 导入上限为 32 MB）");
        // Only the bounded paragraph extraction path is used, not POI's desktop/AWT converters.
        org.apache.poi.util.IOUtils.setByteArrayMaxOverride(32*1024*1024);
        StringBuilder out=new StringBuilder();
        try(InputStream in=new BufferedInputStream(new MonitoredInputStream(new FileInputStream(file),monitor,file.length())); WordExtractor reader=new WordExtractor(in)) {
            for(String paragraph:reader.getParagraphText()) append(out,WordExtractor.stripFields(paragraph),monitor);
            for(String paragraph:reader.getFootnoteText()) append(out,WordExtractor.stripFields(paragraph),monitor);
            for(String paragraph:reader.getEndnoteText()) append(out,WordExtractor.stripFields(paragraph),monitor);
            for(String paragraph:reader.getMainTextboxText()) append(out,WordExtractor.stripFields(paragraph),monitor);
        } catch(OldWordFileFormatException old) {
            out.setLength(0);
            try(InputStream in=new BufferedInputStream(new MonitoredInputStream(new FileInputStream(file),monitor,file.length())); Word6Extractor reader=new Word6Extractor(in)) {
                for(String paragraph:reader.getParagraphText()) append(out,WordExtractor.stripFields(paragraph),monitor);
            }
        }
        return out.toString();
    }
    private static String docx(File file,Monitor monitor) throws IOException {
        try(ZipFile zip=new ZipFile(file)) {
            ZipEntry body=zip.getEntry("word/document.xml");
            if(body==null) throw new IOException("此 ZIP 文件不是有效的 DOCX 文档");
            if(body.getSize()>MAX_XML_BYTES) throw new IOException("Word 正文过大，请先拆分文档或导出为 PDF");
            byte[] bytes;
            try(InputStream in=zip.getInputStream(body)) { bytes=readLimited(in,MAX_XML_BYTES,monitor,body.getSize()); }
            // Reject DTDs before XML parsing, including UTF-16/32 encodings. OOXML has no DTD.
            String probe=new String(bytes,StandardCharsets.ISO_8859_1).replace("\u0000","");
            if(probe.contains("<!DOCTYPE") || probe.contains("<!ENTITY")) throw new IOException("文档包含不受支持的 XML 实体定义");
            DocxHandler handler=new DocxHandler(monitor);
            try {
                SAXParserFactory factory=SAXParserFactory.newInstance(); factory.setNamespaceAware(true); factory.setValidating(false);
                XMLReader parser=factory.newSAXParser().getXMLReader();
                parser.setFeature("http://xml.org/sax/features/external-general-entities",false);
                parser.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
                parser.setContentHandler(handler); parser.setEntityResolver(handler); parser.setErrorHandler(handler);
                parser.parse(new InputSource(new ByteArrayInputStream(bytes)));
            } catch(Exception e) {active(monitor);throw new IOException("DOCX 正文无法解析，或内容超过可处理范围",e); }
            return handler.text.toString();
        }
    }
    private static final class DocxHandler extends DefaultHandler2 {
        final StringBuilder text=new StringBuilder(); int textDepth,deletedDepth;
        final Monitor monitor;
        DocxHandler(Monitor monitor){this.monitor=monitor;}
        static boolean word(String uri) { return "http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(uri) || "http://purl.oclc.org/ooxml/wordprocessingml/main".equals(uri); }
        @Override public InputSource resolveEntity(String publicId,String systemId) throws SAXException { throw new SAXException("External entities are not allowed"); }
        @Override public void startElement(String uri,String name,String qName,Attributes attributes) throws SAXException {
            checkActive();
            if(!word(uri)) return;
            if("del".equals(name)) deletedDepth++;
            if(deletedDepth>0) return;
            if("t".equals(name)) textDepth++;
            if("tab".equals(name)) add("\t");
            if("br".equals(name)||"cr".equals(name)) add("\n");
        }
        @Override public void endElement(String uri,String name,String qName) throws SAXException {
            checkActive();
            if(!word(uri)) return;
            if("del".equals(name)) {deletedDepth--;return;}
            if(deletedDepth>0)return;
            if("t".equals(name)) textDepth--;
            if("p".equals(name)||"tr".equals(name)) add("\n");
            if("tc".equals(name)) add("\t");
        }
        @Override public void characters(char[] ch,int start,int length) throws SAXException {checkActive();if(textDepth>0 && deletedDepth==0) {if(text.length()+length>MAX_CHARACTERS)throw new SAXException("Text limit");text.append(ch,start,length);} }
        void add(String value) throws SAXException {if(text.length()+value.length()>MAX_CHARACTERS)throw new SAXException("Text limit");text.append(value);}
        void checkActive()throws SAXException{try{active(monitor);}catch(IOException e){throw new SAXException(e);}}
        @Override public void error(SAXParseException e) throws SAXException {throw e;}
        @Override public void fatalError(SAXParseException e) throws SAXException {throw e;}
    }
    private static String txt(File file,Monitor monitor) throws IOException {
        byte[] data;try(InputStream in=new FileInputStream(file)){data=readLimited(in,MAX_TEXT_BYTES,monitor,file.length());}
        int offset=0;Charset charset=StandardCharsets.UTF_8;
        if(data.length>=3&&(data[0]&255)==239&&(data[1]&255)==187&&(data[2]&255)==191) offset=3;
        else if(data.length>=2&&(data[0]&255)==255&&(data[1]&255)==254) {charset=StandardCharsets.UTF_16LE;offset=2;}
        else if(data.length>=2&&(data[0]&255)==254&&(data[1]&255)==255) {charset=StandardCharsets.UTF_16BE;offset=2;}
        String text;
        try {text=charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data,offset,data.length-offset)).toString();}
        catch(CharacterCodingException e) { if(offset!=0)throw new IOException("文本编码不完整，请另存为 UTF-8 后重试",e);text=Charset.forName("GB18030").decode(ByteBuffer.wrap(data)).toString(); }
        if(text.indexOf('\u0000')>=0)throw new IOException("文件包含二进制数据，请选择有效的 TXT 文本文件");
        if(text.length()>MAX_CHARACTERS)throw new IOException("文本超过 200 万字符，请先拆分文件");
        return text;
    }
    private static byte[] readLimited(InputStream in,int limit,Monitor monitor,long total) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
        while((count=in.read(buffer))!=-1){active(monitor);if(out.size()+count>limit)throw new IOException("文档内容过大，请拆分后再导入");out.write(buffer,0,count);report(monitor,out.size(),total);}
        return out.toByteArray();
    }
    private static void append(StringBuilder out,String text,Monitor monitor) throws IOException {active(monitor);if(text==null)return;if(out.length()+text.length()>MAX_CHARACTERS)throw new IOException("文本超过 200 万字符，请先拆分文档");out.append(text);}
    private static String clean(String text,Monitor monitor) throws IOException {
        if(text.length()>MAX_CHARACTERS)throw new IOException("文本超过 200 万字符，请先拆分文档");
        StringBuilder out=new StringBuilder(text.length());boolean previousCr=false;
        for(int i=0;i<text.length();i++) {if((i&4095)==0)active(monitor);char c=text.charAt(i);if(c=='\r'){out.append('\n');previousCr=true;continue;}if(c=='\n'&&previousCr){previousCr=false;continue;}previousCr=false;if(c=='\u0007')out.append('\t');else if(c=='\u000b'||c=='\u000c')out.append('\n');else if(c=='\n'||c=='\t'||c>=32&&c!='\ufffe'&&c!='\uffff')out.append(c);}
        return out.toString();
    }
    private static boolean hasVisibleText(String text){for(int offset=0;offset<text.length();){int cp=text.codePointAt(offset);if(!Character.isWhitespace(cp)&&!Character.isSpaceChar(cp)&&Character.getType(cp)!=Character.FORMAT)return true;offset+=Character.charCount(cp);}return false;}
    private static void active(Monitor monitor)throws IOException{boolean cancelled=Thread.currentThread().isInterrupted();if(!cancelled&&monitor!=null){try{cancelled=monitor.isCancelled();}catch(RuntimeException failure){cancelled=true;}}if(cancelled)throw new InterruptedIOException("已取消导入");}
    private static void report(Monitor monitor,long completed,long total){if(monitor!=null)try{monitor.progress(completed,total);}catch(RuntimeException ignored){}}
    private static final class MonitoredInputStream extends FilterInputStream {
        final Monitor monitor;final long total;long completed;
        MonitoredInputStream(InputStream input,Monitor monitor,long total){super(input);this.monitor=monitor;this.total=total;}
        @Override public int read()throws IOException{active(monitor);int value=in.read();if(value!=-1){completed++;report(monitor,completed,total);}return value;}
        @Override public int read(byte[] buffer,int offset,int length)throws IOException{active(monitor);int count=in.read(buffer,offset,length);if(count>0){completed+=count;report(monitor,completed,total);}return count;}
        @Override public long skip(long count)throws IOException{active(monitor);long skipped=in.skip(count);if(skipped>0){completed+=skipped;report(monitor,completed,total);}return skipped;}
    }
}
