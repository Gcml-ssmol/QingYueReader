package cn.qingyue.pdf;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.zip.*;

public final class EpubImportTest {
    public static void main(String[] args)throws Exception {
        File dir=new File(args[0]);dir.mkdirs();
        Map<String,String> files=book();File good=write(new File(dir,"book.epub"),files);
        DocumentText.Result result=DocumentText.extract(good,"renamed.epub",null);
        require("EPUB".equals(result.format),"format");
        require(result.text.indexOf("第二章")<result.text.indexOf("第一章"),"spine order");
        require(result.text.contains("中文正文") && result.text.contains("图片：封面"),"unicode and image alt");
        require(!result.text.contains("alert") && !result.text.contains("red"),"script and style ignored");
        require("EPUB".equals(DocumentText.extract(good,"renamed.bin","application/epub+zip").format),"MIME routing");
        Map<String,String> fontBook=book();fontBook.put("OPS/book.opf",fontBook.get("OPS/book.opf").replace("</manifest>","<item id='font' href='font.ttf' media-type='font/ttf'/></manifest>"));
        fontBook.put("OPS/font.ttf","unused obfuscated font bytes");fontBook.put("META-INF/encryption.xml","<encryption xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><EncryptedData xmlns='http://www.w3.org/2001/04/xmlenc#'><EncryptionMethod Algorithm='http://www.idpf.org/2008/embedding'/><CipherData><CipherReference URI='OPS/font.ttf'/></CipherData></EncryptedData></encryption>");
        require(DocumentText.extract(write(new File(dir,"font.epub"),fontBook),"font.epub",null).text.contains("中文正文"),"unused obfuscated font does not block text");
        fontBook.put("META-INF/encryption.xml",fontBook.get("META-INF/encryption.xml").replace("OPS/font.ttf","OPS/text/first.xhtml"));reject(write(new File(dir,"encrypted-text.epub"),fontBook),"encrypted XHTML must never bypass DRM check");
        Map<String,String> commentDtd=book();commentDtd.put("text/second.xhtml","<!-- <!DOCTYPE html> --><!DOCTYPE html [<!ENTITY a 'x'><!ENTITY b '&a;&a;'>]><html xmlns='http://www.w3.org/1999/xhtml'><body>&b;</body></html>");reject(write(new File(dir,"comment-dtd.epub"),commentDtd),"comment cannot hide internal entities");
        Map<String,String> named=book();named.put("text/second.xhtml","<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.1//EN' 'https://invalid.example/xhtml.dtd'><html xmlns='http://www.w3.org/1999/xhtml'><body>本地&nbsp;文字&mdash;引用</body></html>");
        require(DocumentText.extract(write(new File(dir,"named.epub"),named),"named.epub",null).text.contains("文字—引用"),"finite local XHTML entities");
        Map<String,String> encrypted=book();encrypted.put("META-INF/encryption.xml","<encryption/>");reject(write(new File(dir,"encrypted.epub"),encrypted),"encryption");
        Map<String,String> entity=book();entity.put("text/second.xhtml","<!DOCTYPE html [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><html xmlns='http://www.w3.org/1999/xhtml'><body>&x;</body></html>");reject(write(new File(dir,"entity.epub"),entity),"XXE");
        Map<String,String> expansion=book();expansion.put("text/second.xhtml","<!DOCTYPE html [<!ENTITY x '1234567890'><!ENTITY y '&x;&x;&x;&x;&x;'>]><html xmlns='http://www.w3.org/1999/xhtml'><body>&y;</body></html>");reject(write(new File(dir,"expansion.epub"),expansion),"entity expansion");
        Map<String,String> doctype=book();doctype.put("text/second.xhtml","<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.1//EN' 'http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd'><html xmlns='http://www.w3.org/1999/xhtml'><body><p>DOCTYPE 正常正文</p></body></html>");
        require(DocumentText.extract(write(new File(dir,"doctype.epub"),doctype),"doctype.epub",null).text.contains("DOCTYPE 正常正文"),"external doctype ignored safely");
        Map<String,String> unicode=book();unicode.put("OPS/book.opf",unicode.get("OPS/book.opf").replace("../text/second.xhtml","../text/%E4%B8%AD%E6%96%87.xhtml"));unicode.put("text/中文.xhtml",unicode.remove("text/second.xhtml"));
        require(DocumentText.extract(write(new File(dir,"unicode.epub"),unicode),"unicode.epub",null).text.contains("第二章"),"UTF-8 percent encoded path");
        for(String bad:new String[]{"%","%A","%GG","%E4%B8","%C0%AF"}){
            Map<String,String> malformed=book();malformed.put("OPS/book.opf",malformed.get("OPS/book.opf").replace("../text/second.xhtml","../text/"+bad+".xhtml"));
            rejectWithTimeout(write(new File(dir,"bad-path-"+bad.replace('%','p')+".epub"),malformed),"malformed percent path "+bad);
        }
        Map<String,String> escape=book();escape.put("OPS/book.opf",escape.get("OPS/book.opf").replace("../text/second.xhtml","../../../text/second.xhtml"));reject(write(new File(dir,"escape.epub"),escape),"path escape");
        Map<String,String> bomb=book();StringBuilder huge=new StringBuilder("<html xmlns='http://www.w3.org/1999/xhtml'><body>");for(int i=0;i<9000000;i++)huge.append('x');huge.append("</body></html>");bomb.put("text/second.xhtml",huge.toString());reject(write(new File(dir,"bomb.epub"),bomb),"zip bomb");
        try{DocumentText.extract(good,"book.epub",null,new DocumentText.Monitor(){public boolean isCancelled(){return true;}public void progress(long n,long t){}});throw new AssertionError("cancellation");}catch(InterruptedIOException expected){}
        System.out.println("EpubImportTest: spine, paths, XHTML, CJK, DOCTYPE, limits, encryption, XXE and cancellation passed");
    }
    static Map<String,String> book(){Map<String,String> m=new LinkedHashMap<>();m.put("mimetype","application/epub+zip");m.put("META-INF/container.xml","<?xml version='1.0'?><container xmlns='urn:oasis:names:tc:opendocument:xmlns:container' version='1.0'><rootfiles><rootfile full-path='OPS/book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>");m.put("OPS/book.opf","<?xml version='1.0'?><package xmlns='http://www.idpf.org/2007/opf' version='3.0'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>测试书</dc:title></metadata><manifest><item id='first' href='text/first.xhtml' media-type='application/xhtml+xml'/><item id='second' href='../text/second.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='second'/><itemref idref='first'/></spine></package>");m.put("OPS/text/first.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><body><h1>第一章</h1><p>中文正文<img alt='封面' src='cover.png'/></p></body></html>");m.put("text/second.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><body><h1>第二章</h1><script>alert('bad')</script><style>red</style><p>先读这里</p></body></html>");return m;}
    static File write(File file,Map<String,String> files)throws Exception{try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(file))){for(Map.Entry<String,String> e:files.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue().getBytes(StandardCharsets.UTF_8));z.closeEntry();}}return file;}
    static void reject(File file,String label)throws Exception{try{DocumentText.extract(file,file.getName(),null);throw new AssertionError(label);}catch(IOException expected){}}
    static void rejectWithTimeout(File file,String label)throws Exception{ExecutorService pool=Executors.newSingleThreadExecutor();try{Future<?> future=pool.submit(()->{try{reject(file,label);}catch(Exception e){throw new RuntimeException(e);}});future.get(3,TimeUnit.SECONDS);}finally{pool.shutdownNow();}}
    static void require(boolean yes,String label){if(!yes)throw new AssertionError(label);}
}
