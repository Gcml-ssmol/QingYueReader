package cn.qingyue.pdf;

import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation that can also interrupt Tesseract's native recognition call. */
public final class OcrCancellation {
    public interface Stopper { void stop(); }
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private Stopper stopper;

    /**
     * Marks the operation cancelled and synchronously asks the attached native engine to stop.
     * The callback runs while holding this object's monitor. Consequently, after
     * {@link #clearStopper(Stopper)} returns, no previously-started stop callback can still be
     * entering or running, and the engine may safely recycle its native handle.
     */
    public synchronized void cancel() {
        cancelled.set(true);
        if (stopper != null) try { stopper.stop(); } catch (RuntimeException | LinkageError ignored) { }
    }

    public boolean isCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    public void throwIfCancelled() throws InterruptedIOException {
        if (isCancelled()) throw new InterruptedIOException("已取消文字识别");
    }

    synchronized void setStopper(Stopper value) {
        stopper = value;
        if (value != null && cancelled.get()) try { value.stop(); } catch (RuntimeException | LinkageError ignored) { }
    }

    synchronized void clearStopper(Stopper value) {
        if (stopper == value) stopper = null;
    }
}
