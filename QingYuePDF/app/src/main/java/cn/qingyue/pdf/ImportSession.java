package cn.qingyue.pdf;

import java.io.Closeable;
import java.io.IOException;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-local handle for one import.  The handle is deliberately independent of an
 * Activity: a recreated screen can find it through {@link ImportCoordinator#find(String)}
 * and attach a new listener.
 */
public final class ImportSession {
    public enum Phase {
        QUEUED,
        COPYING,
        EXTRACTING,
        CONVERTING,
        VALIDATING,
        READY,
        CLAIMED,
        COMPLETED,
        FAILED,
        CANCELED
    }

    public enum Unit { NONE, BYTES, PAGES }

    public interface Listener {
        /** Called on the main thread.  Callbacks may be repeated after re-attachment. */
        void changed(ImportSession session, Snapshot snapshot);
    }

    public static final class Snapshot {
        public final String sessionId;
        public final long revision;
        public final Phase phase;
        public final Unit unit;
        public final long completed;
        /** -1 means that the provider or converter cannot report a total yet. */
        public final long total;
        public final String message;
        public final String error;
        /** Optional durable success detail, for example duplicate/restored status. */
        public final String completionMessage;
        public final boolean resultAvailable;

        Snapshot(String sessionId, long revision, Phase phase, Unit unit, long completed,
                long total, String message, String error, String completionMessage,
                boolean resultAvailable) {
            this.sessionId = sessionId;
            this.revision = revision;
            this.phase = phase;
            this.unit = unit;
            this.completed = completed;
            this.total = total;
            this.message = message == null ? "" : message;
            this.error = error == null ? "" : error;
            this.completionMessage = completionMessage == null ? "" : completionMessage;
            this.resultAvailable = resultAvailable;
        }

        /** The background work has stopped; READY still needs a claim and library commit. */
        public boolean workFinished() {
            return phase == Phase.READY || phase == Phase.CLAIMED || phase == Phase.COMPLETED
                    || phase == Phase.FAILED || phase == Phase.CANCELED;
        }

        public int percent() {
            if (total <= 0) return -1;
            return (int) Math.max(0, Math.min(100, completed * 100L / total));
        }
    }

    /**
     * Files are private staging files.  The library owner should move/copy them into its
     * final layout, commit its metadata, and only then call {@link Claim#complete()}.
     */
    public static final class Result {
        public final String id;
        public final String name;
        public final String hash;
        public final int pages;
        public final long size;
        public final String format;
        public final File pdf;
        public final File originalAttachment;
        public final String originalAttachmentName;
        public final File stagingDirectory;

        Result(String id, String name, String hash, int pages, long size, String format,
                File pdf, File originalAttachment, String originalAttachmentName,
                File stagingDirectory) {
            this.id = id;
            this.name = name;
            this.hash = hash;
            this.pages = pages;
            this.size = size;
            this.format = format;
            this.pdf = pdf;
            this.originalAttachment = originalAttachment;
            this.originalAttachmentName = originalAttachmentName;
            this.stagingDirectory = stagingDirectory;
        }
    }

    /** Exactly one caller may own a READY result at a time. */
    public static final class Claim {
        public final Result result;
        private final ImportSession session;
        private final AtomicBoolean ended = new AtomicBoolean();

        Claim(ImportSession session, Result result) {
            this.session = session;
            this.result = result;
        }

        /**
         * Marks a successful external library transaction.  Safe to call repeatedly;
         * staging leftovers are cleaned only once.
         */
        public boolean complete() {
            return complete("");
        }

        /** Completes the transaction and preserves a success detail across Activity recreation. */
        public boolean complete(String completionMessage) {
            if (!ended.compareAndSet(false, true)) return session.phase() == Phase.COMPLETED;
            return session.complete(this, completionMessage);
        }

        /** Publishes a real FAILED terminal state when the external library commit failed. */
        public boolean fail(String error) {
            if (!ended.compareAndSet(false, true)) return session.phase() == Phase.FAILED;
            return session.fail(this, error);
        }

        /**
         * Makes the same staged result claimable again after a failed library transaction.
         * The caller must first roll back any files it already moved out of staging.
         */
        public boolean release() {
            if (!ended.compareAndSet(false, true)) return false;
            return session.release(this);
        }

        /** Permanently abandons this staged result. */
        public boolean discard() {
            if (!ended.compareAndSet(false, true)) return false;
            return session.discard(this);
        }
    }

    interface Dispatcher { void post(Runnable runnable); }
    interface Owner {
        void cancel(ImportSession session);
        void consumed(ImportSession session);
        void discarded(ImportSession session);
    }

    private final Object lock = new Object();
    private final String id;
    private final long startedAt;
    private final Dispatcher dispatcher;
    private final Owner owner;
    private final CopyOnWriteArrayList<WeakReference<Listener>> listeners =
            new CopyOnWriteArrayList<>();
    private volatile Snapshot snapshot;
    private Future<?> future;
    private Closeable activeResource;
    private boolean cancelRequested;
    private Result result;
    private Claim activeClaim;
    private long revision;

    ImportSession(String id, Dispatcher dispatcher, Owner owner) {
        this.id = id;
        this.startedAt = System.currentTimeMillis();
        this.dispatcher = dispatcher;
        this.owner = owner;
        snapshot = new Snapshot(id, ++revision, Phase.QUEUED, Unit.NONE, 0, -1,
                "等待导入", "", "", false);
    }

    public String id() { return id; }
    public long startedAt() { return startedAt; }
    public Snapshot snapshot() { return snapshot; }
    public Phase phase() { return snapshot.phase; }

    /** Attaches weakly and immediately replays the latest state on the main thread. */
    public void attach(Listener listener) {
        if (listener == null) return;
        detach(listener);
        WeakReference<Listener> reference = new WeakReference<>(listener);
        listeners.add(reference);
        dispatch(reference, snapshot);
    }

    public void detach(Listener listener) {
        for (WeakReference<Listener> reference : listeners) {
            Listener existing = reference.get();
            if (existing == null || existing == listener) listeners.remove(reference);
        }
    }

    /** Requests cancellation of a running/queued import, or discards an unclaimed READY result. */
    public boolean cancel() {
        Snapshot changed;
        boolean discardReady;
        Closeable resource;
        synchronized (lock) {
            Phase phase = snapshot.phase;
            if (phase == Phase.CLAIMED || phase == Phase.COMPLETED
                    || phase == Phase.FAILED || phase == Phase.CANCELED) return false;
            discardReady = phase == Phase.READY;
            cancelRequested = true;
            if (future != null) future.cancel(true);
            resource = activeResource;
            activeResource = null;
            if (discardReady) result = null;
            changed = replace(Phase.CANCELED, Unit.NONE, 0, -1, "已取消导入", "", false);
        }
        closeAsync(resource);
        publish(changed);
        if (discardReady) owner.discarded(this);
        else owner.cancel(this);
        return true;
    }

    /** Returns null unless this caller atomically acquired the READY result. */
    public Claim claim() {
        Snapshot changed;
        Claim claim;
        synchronized (lock) {
            if (snapshot.phase != Phase.READY || result == null || activeClaim != null) return null;
            claim = new Claim(this, result);
            activeClaim = claim;
            changed = replace(Phase.CLAIMED, Unit.NONE, result.pages, result.pages,
                    "等待写入文档库", "", true);
        }
        publish(changed);
        return claim;
    }

    void setFuture(Future<?> future) {
        synchronized (lock) {
            this.future = future;
            if (cancelRequested && future != null) future.cancel(true);
        }
    }

    /** Registers a provider stream so cancellation can actively unblock a pending read. */
    boolean beginResource(Closeable resource) {
        if (resource == null) return false;
        synchronized (lock) {
            if (cancelRequested || snapshot.phase == Phase.CANCELED) {
                closeQuietly(resource);
                return false;
            }
            activeResource = resource;
            return true;
        }
    }

    void endResource(Closeable resource) {
        synchronized (lock) {
            if (activeResource == resource) activeResource = null;
        }
    }

    boolean cancellationRequested() {
        synchronized (lock) { return cancelRequested || snapshot.phase == Phase.CANCELED; }
    }

    boolean progress(Phase phase, Unit unit, long completed, long total, String message) {
        Snapshot changed;
        synchronized (lock) {
            if (cancelRequested || snapshot.workFinished()) return false;
            long safeCompleted = Math.max(0, completed);
            long safeTotal = total < 0 ? -1 : Math.max(safeCompleted, total);
            if (snapshot.phase == phase && snapshot.unit == unit) {
                safeCompleted = Math.max(snapshot.completed, safeCompleted);
                if (snapshot.total >= 0)
                    safeTotal = safeTotal < 0 ? snapshot.total : Math.max(snapshot.total, safeTotal);
                if (safeTotal >= 0) safeTotal = Math.max(safeCompleted, safeTotal);
            }
            changed = replace(phase, unit, safeCompleted, safeTotal, message, "", false);
        }
        publish(changed);
        return true;
    }

    boolean ready(Result ready) {
        Snapshot changed;
        synchronized (lock) {
            if (cancelRequested || snapshot.workFinished()) return false;
            result = ready;
            changed = replace(Phase.READY, Unit.PAGES, ready.pages, ready.pages,
                    "导入处理完成", "", true);
        }
        publish(changed);
        return true;
    }

    void fail(String error) {
        Snapshot changed;
        synchronized (lock) {
            if (cancelRequested || snapshot.phase == Phase.CANCELED || snapshot.workFinished()) return;
            changed = replace(Phase.FAILED, Unit.NONE, 0, -1, "导入失败", error, false);
        }
        publish(changed);
    }

    private boolean complete(Claim claim, String completionMessage) {
        Snapshot changed;
        synchronized (lock) {
            if (snapshot.phase == Phase.COMPLETED) return true;
            if (snapshot.phase != Phase.CLAIMED || activeClaim != claim) return false;
            activeClaim = null;
            changed = replace(Phase.COMPLETED, Unit.NONE, 1, 1, "已写入文档库", "",
                    false, completionMessage);
        }
        publish(changed);
        owner.consumed(this);
        return true;
    }

    private boolean fail(Claim claim, String error) {
        Snapshot changed;
        synchronized (lock) {
            if (snapshot.phase == Phase.FAILED) return true;
            if (snapshot.phase != Phase.CLAIMED || activeClaim != claim) return false;
            activeClaim = null;
            result = null;
            String detail = error == null || error.trim().isEmpty()
                    ? "无法写入资料库" : error;
            changed = replace(Phase.FAILED, Unit.NONE, 0, -1,
                    "导入结果未写入资料库", detail, false);
        }
        publish(changed);
        owner.discarded(this);
        return true;
    }

    private boolean release(Claim claim) {
        Snapshot changed;
        synchronized (lock) {
            if (snapshot.phase != Phase.CLAIMED || activeClaim != claim || result == null) return false;
            activeClaim = null;
            changed = replace(Phase.READY, Unit.PAGES, result.pages, result.pages,
                    "导入处理完成", "", true);
        }
        publish(changed);
        return true;
    }

    private boolean discard(Claim claim) {
        Snapshot changed;
        synchronized (lock) {
            if (snapshot.phase != Phase.CLAIMED || activeClaim != claim) return false;
            activeClaim = null;
            result = null;
            cancelRequested = true;
            changed = replace(Phase.CANCELED, Unit.NONE, 0, -1, "已放弃导入结果", "", false);
        }
        publish(changed);
        owner.discarded(this);
        return true;
    }

    private Snapshot replace(Phase phase, Unit unit, long completed, long total,
            String message, String error, boolean resultAvailable) {
        return replace(phase, unit, completed, total, message, error, resultAvailable, "");
    }

    private Snapshot replace(Phase phase, Unit unit, long completed, long total,
            String message, String error, boolean resultAvailable, String completionMessage) {
        Snapshot next = new Snapshot(id, ++revision, phase, unit, completed, total,
                message, error, completionMessage, resultAvailable);
        snapshot = next;
        return next;
    }

    private void publish(Snapshot changed) {
        for (WeakReference<Listener> reference : listeners) dispatch(reference, changed);
    }

    private void dispatch(WeakReference<Listener> reference, Snapshot changed) {
        dispatcher.post(() -> {
            Listener listener = reference.get();
            if (listener == null) {
                listeners.remove(reference);
                return;
            }
            if (!listeners.contains(reference)) return;
            // Attach replay and a worker publish can be posted in either order. Never let an
            // older callback move a recreated screen backwards (for example READY -> COPYING).
            if (changed.revision < snapshot.revision) return;
            try { listener.changed(this, changed); }
            catch (RuntimeException ignored) { }
        });
    }

    private static void closeQuietly(Closeable resource) {
        if (resource == null) return;
        try { resource.close(); }
        catch (IOException | RuntimeException ignored) { }
    }

    private static void closeAsync(Closeable resource) {
        if (resource == null) return;
        Thread closer = new Thread(() -> closeQuietly(resource), "qingyue-import-close");
        closer.setDaemon(true);
        closer.start();
    }
}
