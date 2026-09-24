package cn.qingyue.pdf;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.zip.*;
import org.apache.poi.hwpf.HWPFDocument;

public final class DocumentImportTest {
    static int checks;
    public static void main(String[] args)throws Exception {
        File fixtures=new File(args[0]),tmp=new File(args[1]);tmp.mkdirs();
        check(DocumentText.extract(new File(fixtures,"SampleDoc.doc"),"SampleDoc.doc",null).text.contains("I am a test document"),"DOC97 text");
        check(DocumentText.extract(new File(fixtures,"simple.doc"),"renamed.bin",null).format.equals("DOC"),"DOC magic instead of filename");
        check(DocumentText.extract(new File(fixtures,"Word6.doc"),"Word6.doc",null).text.contains("quick brown fox"),"genuine Word6 file");
        check(DocumentText.extract(new File(fixtures,"SampleDoc.docx"),"SampleDoc.docx",null).text.contains("This is page two"),"genuine DOCX");
        File chineseDoc=new File(tmp,"Chinese.doc");
        try(InputStream in=new FileInputStream(new File(fixtures,"simple.doc"));HWPFDocument doc=new HWPFDocument(in);OutputStream out=new FileOutputStream(chineseDoc)) {doc.getRange().insertBefore("晴阅中文测试\r图片与网格笔记\r");doc.write(out);}
        check(DocumentText.extract(chineseDoc,"中文.doc",null).text.contains("晴阅中文测试"),"DOC CJK Unicode");
        File utf8=new File(tmp,"utf8.txt");Files.write(utf8.toPath(),"晴阅\n第二行\r\n第三行".getBytes(StandardCharsets.UTF_8));
        check(DocumentText.extract(utf8,"utf8.txt",null).text.equals("晴阅\n第二行\n第三行"),"UTF8 and newline normalization");
        File gbk=new File(tmp,"gbk.txt");Files.write(gbk.toPath(),"中文编码测试".getBytes(Charset.forName("GB18030")));
        check(DocumentText.extract(gbk,"gbk.txt",null).text.equals("中文编码测试"),"Chinese GB18030");
        File utf16=new File(tmp,"utf16.txt");try(OutputStream out=new FileOutputStream(utf16)){out.write(new byte[]{(byte)255,(byte)254});out.write("晴阅 Unicode".getBytes(StandardCharsets.UTF_16LE));}
        check(DocumentText.extract(utf16,"utf16.txt",null).text.equals("晴阅 Unicode"),"UTF16 BOM");
        String prefix="<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>";
        String suffix="</w:body></w:document>";
        File docx=new File(tmp,"中文.docx");zip(docx,prefix+"<w:p><w:r><w:t>晴阅中文</w:t><w:tab/><w:t>搜索</w:t><w:br/><w:t>第二行</w:t></w:r></w:p><w:del><w:r><w:t>删除文字</w:t></w:r></w:del><w:p><w:r><w:t>保留文字</w:t></w:r></w:p>"+suffix);
        String text=DocumentText.extract(docx,"中文.docx",null).text;
        check(text.contains("晴阅中文\t搜索\n第二行\n") && text.contains("保留文字") && !text.contains("删除文字"),"DOCX paragraph/tab/break/CJK/deleted text");
        File invalid=new File(tmp,"notWord.doc");Files.write(invalid.toPath(),"Not a Word document".getBytes(StandardCharsets.UTF_8));reject(invalid,"notWord.doc","fake DOC");
        File badOle=new File(tmp,"bad.doc");Files.write(badOle.toPath(),new byte[]{(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,0x1a,(byte)0xe1});reject(badOle,"bad.doc","corrupt OLE");
        File malicious=new File(tmp,"entity.docx");zip(malicious,"<!DOCTYPE w:document [<!ENTITY secret SYSTEM 'file:///must-not-read'>]>"+prefix+"<w:p><w:r><w:t>&secret;</w:t></w:r></w:p>"+suffix);reject(malicious,"entity.docx","XML external entity");
        File utf16Doctype=new File(tmp,"entity-utf16.docx");zipBytes(utf16Doctype,("\ufeff<?xml version=\"1.0\" encoding=\"UTF-16\"?><!DOCTYPE w:document [<!ENTITY bomb 'abc'>]>"+prefix+"<w:p><w:r><w:t>&bomb;</w:t></w:r></w:p>"+suffix).getBytes(StandardCharsets.UTF_16LE));reject(utf16Doctype,"entity.docx","UTF16 DTD");
        File binary=new File(tmp,"binary.txt");Files.write(binary.toPath(),new byte[]{0,1,2,3});reject(binary,"binary.txt","binary TXT");
        File blank=new File(tmp,"blank.txt");Files.write(blank.toPath(),new byte[0]);reject(blank,"blank.txt","empty content");
        File large=new File(tmp,"large.txt");char[] chars=new char[DocumentText.MAX_CHARACTERS+1];java.util.Arrays.fill(chars,'A');Files.write(large.toPath(),new String(chars).getBytes(StandardCharsets.UTF_8));reject(large,"large.txt","text limit");
        File zipBomb=new File(tmp,"huge.docx");zip(zipBomb,prefix+"<w:p><w:r><w:t>"+new String(chars)+"</w:t></w:r></w:p>"+suffix);reject(zipBomb,"huge.docx","decompressed text limit");
        check(DocumentText.prepareSharedText("\u6674\u9605\r\n\u7b2c\u4e8c\u884c\u000b\u7b2c\u4e09\u884c").equals("\u6674\u9605\n\u7b2c\u4e8c\u884c\n\u7b2c\u4e09\u884c"),"shared text normalization");
        String exact=new String(chars,0,DocumentText.MAX_CHARACTERS);
        check(DocumentText.prepareSharedText(exact).length()==DocumentText.MAX_CHARACTERS,"shared text exact character limit");
        rejectShared(new String(chars),"shared text character limit");
        rejectShared(" \t\n\u200b ","shared text visible content");
        rejectShared("abc\u0000def","shared text binary character");
        final int[] callbacks={0};
        try {
            DocumentText.extract(utf8,"utf8.txt","text/plain",new DocumentText.Monitor(){
                public boolean isCancelled(){return callbacks[0]>0;}
                public void progress(long completed,long total){callbacks[0]++;}
            });
            throw new AssertionError("cancelled extraction was accepted");
        } catch(InterruptedIOException expected) {check(expected.getMessage().contains("\u53d6\u6d88"),"cooperative extraction cancellation");}
        System.out.println("DocumentImportTest: "+checks+" checks passed; genuine DOC/Word6/DOCX, shared text, cancellation, encodings, limits and invalid inputs");
    }
    static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    static void reject(File file,String name,String label)throws Exception {checks++;try{DocumentText.extract(file,name,null);throw new AssertionError(label+" was accepted");}catch(IOException expected){}}
    static void rejectShared(CharSequence text,String label)throws Exception {checks++;try{DocumentText.prepareSharedText(text);throw new AssertionError(label+" was accepted");}catch(IOException expected){}}
    static void zip(File file,String xml)throws Exception {zipBytes(file,xml.getBytes(StandardCharsets.UTF_8));}
    static void zipBytes(File file,byte[] xml)throws Exception {try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(file))){out.putNextEntry(new ZipEntry("word/document.xml"));out.write(xml);out.closeEntry();}}
}
