package android.graphics.pdf;
import android.graphics.*;import android.os.ParcelFileDescriptor;
public final class PdfRenderer implements AutoCloseable {
    public static volatile java.util.concurrent.CountDownLatch renderStarted,releaseRender;
    public PdfRenderer(ParcelFileDescriptor descriptor){}
    public Page openPage(int page){return new Page();}
    public void close(){}
    public static final class Page implements AutoCloseable {
        public static final int RENDER_MODE_FOR_DISPLAY=1;
        public int getWidth(){return 100;}
        public int getHeight(){return 1000;}
        public void render(Bitmap bitmap,Object clip,Matrix matrix,int mode){java.util.concurrent.CountDownLatch started=renderStarted,release=releaseRender;if(started!=null&&release!=null){started.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new RuntimeException(interrupted);}}bitmap.draw();}
        public void close(){}
    }
}
