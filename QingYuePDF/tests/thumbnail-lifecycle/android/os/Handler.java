package android.os;
import java.util.concurrent.*;
public final class Handler {
    private static final BlockingQueue<Runnable> QUEUE=new LinkedBlockingQueue<>();
    public Handler(Looper ignored){}
    public boolean post(Runnable task){QUEUE.add(task);return true;}
    public void removeCallbacksAndMessages(Object token){QUEUE.clear();}
    public static void drainOne()throws InterruptedException{Runnable task=QUEUE.poll(5,TimeUnit.SECONDS);if(task==null)throw new AssertionError("thumbnail worker did not post");task.run();}
}
