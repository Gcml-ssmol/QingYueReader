package cn.qingyue.pdf;

import java.io.*;
import java.security.*;

/** Actual PDF bytes are the authority for OCR sidecar validity. */
public final class RecognitionOcrFingerprint {
    private RecognitionOcrFingerprint() { }
    public static String sha256(File source, GlobalSearchSource.Cancellation token) throws IOException {
        MessageDigest digest;
        try { digest=MessageDigest.getInstance("SHA-256"); }
        catch(NoSuchAlgorithmException e){throw new IOException(e);}
        byte[] buffer=new byte[65536];
        try(InputStream input=new FileInputStream(source)){
            int count;
            while((count=input.read(buffer))!=-1){
                if(token!=null&&token.isCancelled())throw new InterruptedIOException("搜索索引已取消");
                digest.update(buffer,0,count);
            }
        }
        StringBuilder out=new StringBuilder();
        for(byte value:digest.digest())out.append(String.format("%02x",value&255));
        return out.toString();
    }
}
