package android.graphics.pdf;
import android.graphics.*;import android.os.ParcelFileDescriptor;
public class PdfRenderer implements AutoCloseable {
    public PdfRenderer(ParcelFileDescriptor descriptor){}public int getPageCount(){return 1;}public Page openPage(int page){return new Page();}public void close(){}
    public static class Page implements AutoCloseable {public static final int RENDER_MODE_FOR_PRINT=2;public static boolean failWithOutOfMemory;public int getWidth(){return 1000;}public int getHeight(){return 1400;}public void render(Bitmap target,Object clip,Matrix transform,int mode){if(failWithOutOfMemory)throw new OutOfMemoryError("simulated renderer OOM");}public void close(){}}
}
