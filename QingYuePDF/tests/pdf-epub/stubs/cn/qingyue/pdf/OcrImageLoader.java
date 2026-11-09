package cn.qingyue.pdf;
import android.graphics.Bitmap;
import java.io.File;
import java.io.IOException;
public final class OcrImageLoader {
    public static final class Loaded { public Bitmap bitmap; }
    public static Loaded renderPdfPage(File f, int p, OcrCancellation t) throws IOException {
        t.throwIfCancelled();Loaded result=new Loaded();result.bitmap=new Bitmap(p==2);return result;
    }
    public static Loaded renderPdfPreview(File f, int p, OcrCancellation t) throws IOException { return renderPdfPage(f,p,t); }
}
