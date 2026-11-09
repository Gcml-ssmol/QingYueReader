package cn.qingyue.pdf;
import android.content.Context;import java.io.File;import java.io.IOException;
public final class PdfEpubExporter {
    public interface OcrAdapter { OcrData recognize(Context context,File source,int page,OcrCancellation token)throws IOException; }
    public static final class OcrData {
        public final String text,hocr;public final int confidence;
        public OcrData(String text,int confidence,String hocr){this.text=text;this.confidence=confidence;this.hocr=hocr;}
    }
}
