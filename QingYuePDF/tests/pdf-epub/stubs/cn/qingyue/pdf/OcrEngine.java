package cn.qingyue.pdf;
import android.content.Context;
import android.graphics.Bitmap;
import java.io.IOException;
public final class OcrEngine {
    public enum Language { MIXED }
    public enum Layout { BODY }
    public static final class Result { public String text, hocr; public int confidence; }
    public static Result recognize(Context c, Bitmap b, int p, Language l, Layout y,
            OcrCancellation token, Object progress) throws IOException { throw new IOException("desktop OCR stub"); }
}
