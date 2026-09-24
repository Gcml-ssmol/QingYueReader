package cn.qingyue.pdf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class BackupCoordinatorTest {
    private static int checks;

    public static void main(String[] args) {
        try {
            testRotationAndReplay();
            testCooperativeCancellation();
            testSuccessfulWorkWinsCancellationRace();
            testFailureRetention();
            System.out.println("BackupCoordinatorTest: " + checks
                    + " checks passed; replay, rotation, cancellation and retention");
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }

    private static void testRotationAndReplay() throws Exception {
        CountDownLatch progressSent = new CountDownLatch(1);
        CountDownLatch finishWork = new CountDownLatch(1);
        BackupManager.Result expected = result(4, 6, 1024);
        BackupCoordinator.Session session = BackupCoordinator.start(
                BackupCoordinator.Kind.BACKUP, (progress, cancellation) -> {
                    progress.onProgress("copy", "正在写入", 512, 1024, 2, 6);
                    progressSent.countDown();
                    await(finishWork, "release backup work");
                    return expected;
                });

        check(progressSent.await(3, TimeUnit.SECONDS), "work reports progress");
        CountDownLatch firstReplay = new CountDownLatch(1);
        List<BackupCoordinator.Snapshot> oldScreen = new ArrayList<>();
        BackupCoordinator.Listener first = (source, snapshot) -> {
            oldScreen.add(snapshot);
            firstReplay.countDown();
        };
        session.attach(first);
        check(firstReplay.await(3, TimeUnit.SECONDS), "first Activity receives replay");
        check(oldScreen.get(oldScreen.size() - 1).completedBytes == 512,
                "running progress survives late attach");
        check(Thread.currentThread().getName().equals("main"),
                "test itself remains off callback dispatcher");

        session.detach(first);
        int detachedCount = oldScreen.size();
        CountDownLatch recreatedReplay = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> callbackThread = new AtomicReference<>();
        AtomicReference<BackupCoordinator.Snapshot> terminal = new AtomicReference<>();
        BackupCoordinator.Listener recreated = (source, snapshot) -> {
            callbackThread.set(Thread.currentThread().getName());
            if (snapshot.state == BackupCoordinator.State.RUNNING) recreatedReplay.countDown();
            if (snapshot.isTerminal()) {
                terminal.set(snapshot);
                completed.countDown();
            }
        };
        session.attach(recreated);
        check(recreatedReplay.await(3, TimeUnit.SECONDS), "recreated Activity replays running state");
        finishWork.countDown();
        check(completed.await(3, TimeUnit.SECONDS), "recreated Activity receives completion");
        check(oldScreen.size() == detachedCount, "destroyed Activity remains detached");
        check("desktop-main".equals(callbackThread.get()), "listener dispatches on main Handler");
        check(terminal.get().result == expected && terminal.get().error == null,
                "completion retains exact result");
        check(BackupCoordinator.find(session.id()) == session,
                "terminal session remains findable");
        check(BackupCoordinator.latest() == session
                        && BackupCoordinator.latest(BackupCoordinator.Kind.BACKUP) == session,
                "latest APIs return retained session");

        CountDownLatch terminalReplay = new CountDownLatch(1);
        AtomicReference<BackupCoordinator.Snapshot> replayed = new AtomicReference<>();
        BackupCoordinator.Listener afterCompletion = (source, snapshot) -> {
            replayed.set(snapshot);
            terminalReplay.countDown();
        };
        session.attach(afterCompletion);
        check(terminalReplay.await(3, TimeUnit.SECONDS)
                        && replayed.get().state == BackupCoordinator.State.COMPLETED,
                "terminal result replays after recreation");
        check(BackupCoordinator.forget(session.id()), "terminal session can be forgotten");
        check(BackupCoordinator.find(session.id()) == null
                        && !BackupCoordinator.forget(session.id()),
                "forget removes exactly once");
    }

    private static void testCooperativeCancellation() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch observed = new CountDownLatch(1);
        CountDownLatch allowStop = new CountDownLatch(1);
        AtomicBoolean tokenObserved = new AtomicBoolean();
        CountDownLatch canceled = new CountDownLatch(1);
        BackupCoordinator.Session session = BackupCoordinator.start(
                BackupCoordinator.Kind.RESTORE, (progress, cancellation) -> {
                    started.countDown();
                    while (!cancellation.isCancelled()) Thread.yield();
                    tokenObserved.set(true);
                    observed.countDown();
                    await(allowStop, "allow canceled work to stop");
                    throw new IOException("cancelled by token");
                });
        BackupCoordinator.Listener listener = (source, snapshot) -> {
            if (snapshot.state == BackupCoordinator.State.CANCELED) canceled.countDown();
        };
        session.attach(listener);
        check(started.await(3, TimeUnit.SECONDS), "restore work starts");
        check(session.cancel() && !session.cancel(), "cancel request is idempotent");
        check(session.snapshot().state == BackupCoordinator.State.RUNNING
                        && session.snapshot().cancellationRequested,
                "cancel request remains running until work stops");
        check(!BackupCoordinator.forget(session.id()),
                "cancel request cannot be forgotten while work still runs");
        boolean secondRejected = false;
        try {
            BackupCoordinator.start(BackupCoordinator.Kind.BACKUP,
                    (progress, cancellation) -> result(1, 1, 1));
        } catch (IllegalStateException expected) { secondRejected = true; }
        check(secondRejected, "second active session is rejected atomically");
        check(observed.await(3, TimeUnit.SECONDS) && tokenObserved.get(),
                "work observes cooperative token");
        allowStop.countDown();
        check(canceled.await(3, TimeUnit.SECONDS), "canceled state waits for worker exit");
        check(session.snapshot().state == BackupCoordinator.State.CANCELED
                        && session.snapshot().error == null,
                "cancellation is not published as failure");
        check(!BackupCoordinator.forget("missing") && BackupCoordinator.forget(session.id()),
                "canceled session is retained until forget");
    }

    private static void testSuccessfulWorkWinsCancellationRace() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch commitFinished = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        BackupManager.Result expected = result(2, 3, 50);
        BackupCoordinator.Session session = BackupCoordinator.start(
                BackupCoordinator.Kind.RESTORE, (progress, cancellation) -> {
                    started.countDown();
                    await(commitFinished, "finish committed restore");
                    return expected;
                });
        BackupCoordinator.Listener listener = (source, snapshot) -> {
            if (snapshot.state == BackupCoordinator.State.COMPLETED) completed.countDown();
        };
        session.attach(listener);
        check(started.await(3, TimeUnit.SECONDS), "commit-race work starts");
        check(session.cancel(), "cancel can race the final commit window");
        commitFinished.countDown();
        check(completed.await(3, TimeUnit.SECONDS), "successful return wins cancellation race");
        check(session.snapshot().state == BackupCoordinator.State.COMPLETED
                        && session.snapshot().result == expected,
                "committed restore is never reported as canceled");
        check(BackupCoordinator.forget(session.id()), "race winner remains retained until forget");
    }

    private static void testFailureRetention() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        IOException expected = new IOException("broken archive");
        BackupCoordinator.Session session = BackupCoordinator.start(
                BackupCoordinator.Kind.RESTORE, (progress, cancellation) -> { throw expected; });
        BackupCoordinator.Listener listener = (source, snapshot) -> {
            if (snapshot.state == BackupCoordinator.State.FAILED) failed.countDown();
        };
        session.attach(listener);
        check(failed.await(3, TimeUnit.SECONDS), "failure is delivered");
        check(session.snapshot().error == expected && session.snapshot().result == null,
                "failure retains exact error");
        check(BackupCoordinator.sessions().contains(session), "failure remains in registry");
        boolean immutable = false;
        try { BackupCoordinator.sessions().clear(); }
        catch (UnsupportedOperationException expectedError) { immutable = true; }
        check(immutable, "sessions view is immutable");
        check(BackupCoordinator.forget(session.id()), "failed session can be forgotten");
    }

    private static BackupManager.Result result(int items, int entries, long bytes) {
        return new BackupManager.Result(items, entries, 0, bytes, "");
    }

    private static void await(CountDownLatch latch, String label) throws Exception {
        if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError(label);
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
