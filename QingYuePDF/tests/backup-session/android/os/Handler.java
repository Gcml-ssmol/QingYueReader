package android.os;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Handler {
    private static final ExecutorService MAIN = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "desktop-main");
        thread.setDaemon(true);
        return thread;
    });

    public Handler(Looper looper) { }
    public boolean post(Runnable runnable) { MAIN.execute(runnable); return true; }
}
