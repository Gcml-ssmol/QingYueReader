package cn.qingyue.pdf;

import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-level owner for backup and restore work that must outlive an Activity instance.
 *
 * <p>An Activity keeps only {@link Session#id()}, finds the same session after recreation, and
 * attaches a new listener.  Attach always replays the latest immutable snapshot.  Terminal
 * sessions deliberately remain in the registry until the UI acknowledges them with
 * {@link #forget(String)}.</p>
 */
public final class BackupCoordinator {
    public enum Kind { BACKUP, RESTORE }

    public enum State { RUNNING, COMPLETED, FAILED, CANCELED }

    /** Work runs on the coordinator's single background thread. */
    public interface Work {
        BackupManager.Result run(BackupManager.Progress progress,
                                 BackupManager.Cancellation cancellation) throws Exception;
    }

    public interface Listener {
        /** Called on the main thread. Callbacks may be repeated after re-attachment. */
        void changed(Session session, Snapshot snapshot);
    }

    /** Immutable state suitable for rendering directly after Activity recreation. */
    public static final class Snapshot {
        public final String sessionId;
        public final Kind kind;
        public final long startedAt;
        public final long revision;
        public final State state;
        public final String phase;
        public final String detail;
        public final long completedBytes;
        /** -1 means the operation cannot report a byte total yet. */
        public final long totalBytes;
        public final int completedEntries;
        /** -1 means the operation cannot report an entry total yet. */
        public final int totalEntries;
        public final BackupManager.Result result;
        public final Throwable error;
        /** True after cancel() has asked the still-running Work to stop cooperatively. */
        public final boolean cancellationRequested;

        private Snapshot(String sessionId, Kind kind, long startedAt, long revision,
                         State state, String phase, String detail, long completedBytes,
                         long totalBytes, int completedEntries, int totalEntries,
                         BackupManager.Result result, Throwable error,
                         boolean cancellationRequested) {
            this.sessionId = sessionId;
            this.kind = kind;
            this.startedAt = startedAt;
            this.revision = revision;
            this.state = state;
            this.phase = clean(phase);
            this.detail = clean(detail);
            this.completedBytes = completedBytes;
            this.totalBytes = totalBytes;
            this.completedEntries = completedEntries;
            this.totalEntries = totalEntries;
            this.result = result;
            this.error = error;
            this.cancellationRequested = cancellationRequested;
        }

        public boolean isTerminal() { return state != State.RUNNING; }

        public int percent() {
            if (totalBytes <= 0) return -1;
            return (int) Math.max(0, Math.min(100,
                    completedBytes * 100L / totalBytes));
        }

        private static String clean(String value) { return value == null ? "" : value; }
    }

    /** Process-local handle for a single backup or restore operation. */
    public static final class Session {
        private final Object lock = new Object();
        private final String id;
        private final Kind kind;
        private final long startedAt;
        private final long order;
        private final CopyOnWriteArrayList<WeakReference<Listener>> listeners =
                new CopyOnWriteArrayList<>();
        private volatile Snapshot snapshot;
        private volatile boolean cancellationRequested;
        private long revision;

        private Session(String id, Kind kind, long startedAt, long order) {
            this.id = id;
            this.kind = kind;
            this.startedAt = startedAt;
            this.order = order;
            snapshot = next(State.RUNNING, "", "", 0, -1, 0, -1, null, null);
        }

        public String id() { return id; }
        public Kind kind() { return kind; }
        public long startedAt() { return startedAt; }
        public Snapshot snapshot() { return snapshot; }
        public State state() { return snapshot.state; }
        public boolean cancellationRequested() { return cancellationRequested; }

        /** Attaches weakly and immediately replays the latest snapshot on the main thread. */
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

        /**
         * Requests cooperative cancellation.  Work must observe the supplied Cancellation token.
         * State remains RUNNING until Work stops. If Work has already committed and returns a
         * result, that successful result wins the race and the session becomes COMPLETED.
         */
        public boolean cancel() {
            Snapshot changed;
            synchronized (lock) {
                if (snapshot.isTerminal() || cancellationRequested) return false;
                cancellationRequested = true;
                changed = next(State.RUNNING, snapshot.phase, snapshot.detail,
                        snapshot.completedBytes, snapshot.totalBytes,
                        snapshot.completedEntries, snapshot.totalEntries, null, null);
            }
            publish(changed);
            return true;
        }

        private void execute(Work work) {
            if (snapshot.isTerminal()) return;
            if (cancellationRequested) {
                finishCanceled();
                return;
            }
            BackupManager.Progress progress = new BackupManager.Progress() {
                @Override public void onProgress(String phase, String detail,
                                                 long completedBytes, long totalBytes,
                                                 int completedEntries, int totalEntries) {
                    progress(phase, detail, completedBytes, totalBytes,
                            completedEntries, totalEntries);
                }
            };
            BackupManager.Cancellation cancellation = new BackupManager.Cancellation() {
                @Override public boolean isCancelled() { return cancellationRequested; }
            };
            try {
                BackupManager.Result result = work.run(progress, cancellation);
                if (result == null) throw new NullPointerException("Backup work returned null");
                complete(result);
            } catch (Throwable error) {
                fail(error);
            }
        }

        private void progress(String phase, String detail, long completedBytes, long totalBytes,
                              int completedEntries, int totalEntries) {
            Snapshot changed;
            synchronized (lock) {
                if (snapshot.isTerminal() || cancellationRequested) return;
                long safeBytes = Math.max(0, completedBytes);
                long safeTotalBytes = totalBytes < 0 ? -1 : Math.max(safeBytes, totalBytes);
                int safeEntries = Math.max(0, completedEntries);
                int safeTotalEntries = totalEntries < 0
                        ? -1 : Math.max(safeEntries, totalEntries);
                changed = next(State.RUNNING, phase, detail, safeBytes, safeTotalBytes,
                        safeEntries, safeTotalEntries, null, null);
            }
            publish(changed);
        }

        private void complete(BackupManager.Result result) {
            Snapshot changed;
            synchronized (lock) {
                if (snapshot.isTerminal()) return;
                changed = next(State.COMPLETED, snapshot.phase, snapshot.detail,
                        snapshot.completedBytes, snapshot.totalBytes,
                        snapshot.completedEntries, snapshot.totalEntries, result, null);
            }
            publish(changed);
        }

        private void fail(Throwable error) {
            Snapshot changed;
            synchronized (lock) {
                if (snapshot.isTerminal()) return;
                if (cancellationRequested) {
                    changed = next(State.CANCELED, snapshot.phase, snapshot.detail,
                            snapshot.completedBytes, snapshot.totalBytes,
                            snapshot.completedEntries, snapshot.totalEntries, null, null);
                } else {
                    changed = next(State.FAILED, snapshot.phase, snapshot.detail,
                            snapshot.completedBytes, snapshot.totalBytes,
                            snapshot.completedEntries, snapshot.totalEntries, null, error);
                }
            }
            publish(changed);
        }

        private void finishCanceled() {
            Snapshot changed;
            synchronized (lock) {
                if (snapshot.isTerminal()) return;
                changed = next(State.CANCELED, snapshot.phase, snapshot.detail,
                        snapshot.completedBytes, snapshot.totalBytes,
                        snapshot.completedEntries, snapshot.totalEntries, null, null);
            }
            publish(changed);
        }

        private Snapshot next(State state, String phase, String detail,
                              long completedBytes, long totalBytes,
                              int completedEntries, int totalEntries,
                              BackupManager.Result result, Throwable error) {
            Snapshot changed = new Snapshot(id, kind, startedAt, ++revision, state,
                    phase, detail, completedBytes, totalBytes, completedEntries, totalEntries,
                    result, error, cancellationRequested);
            snapshot = changed;
            return changed;
        }

        private void publish(Snapshot changed) {
            for (WeakReference<Listener> reference : listeners) dispatch(reference, changed);
        }

        private void dispatch(final WeakReference<Listener> reference,
                              final Snapshot changed) {
            MAIN.post(new Runnable() {
                @Override public void run() {
                    Listener listener = reference.get();
                    if (listener == null) {
                        listeners.remove(reference);
                        return;
                    }
                    if (!listeners.contains(reference)) return;
                    // A progress publish can race cancellation/completion before it reaches MAIN.
                    // Never let an older callback visually roll a recreated Activity backwards.
                    if (changed.revision < snapshot.revision) return;
                    try { listener.changed(Session.this, changed); }
                    catch (RuntimeException ignored) { }
                }
            });
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "qingyue-backup");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private static final ConcurrentHashMap<String, Session> SESSIONS =
            new ConcurrentHashMap<>();
    private static final AtomicLong ORDER = new AtomicLong();
    private static final Object START_LOCK = new Object();

    private BackupCoordinator() { }

    public static Session start(Kind kind, Work work) {
        if (kind == null) throw new IllegalArgumentException("kind == null");
        if (work == null) throw new IllegalArgumentException("work == null");
        final Session session;
        synchronized (START_LOCK) {
            for (Session existing : SESSIONS.values()) {
                if (!existing.snapshot().isTerminal())
                    throw new IllegalStateException("A backup or restore session is already active");
            }
            String id = UUID.randomUUID().toString();
            session = new Session(id, kind, System.currentTimeMillis(),
                    ORDER.incrementAndGet());
            SESSIONS.put(id, session);
        }
        WORKER.execute(new Runnable() {
            @Override public void run() { session.execute(work); }
        });
        return session;
    }

    public static Session find(String sessionId) {
        return sessionId == null ? null : SESSIONS.get(sessionId);
    }

    /** All retained sessions, newest first. */
    public static List<Session> sessions() {
        ArrayList<Session> result = new ArrayList<>(SESSIONS.values());
        Collections.sort(result, new Comparator<Session>() {
            @Override public int compare(Session left, Session right) {
                return Long.compare(right.order, left.order);
            }
        });
        return Collections.unmodifiableList(result);
    }

    /** Newest retained session, or null when the registry is empty. */
    public static Session latest() {
        List<Session> retained = sessions();
        return retained.isEmpty() ? null : retained.get(0);
    }

    /** Newest retained session of the requested kind. */
    public static Session latest(Kind kind) {
        if (kind == null) return null;
        for (Session session : sessions()) if (session.kind == kind) return session;
        return null;
    }

    /** Removes a terminal session after its outcome has been consumed by the UI. */
    public static boolean forget(String sessionId) {
        Session session = find(sessionId);
        return session != null && session.snapshot().isTerminal()
                && SESSIONS.remove(sessionId, session);
    }
}
