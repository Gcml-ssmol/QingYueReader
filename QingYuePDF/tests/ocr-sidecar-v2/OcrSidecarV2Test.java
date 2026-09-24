package cn.qingyue.pdf;

import android.content.Context;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Desktop sidecar compatibility and invalidation checks against production storage. */
public final class OcrSidecarV2Test {
    private static final String ID="11111111-1111-4111-8111-111111111111";
    private static final String ORIGINAL=repeat('a'),PDF=sha("actual PDF bytes"),CHANGED=repeat('c');
    private static String sha(String text){try{byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format("%02x",b&255));return out.toString();}catch(Exception e){throw new AssertionError(e);}}
    private static String repeat(char c){char[] chars=new char[64];Arrays.fill(chars,c);return new String(chars);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void invalid(Io action)throws Exception{try{action.run();throw new AssertionError("invalid input accepted");}catch(IOException expected){}}
    private interface Io {void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("qingyue-ocr-v2-");Context context=new Context(root.toFile());
        Files.write(root.resolve(ID+".pdf"),"actual PDF bytes".getBytes("UTF-8"));
        LibraryItem item=new LibraryItem();item.id=ID;item.name="converted.txt";item.originalName=item.name;item.hash=ORIGINAL;item.created=item.modified=1;LibraryStore.save(context,Collections.singletonList(item));
        OcrSidecar.save(context,ID,0,ORIGINAL,"旧转换件 中文 English");
        check(OcrSidecar.load(context,ID,0,ORIGINAL).contains("中文"),"legacy load broke");
        check(OcrSidecar.loadForPdf(context,ID,0,ORIGINAL,PDF,true).contains("English"),"legacy converted correction hidden");
        check(OcrSidecar.loadForPdf(context,ID,0,ORIGINAL,PDF,false).isEmpty(),"unverified converted OCR indexed");
        OcrSidecar.save(context,ID,1,PDF,"旧 PDF");
        check(OcrSidecar.loadForPdf(context,ID,1,ORIGINAL,PDF,false).equals("旧 PDF"),"legacy matching PDF rejected");
        OcrSidecar.saveVerified(context,ID,0,ORIGINAL,PDF,"新版 中文 English");
        check(OcrSidecar.loadForPdf(context,ID,0,ORIGINAL,PDF,false).contains("新版"),"verified OCR missing");
        check(OcrSidecar.loadForPdf(context,ID,0,ORIGINAL,CHANGED,true).isEmpty(),"changed PDF accepted");
        check(OcrSidecar.loadForPdf(context,ID,0,CHANGED,PDF,true).isEmpty(),"wrong original identity accepted");
        check(OcrSidecar.load(context,ID,0,ORIGINAL).isEmpty(),"v1 reader misread v2 header");
        invalid(()->OcrSidecar.saveVerified(context,ID,0,ORIGINAL,"bad","x"));
        invalid(()->OcrSidecar.saveVerified(context,ID,0,ORIGINAL,CHANGED,"过期任务不得覆盖"));
        invalid(()->OcrSidecar.loadForPdf(context,ID,0,ORIGINAL,"bad",true));
        File sidecar=new File(OcrSidecar.directory(context,ID),"page-2.txt");
        Files.write(sidecar.toPath(),new byte[]{(byte)0xc3,(byte)0x28});
        invalid(()->OcrSidecar.loadForPdf(context,ID,2,ORIGINAL,PDF,false));
        Files.write(sidecar.toPath(),("QY-OCR-2\n"+ORIGINAL+"\n"+PDF.substring(0,20)).getBytes("UTF-8"));
        check(OcrSidecar.loadForPdf(context,ID,2,ORIGINAL,PDF,true).isEmpty(),"truncated v2 header accepted");
        System.out.println("OcrSidecarV2Test passed: legacy converted preservation, strict search, verified round trip, changed PDF/identity, malformed data.");
    }
}
