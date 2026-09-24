package cn.qingyue.pdf;

import android.content.Context;
import java.io.*;
import java.security.*;

/** Searches current per-page OCR corrections only while their PDF bytes still match. */
public final class RecognitionOcrSidecarProvider implements GlobalSearchContentProvider {
    @Override public String providerId(){return "ocr-sidecar-main-v1";}
    @Override public String channel(){return GlobalSearchSource.CHANNEL_OCR_TEXT;}
    @Override public boolean supports(GlobalSearchLibrarySource.LibraryDocument d){return d!=null&&!d.isNote();}
    @Override public String fingerprint(Context c,GlobalSearchLibrarySource.LibraryDocument d,
            GlobalSearchSource.Cancellation token)throws IOException {
        File dir=OcrSidecar.directory(c,d.id);
        File[] files=dir.listFiles();if(files==null||files.length==0)return "";
        String hash=RecognitionOcrFingerprint.sha256(d.contentFile,token);
        MessageDigest digest=digest();
        boolean found=false;
        for(int page=0;page<d.pages;page++){
            if(token!=null&&token.isCancelled())throw new InterruptedIOException("搜索索引已取消");
            String value=OcrSidecar.loadForPdf(c,d.id,page,d.hash,hash,false);
            if(!value.isEmpty()){
                found=true;
                digest.update((byte)(page>>>24));digest.update((byte)(page>>>16));
                digest.update((byte)(page>>>8));digest.update((byte)page);
                digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return found?hash+":"+hex(digest.digest()):"";
    }
    @Override public void read(Context c,GlobalSearchLibrarySource.LibraryDocument d,Sink sink,
            GlobalSearchSource.Cancellation token)throws IOException {
        String hash=RecognitionOcrFingerprint.sha256(d.contentFile,token);
        for(int page=0;page<d.pages;page++){
            if(token!=null&&token.isCancelled())throw new InterruptedIOException("搜索索引已取消");
            String value=OcrSidecar.loadForPdf(c,d.id,page,d.hash,hash,false);
            if(!value.isEmpty())sink.accept(new Text(page,"OCR",value,
                    GlobalSearchSource.JUMP_PDF_PAGE,""));
        }
    }
    private static MessageDigest digest()throws IOException {
        try{return MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException e){throw new IOException(e);}
    }
    private static String hex(byte[] bytes){
        StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b&255));
        return out.toString();
    }
}
