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
    private static final int MAX_TEXT_BYTES = 8 * 1024 * 1024;
    private DocumentText() {}
    public static final class Result {
        public final String format, text;
        Result(String format, String text) { this.format=format; this.text=text; }
    }
    public static Result extract(File input, String name, String mime) throws IOException {
        if(input==null || !input.isFile()) throw new IOException("找不到要导入的文档");
        String extension=name==null?"":name.toLowerCase(Locale.ROOT);
        byte[] signature=new byte[8]; int count;
        try(InputStream in=new FileInputStream(input)) { count=in.read(signature); }
        boolean ole=count==8 && (signature[0]&255)==0xd0 && (signature[1]&255)==0xcf && (signature[2]&255)==0x11 && (signature[3]&255)==0xe0 && (signature[4]&255)==0xa1 && (signature[5]&255)==0xb1 && (signature[6]&255)==0x1a && (signature[7]&255)==0xe1;
        boolean zip=count>=4 && signature[0]=='P' && signature[1]=='K' && signature[2]==3 && signature[3]==4;
        String format,text;
        try {
            if(ole) { format="DOC"; text=doc(input); }
            else if(zip) { format="DOCX"; text=docx(input); }
            else if(extension.endsWith(".txt") || "text/plain".equals(mime)) { format="TXT"; text=txt(input); }
            else throw new IOException("支持 PDF、DOC、DOCX 和 TXT；这个文件的实际内容不属于可导入的格式");
        } catch(IOException e) { throw e; }
        catch(RuntimeException e) { throw new IOException("文档可能已损坏、已加密，或内容超过可处理范围。请另存为未加密的 DOCX / PDF 后重试",e); }
        text=clean(text);
        if(text.trim().isEmpty()) throw new IOException("没有找到可导入的正文文字。仅含图片的 Word 文件请先导出为 PDF");
        return new Result(format,text);
    }
    private static String doc(File file) throws IOException {
        if(file.length()>MAX_DOC_BYTES) throw new IOException("DOC 文件较大，请先转换为 DOCX 或 PDF（DOC 导入上限为 32 MB）");
        // Only the bounded paragraph extraction path is used, not POI's desktop/AWT converters.
        org.apache.poi.util.IOUtils.setByteArrayMaxOverride(32*1024*1024);
        StringBuilder out=new StringBuilder();
        try(InputStream in=new BufferedInputStream(new FileInputStream(file)); WordExtractor reader=new WordExtractor(in)) {
            for(String paragraph:reader.getParagraphText()) append(out,WordExtractor.stripFields(paragraph));
            for(String paragraph:reader.getFootnoteText()) append(out,WordExtractor.stripFields(paragraph));
            for(String paragraph:reader.getEndnoteText()) append(out,WordExtractor.stripFields(paragraph));
            for(String paragraph:reader.getMainTextboxText()) append(out,WordExtractor.stripFields(paragraph));
        } catch(OldWordFileFormatException old) {
            out.setLength(0);
            try(InputStream in=new BufferedInputStream(new FileInputStream(file)); Word6Extractor reader=new Word6Extractor(in)) {
                for(String paragraph:reader.getParagraphText()) append(out,WordExtractor.stripFields(paragraph));
            }
        }
        return out.toString();
    }
    private static String docx(File file) throws IOException {
        try(ZipFile zip=new ZipFile(file)) {
            ZipEntry body=zip.getEntry("word/document.xml");
            if(body==null) throw new IOException("此 ZIP 文件不是有效的 DOCX 文档");
            if(body.getSize()>MAX_XML_BYTES) throw new IOException("Word 正文过大，请先拆分文档或导出为 PDF");
            byte[] bytes;
            try(InputStream in=zip.getInputStream(body)) { bytes=readLimited(in,MAX_XML_BYTES); }
            // Reject DTDs before XML parsing, including UTF-16/32 encodings. OOXML has no DTD.
            String probe=new String(bytes,StandardCharsets.ISO_8859_1).replace("\u0000","");
            if(probe.contains("<!DOCTYPE") || probe.contains("<!ENTITY")) throw new IOException("文档包含不受支持的 XML 实体定义");
            DocxHandler handler=new DocxHandler();
            try {
                SAXParserFactory factory=SAXParserFactory.newInstance(); factory.setNamespaceAware(true); factory.setValidating(false);
                XMLReader parser=factory.newSAXParser().getXMLReader();
                parser.setFeature("http://xml.org/sax/features/external-general-entities",false);
                parser.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
                parser.setContentHandler(handler); parser.setEntityResolver(handler); parser.setErrorHandler(handler);
                parser.parse(new InputSource(new ByteArrayInputStream(bytes)));
            } catch(Exception e) { throw new IOException("DOCX 正文无法解析，或内容超过可处理范围",e); }
            return handler.text.toString();
        }
    }
    private static final class DocxHandler extends DefaultHandler2 {
        final StringBuilder text=new StringBuilder(); int textDepth,deletedDepth;
        static boolean word(String uri) { return "http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(uri) || "http://purl.oclc.org/ooxml/wordprocessingml/main".equals(uri); }
        @Override public InputSource resolveEntity(String publicId,String systemId) throws SAXException { throw new SAXException("External entities are not allowed"); }
        @Override public void startElement(String uri,String name,String qName,Attributes attributes) throws SAXException {
            if(!word(uri)) return;
            if("del".equals(name)) deletedDepth++;
            if(deletedDepth>0) return;
            if("t".equals(name)) textDepth++;
            if("tab".equals(name)) add("\t");
            if("br".equals(name)||"cr".equals(name)) add("\n");
        }
        @Override public void endElement(String uri,String name,String qName) throws SAXException {
            if(!word(uri)) return;
            if("del".equals(name)) {deletedDepth--;return;}
            if(deletedDepth>0)return;
            if("t".equals(name)) textDepth--;
            if("p".equals(name)||"tr".equals(name)) add("\n");
            if("tc".equals(name)) add("\t");
        }
        @Override public void characters(char[] ch,int start,int length) throws SAXException { if(textDepth>0 && deletedDepth==0) {if(text.length()+length>MAX_CHARACTERS)throw new SAXException("Text limit");text.append(ch,start,length);} }
        void add(String value) throws SAXException {if(text.length()+value.length()>MAX_CHARACTERS)throw new SAXException("Text limit");text.append(value);}
        @Override public void error(SAXParseException e) throws SAXException {throw e;}
        @Override public void fatalError(SAXParseException e) throws SAXException {throw e;}
    }
    private static String txt(File file) throws IOException {
        byte[] data;try(InputStream in=new FileInputStream(file)){data=readLimited(in,MAX_TEXT_BYTES);}
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
    private static byte[] readLimited(InputStream in,int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
        while((count=in.read(buffer))!=-1){if(out.size()+count>limit)throw new IOException("文档内容过大，请拆分后再导入");out.write(buffer,0,count);}
        return out.toByteArray();
    }
    private static void append(StringBuilder out,String text) throws IOException {if(text==null)return;if(out.length()+text.length()>MAX_CHARACTERS)throw new IOException("文本超过 200 万字符，请先拆分文档");out.append(text);}
    private static String clean(String text) throws IOException {
        if(text.length()>MAX_CHARACTERS)throw new IOException("文本超过 200 万字符，请先拆分文档");
        StringBuilder out=new StringBuilder(text.length());boolean previousCr=false;
        for(int i=0;i<text.length();i++) {char c=text.charAt(i);if(c=='\r'){out.append('\n');previousCr=true;continue;}if(c=='\n'&&previousCr){previousCr=false;continue;}previousCr=false;if(c=='\u0007')out.append('\t');else if(c=='\u000b'||c=='\u000c')out.append('\n');else if(c=='\n'||c=='\t'||c>=32&&c!='\ufffe'&&c!='\uffff')out.append(c);}
        return out.toString();
    }
}
