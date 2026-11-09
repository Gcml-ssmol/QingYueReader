package cn.qingyue.pdf;
import android.graphics.Bitmap;import java.io.File;import java.io.IOException;
public final class OcrImageLoader {
    public static final class Loaded {public final Bitmap bitmap;Loaded(Bitmap b){bitmap=b;}}
    public static Bitmap template;public static int ordinaryCalls,deskewCalls;
    public static Loaded renderPdfPage(File pdf,int page,OcrCancellation token)throws IOException{
        token.throwIfCancelled();ordinaryCalls++;return new Loaded(copy());
    }
    public static Loaded renderPdfPageForDeskew(File pdf,int page,OcrCancellation token)throws IOException{
        token.throwIfCancelled();deskewCalls++;return new Loaded(copy());
    }
    private static Bitmap copy(){Bitmap source=template==null?new Bitmap(400,300):template;
        Bitmap target=new Bitmap(source.getWidth(),source.getHeight());
        for(int y=0;y<source.getHeight();y++)for(int x=0;x<source.getWidth();x++)
            target.setPixel(x,y,source.getPixel(x,y));return target;}
}
