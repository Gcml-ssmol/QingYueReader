package cn.qingyue.pdf;

import java.io.*;
import java.nio.file.Files;

public final class RecognitionOcrFingerprintTest {
    public static void main(String[] args)throws Exception {
        File source=File.createTempFile("qingyue-ocr-fingerprint-",".pdf");
        try {
            Files.write(source.toPath(),new byte[]{1,2,3,4});
            long modified=source.lastModified();
            String before=RecognitionOcrFingerprint.sha256(source,()->false);
            Files.write(source.toPath(),new byte[]{1,2,3,5});
            source.setLastModified(modified);
            String after=RecognitionOcrFingerprint.sha256(source,()->false);
            if(before.equals(after))throw new AssertionError("same-length, same-mtime PDF bytes must invalidate OCR");
            try {RecognitionOcrFingerprint.sha256(source,()->true);
                throw new AssertionError("cancelled PDF digest accepted");
            } catch(InterruptedIOException expected) { }
            System.out.println("RecognitionOcrFingerprintTest passed");
        } finally {source.delete();}
    }
}
