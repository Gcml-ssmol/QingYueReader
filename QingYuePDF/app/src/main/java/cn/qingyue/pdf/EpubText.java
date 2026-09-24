package cn.qingyue.pdf;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** EPUB 2/3 spine text extraction. No CSS, script, network or DRM processing. */
final class EpubText {
    private static final int MAX_ENTRIES=2048, MAX_MANIFEST=2048, MAX_SPINE=1024, MAX_XML=8*1024*1024, MAX_TOTAL=48*1024*1024;
    private EpubText() {}
    static String extract(File file,DocumentText.Monitor monitor)throws IOException {
        if(file.length()>64L*1024*1024)throw new IOException("EPUB 文件超过 64 MB");
        try(ZipFile zip=new ZipFile(file)) {
            Map<String,ZipEntry> entries=new HashMap<>();int count=0;long sizes=0;
            for(Enumeration<? extends ZipEntry> e=zip.entries();e.hasMoreElements();) {
                active(monitor);ZipEntry entry=e.nextElement();if(++count>MAX_ENTRIES)throw new IOException("EPUB 文件条目过多");
                String path=path(entry.getName());if(path==null || entries.put(path,entry)!=null)throw new IOException("EPUB 包含不安全或重复的路径");
                if(entry.getSize()>MAX_XML)throw new IOException("EPUB 文件条目过大");
                if(entry.getSize()>0 && (sizes+=entry.getSize())>MAX_TOTAL)throw new IOException("EPUB 解压内容过大");
            }
            if(!entries.containsKey("mimetype"))throw new IOException("不是有效的 EPUB 文件");
            String mime=new String(read(zip,entries.get("mimetype"),128,monitor),StandardCharsets.US_ASCII);
            if(!"application/epub+zip".equals(mime.trim()))throw new IOException("不是有效的 EPUB 文件");
            ZipEntry container=entries.get("META-INF/container.xml");if(container==null)throw new IOException("EPUB 缺少容器目录");
            Container handler=new Container();parse(read(zip,container,MAX_XML,monitor),handler);
            String opfPath=path(handler.root);if(opfPath==null || !entries.containsKey(opfPath))throw new IOException("EPUB 缺少 OPF 书目");
            PackageInfo info=new PackageInfo();parse(read(zip,entries.get(opfPath),MAX_XML,monitor),info);
            String base=opfPath.substring(0,opfPath.lastIndexOf('/')+1),title=info.title.trim();
            if(entries.containsKey("META-INF/encryption.xml")){
                Encryption encrypted=new Encryption();parse(read(zip,entries.get("META-INF/encryption.xml"),MAX_XML,monitor),encrypted);
                if(encrypted.paths.isEmpty())throw new IOException("EPUB 加密资源声明无效");
                for(String resource:encrypted.paths){
                    String target=resolve("",resource);boolean font=false;
                    for(Item candidate:info.items.values())if(resolve(base,candidate.href).equals(target)){
                        if(candidate.media==null||!(candidate.media.startsWith("font/")||candidate.media.equals("application/vnd.ms-opentype")||candidate.media.equals("application/font-sfnt")||candidate.media.equals("application/x-font-ttf")||candidate.media.equals("application/x-font-opentype")))throw new IOException("不支持加密正文或受 DRM 保护的 EPUB");
                        font=true;
                    }
                    if(!font)throw new IOException("EPUB 加密资源不是已声明字体");
                }
            }
            StringBuilder out=new StringBuilder();if(!title.isEmpty())append(out,title+"\n\n");
            int chapters=0;
            for(String id:info.spine) {
                active(monitor);Item item=info.items.get(id);
                if(item==null)throw new IOException("EPUB 目录引用了不存在的章节");
                if(!"application/xhtml+xml".equals(item.media))continue;
                String location=resolve(base,item.href);ZipEntry entry=entries.get(location);
                if(entry==null)throw new IOException("EPUB 缺少章节文件");
                Chapter chapter=new Chapter(monitor);parse(read(zip,entry,MAX_XML,monitor),chapter);
                String body=chapter.text.toString().trim();if(!body.isEmpty()){if(chapters++>0)append(out,"\n\n");append(out,body);}
            }
            if(chapters==0)throw new IOException("EPUB 没有可导入的 XHTML 章节");
            return out.toString();
        } catch(ZipException e){throw new IOException("EPUB 压缩包已损坏",e);}
    }
    private static String path(String raw) {
        if(raw==null || raw.isEmpty() || raw.startsWith("/") || raw.indexOf('\\')>=0 || raw.indexOf('\0')>=0 || raw.matches("(?i)^[a-z][a-z0-9+.-]*:.*"))return null;
        ArrayDeque<String> parts=new ArrayDeque<>();for(String part:raw.split("/",-1)){if(part.isEmpty() || part.equals("."))continue;if(part.equals("..")){if(parts.isEmpty())return null;parts.removeLast();}else parts.add(part);}
        return String.join("/",parts);
    }
    private static String resolve(String base,String href)throws IOException {
        if(href==null || href.isEmpty() || href.startsWith("/") || href.indexOf('\\')>=0 || href.matches("(?i)^[a-z][a-z0-9+.-]*:.*"))throw new IOException("EPUB 包含外部章节路径");
        String p=href.split("[?#]",2)[0];String decoded=decode(p);String result=path(base+decoded);
        if(result==null)throw new IOException("EPUB 包含越界章节路径");return result;
    }
    private static String decode(String path)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();StringBuilder out=new StringBuilder();
        for(int i=0;i<path.length();) {char c=path.charAt(i);if(c=='%'){bytes.reset();while(i<path.length()&&path.charAt(i)=='%'){
                    if(i+2>=path.length())throw new IOException("EPUB 章节路径编码无效");
                    int high=Character.digit(path.charAt(i+1),16),low=Character.digit(path.charAt(i+2),16);
                    if(high<0||low<0)throw new IOException("EPUB 章节路径编码无效");
                    bytes.write((high<<4)|low);i+=3;
                }
                try{out.append(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())));}
                catch(CharacterCodingException e){throw new IOException("EPUB 章节路径编码无效",e);}
            }else{out.append(c);i++;}}
        return out.toString();
    }
    private static byte[] read(ZipFile zip,ZipEntry entry,int limit,DocumentText.Monitor monitor)throws IOException {
        if(entry.getSize()>limit)throw new IOException("EPUB 内容过大");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];
        try(InputStream in=zip.getInputStream(entry)){int n;while((n=in.read(buffer))!=-1){active(monitor);if(out.size()+n>limit)throw new IOException("EPUB 解压内容过大");out.write(buffer,0,n);}}
        return out.toByteArray();
    }
    private static void parse(byte[] xml,DefaultHandler handler)throws IOException {
        try {rejectInternalDtd(xml);SAXParserFactory f=SAXParserFactory.newInstance();f.setNamespaceAware(true);
            XMLReader parser=f.newSAXParser().getXMLReader();
            // Expat on Android does not recognize Xerces-only factory features.
            // The resolver supplies only fixed local XHTML entities; external resources never load.
            parser.setEntityResolver((publicId,systemId)->new InputSource(new StringReader("<!ENTITY nbsp '&#160;'><!ENTITY ensp '&#8194;'><!ENTITY emsp '&#8195;'><!ENTITY thinsp '&#8201;'><!ENTITY ndash '&#8211;'><!ENTITY mdash '&#8212;'><!ENTITY lsquo '&#8216;'><!ENTITY rsquo '&#8217;'><!ENTITY ldquo '&#8220;'><!ENTITY rdquo '&#8221;'><!ENTITY hellip '&#8230;'><!ENTITY copy '&#169;'><!ENTITY reg '&#174;'><!ENTITY trade '&#8482;'><!ENTITY bull '&#8226;'><!ENTITY shy '&#173;'>")));
            parser.setContentHandler(handler);parser.parse(new InputSource(new ByteArrayInputStream(xml)));
        }catch(SAXException e){Throwable cause=e.getCause();if(cause instanceof InterruptedIOException)throw (InterruptedIOException)cause;throw new IOException("EPUB XML 无法解析",e);}catch(Exception e){throw new IOException("EPUB XML 无法解析",e);}
    }
    private static void rejectInternalDtd(byte[] xml)throws IOException {
        // Removing NULs also exposes ASCII declarations in UTF-16/32 XML.
        String source=new String(xml,StandardCharsets.ISO_8859_1).replace("\u0000","").replace("\u00ef\u00bb\u00bf","").replace("\u00ff\u00fe","").replace("\u00fe\u00ff","").trim().toUpperCase(Locale.ROOT);
        if(!source.startsWith("<"))throw new IOException("EPUB XML 编码不受支持");
        // Scan globally: a harmless DOCTYPE in an XML comment must not hide a later entity declaration.
        if(source.contains("<!ENTITY"))throw new IOException("EPUB 不支持内部 DTD 实体声明");
        int start=source.indexOf("<!DOCTYPE");
        if(start<0)return;
        boolean quoted=false;char quote=0;
        for(int i=start+9;i<source.length();i++){
            char c=source.charAt(i);
            if(quoted){if(c==quote)quoted=false;continue;}
            if(c=='\''||c=='\"'){quoted=true;quote=c;continue;}
            if(c=='[')throw new IOException("EPUB 不支持内部 DTD 实体声明");
            if(c=='>')return;
        }
        throw new IOException("EPUB DOCTYPE 不完整");
    }
    private static void active(DocumentText.Monitor monitor)throws InterruptedIOException {if(Thread.currentThread().isInterrupted() || monitor!=null && monitor.isCancelled())throw new InterruptedIOException("已取消导入");}
    private static void append(StringBuilder text,String value)throws IOException{if(text.length()+value.length()>DocumentText.MAX_CHARACTERS)throw new IOException("EPUB 正文超过 200 万字符");text.append(value);}
    private static final class Container extends DefaultHandler {String root;@Override public void startElement(String uri,String local,String qName,Attributes a){if("rootfile".equals(local)&&root==null)root=a.getValue("full-path");}}
    private static final class Item {final String href,media;Item(String href,String media){this.href=href;this.media=media;}}
    private static final class Encryption extends DefaultHandler {
        final List<String> paths=new ArrayList<>();String algorithm,path;boolean inside;
        @Override public void startElement(String uri,String local,String qName,Attributes a)throws SAXException{
            if("EncryptedData".equals(local)){if(inside)throw new SAXException("Nested encryption");inside=true;algorithm=null;path=null;}
            else if(inside&&"EncryptionMethod".equals(local))algorithm=a.getValue("Algorithm");
            else if(inside&&"CipherReference".equals(local))path=a.getValue("URI");
        }
        @Override public void endElement(String uri,String local,String qName)throws SAXException{
            if("EncryptedData".equals(local)){
                if(!"http://www.idpf.org/2008/embedding".equals(algorithm)||path==null||paths.size()>=MAX_ENTRIES)throw new SAXException("Only unused obfuscated fonts are supported");
                paths.add(path);inside=false;
            }
        }
    }
    private static final class PackageInfo extends DefaultHandler {
        final Map<String,Item> items=new HashMap<>();final List<String> spine=new ArrayList<>();String title="";boolean inTitle;
        @Override public void startElement(String uri,String local,String qName,Attributes a)throws SAXException {if("item".equals(local)){if(items.size()>=MAX_MANIFEST)throw new SAXException("EPUB manifest limit");String id=a.getValue("id");if(id!=null)items.put(id,new Item(a.getValue("href"),a.getValue("media-type")));}else if("itemref".equals(local)){if(spine.size()>=MAX_SPINE)throw new SAXException("EPUB spine limit");String id=a.getValue("idref");if(id!=null && !"no".equals(a.getValue("linear")))spine.add(id);}else if("title".equals(local) && title.isEmpty())inTitle=true;}
        @Override public void characters(char[] ch,int start,int length){if(inTitle && title.length()<512)title+=new String(ch,start,Math.min(length,512-title.length()));}
        @Override public void endElement(String uri,String local,String qName){if("title".equals(local))inTitle=false;}
    }
    private static final class Chapter extends DefaultHandler {
        final StringBuilder text=new StringBuilder();final DocumentText.Monitor monitor;int body,skip;
        Chapter(DocumentText.Monitor monitor){this.monitor=monitor;}
        @Override public void startElement(String uri,String local,String qName,Attributes a)throws SAXException {check();if("body".equals(local)){body++;return;}if(body==0)return;if(skip>0){skip++;return;}if("script".equals(local)||"style".equals(local)||"svg".equals(local)){skip=1;return;}if("br".equals(local)||"hr".equals(local)||"p".equals(local)||"div".equals(local)||"li".equals(local)||"h1".equals(local)||"h2".equals(local)||"h3".equals(local)||"h4".equals(local)||"tr".equals(local))newline();if("li".equals(local))add("• ");if("img".equals(local)){String alt=a.getValue("alt");if(alt!=null&&!alt.trim().isEmpty())add("[图片："+alt.trim()+"]");}}
        @Override public void endElement(String uri,String local,String qName)throws SAXException {check();if(skip>0){skip--;return;}if("body".equals(local)){body--;return;}if(body>0 && ("p".equals(local)||"div".equals(local)||"li".equals(local)||"h1".equals(local)||"h2".equals(local)||"h3".equals(local)||"h4".equals(local)||"tr".equals(local)))newline();}
        @Override public void characters(char[] ch,int start,int length)throws SAXException {check();if(body>0&&skip==0)add(new String(ch,start,length));}
        void newline()throws SAXException{if(text.length()>0&&text.charAt(text.length()-1)!='\n')add("\n");}
        void add(String value)throws SAXException{if(text.length()+value.length()>DocumentText.MAX_CHARACTERS)throw new SAXException("EPUB text limit");text.append(value);}
        void check()throws SAXException{try{active(monitor);}catch(InterruptedIOException e){throw new SAXException(e);}}
    }
}
