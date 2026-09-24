package cn.qingyue.pdf;

import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicInteger;

public final class OcrCancellationTest {
    public static void main(String[] args)throws Exception {
        OcrCancellation token=new OcrCancellation();AtomicInteger stops=new AtomicInteger();
        OcrCancellation.Stopper stopper=()->stops.incrementAndGet();
        token.setStopper(stopper);token.cancel();
        if(stops.get()!=1)throw new AssertionError("native stopper not called once");
        try{token.throwIfCancelled();throw new AssertionError("cancel ignored");}
        catch(InterruptedIOException expected){}
        OcrCancellation broken=new OcrCancellation();broken.setStopper(()->{throw new NoSuchMethodError("native stop unavailable");});
        broken.cancel();
        if(!broken.isCancelled())throw new AssertionError("linkage error prevented cancellation");
        broken.setStopper(()->{throw new UnsatisfiedLinkError("native stop unavailable");});
        try{broken.throwIfCancelled();throw new AssertionError("late stopper reset cancellation");}
        catch(InterruptedIOException expected){}
        System.out.println("OcrCancellationTest passed");
    }
}
