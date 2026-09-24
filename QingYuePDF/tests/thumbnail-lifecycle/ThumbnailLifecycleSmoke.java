package cn.qingyue.pdf;

import android.graphics.Bitmap;
import android.os.Handler;
import android.graphics.pdf.PdfRenderer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Exercises the production provider while callbacks retain ImageView-like bitmap references. */
public final class ThumbnailLifecycleSmoke {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static Bitmap request(PdfThumbnailProvider provider,int page)throws Exception {
        final Bitmap[] delivered=new Bitmap[1];final String[] error=new String[1];
        provider.request(page,320,(number,bitmap,failure)->{delivered[0]=bitmap;error[0]=failure;});
        Handler.drainOne();
        check(error[0]==null && delivered[0]!=null,"thumbnail " + page + " failed: " + error[0]);
        return delivered[0];
    }
    public static void main(String[] args)throws Exception {
        File valid=File.createTempFile("thumbnail-lifecycle-",".pdf");
        try {
            List<Bitmap> attached=new ArrayList<>();
            try(PdfThumbnailProvider provider=new PdfThumbnailProvider(valid)) {
                for(int page=0;page<5;page++)attached.add(request(provider,page));
                long bytes=0;for(Bitmap bitmap:attached)bytes+=bitmap.getAllocationByteCount();
                check(bytes>12L*1024*1024,"fixture did not exceed maximum cache budget: " + bytes);
                for(Bitmap bitmap:attached){check(!bitmap.isRecycled(),"cache eviction recycled a delivered bitmap");bitmap.draw();}
            }
            for(Bitmap bitmap:attached){check(!bitmap.isRecycled(),"close recycled a delivered bitmap");bitmap.draw();}

            File missing=new File(valid.getParentFile(),"missing-thumbnail-"+System.nanoTime()+".pdf");
            try(PdfThumbnailProvider provider=new PdfThumbnailProvider(missing)) {
                final int[] calls={0};
                provider.request(0,320,(page,bitmap,error)->{calls[0]++;check(bitmap==null,"failed render delivered bitmap");check(error!=null,"failed render omitted error");});
                Handler.drainOne();
                check(calls[0]==1,"opening error callback count");
            }

            try(PdfThumbnailProvider scrolling=new PdfThumbnailProvider(valid)){
                PdfRenderer.renderStarted=new CountDownLatch(1);PdfRenderer.releaseRender=new CountDownLatch(1);final int[] offscreen={0},visible={0};
                scrolling.request(8,160,(page,bitmap,error)->offscreen[0]++);
                check(PdfRenderer.renderStarted.await(5,TimeUnit.SECONDS),"render did not start");
                scrolling.cancelOutside(9,9);scrolling.request(9,160,(page,bitmap,error)->{check(page==9&&bitmap!=null&&error==null,"wrong visible thumbnail");visible[0]++;});
                PdfRenderer.releaseRender.countDown();Handler.drainOne();Handler.drainOne();
                check(offscreen[0]==0&&visible[0]==1,"offscreen cancellation delivered to detached row or canceled visible page");
            }finally{if(PdfRenderer.releaseRender!=null)PdfRenderer.releaseRender.countDown();PdfRenderer.renderStarted=null;PdfRenderer.releaseRender=null;}

            PdfThumbnailProvider closed=new PdfThumbnailProvider(valid);
            final int[] lateCalls={0};
            closed.request(0,320,(page,bitmap,error)->lateCalls[0]++);
            closed.close();
            // A worker may post before close; either way no bitmap may reach the caller.
            try {Handler.drainOne();} catch(AssertionError timeout) { /* no post after worker cancellation */ }
            check(lateCalls[0]==0,"closed provider delivered a thumbnail");
            closed.request(1,320,(page,bitmap,error)->lateCalls[0]++);
            check(lateCalls[0]==0,"request after close delivered a thumbnail");
            System.out.println("ThumbnailLifecycleSmoke passed: delivered bitmaps survive eviction and close; open errors and late callbacks are bounded.");
        } finally {valid.delete();}
    }
}
