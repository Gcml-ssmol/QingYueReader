package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.File;
import java.io.InterruptedIOException;

public final class EpubOcrLogicTest {
    public static void main(String[] args) throws Exception {
        byte[] horizontal = lines(400, 300, 0);
        byte[] tilted = lines(400, 300, 1.5);
        check(OcrDeskew.estimateInk(horizontal, 400, 300, new OcrCancellation()) == 0,
                "horizontal image should not rotate");
        double angle = OcrDeskew.estimateInk(tilted, 400, 300, new OcrCancellation());
        check(Math.abs(angle - 1.5) <= 0.5, "tilted lines angle: " + angle);
        OcrCancellation cancelled = new OcrCancellation();
        cancelled.cancel();
        try {
            OcrDeskew.estimateInk(tilted, 400, 300, cancelled);
            throw new AssertionError("missing cancellation");
        } catch (java.io.InterruptedIOException expected) { }
        OcrEngine.Result original = new OcrEngine.Result("中文正文", "<hocr/>", 75, "5");
        check(!OcrDeskew.prefer(original,
                new OcrEngine.Result("中文", "<hocr/>", 90, "5")), "lost text");
        check(!OcrDeskew.prefer(original,
                new OcrEngine.Result("中文正文", "<hocr/>", 77, "5")), "weak gain");
        check(OcrDeskew.prefer(original,
                new OcrEngine.Result("中文正文！", "<hocr/>", 80, "5")), "strong gain");
        check(!EpubOcrSession.cacheKey(false).equals(EpubOcrSession.cacheKey(true)), "cache separation");
        sessionLifecycle();
        System.out.println("EpubOcrLogicTest OK");
    }

    private static void sessionLifecycle() throws Exception {
        Context context=new Context();File source=new File("stub.pdf");
        OcrImageLoader.template=bitmap(lines(400,300,1.5),400,300);
        OcrImageLoader.ordinaryCalls=OcrImageLoader.deskewCalls=0;
        OcrModelInstaller.ensureCalls=0;TessBaseAPI.reset();
        EpubOcrSession off=new EpubOcrSession(false);
        PdfEpubExporter.OcrData first=off.recognize(context,source,0,new OcrCancellation());
        PdfEpubExporter.OcrData second=off.recognize(context,source,1,new OcrCancellation());
        check(first.text.equals("原图中文正文")&&second.text.equals(first.text),"default-off OCR changed text");
        check(OcrImageLoader.ordinaryCalls==2&&OcrImageLoader.deskewCalls==0,"default-off rendered deskew path");
        check(TessBaseAPI.inits==1&&TessBaseAPI.clears==2&&TessBaseAPI.recognitions==2,
                "one-worker engine not reused and cleared per page");
        check(OcrModelInstaller.ensureCalls==1,"model assets verified on every page");
        off.close();check(TessBaseAPI.recycles==1,"engine not recycled on close");
        try{off.recognize(context,source,2,new OcrCancellation());throw new AssertionError("closed session used");}
        catch(java.io.IOException expected){}

        TessBaseAPI.reset();OcrImageLoader.ordinaryCalls=OcrImageLoader.deskewCalls=0;
        OcrModelInstaller.ensureCalls=0;
        EpubOcrSession on=new EpubOcrSession(true);
        PdfEpubExporter.OcrData improved=on.recognize(context,source,0,new OcrCancellation());
        check(improved.text.equals(TessBaseAPI.rotatedText),"strong deskew candidate not chosen");
        check(OcrImageLoader.deskewCalls==1&&OcrImageLoader.ordinaryCalls==0,"deskew source path not used");
        check(TessBaseAPI.inits==1&&TessBaseAPI.recognitions==2&&TessBaseAPI.clears==2,
                "deskew candidate did not reuse engine");
        TessBaseAPI.rotatedText="短";
        PdfEpubExporter.OcrData rejected=on.recognize(context,source,1,new OcrCancellation());
        check(rejected.text.equals(TessBaseAPI.baselineText),"shortened deskew candidate selected");
        check(TessBaseAPI.inits==1,"candidate caused reinitialization");
        OcrCancellation cancel=new OcrCancellation();TessBaseAPI.onText=cancel::cancel;
        try{on.recognize(context,source,2,cancel);throw new AssertionError("native cancellation missing");}
        catch(InterruptedIOException expected){}
        check(TessBaseAPI.stops>0&&TessBaseAPI.recycles==1,"cancel did not stop/discard native engine");
        TessBaseAPI.onText=null;
        on.recognize(context,source,3,new OcrCancellation());
        check(TessBaseAPI.inits==2&&OcrModelInstaller.ensureCalls==2,"post-cancel session was not rebuilt");
        on.close();check(TessBaseAPI.recycles==2,"rebuilt session not closed");
    }

    private static Bitmap bitmap(byte[] ink,int width,int height){Bitmap out=new Bitmap(width,height);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)if(ink[y*width+x]!=0)out.setPixel(x,y,Color.BLACK);
        return out;}

    private static byte[] lines(int width, int height, double angle) {
        byte[] image = new byte[width * height];
        double slope = Math.tan(Math.toRadians(angle));
        for (int line = 45; line < height - 30; line += 32) {
            for (int x = 30; x < width - 30; x++) {
                int y = (int)Math.round(line + (x - width / 2.0) * slope);
                for (int t = 0; t < 2; t++) image[(y + t) * width + x] = 1;
            }
        }
        return image;
    }

    private static void check(boolean okay, String message) {
        if (!okay) throw new AssertionError(message);
    }
}
