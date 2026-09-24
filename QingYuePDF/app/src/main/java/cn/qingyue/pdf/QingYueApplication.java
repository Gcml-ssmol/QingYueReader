package cn.qingyue.pdf;

import android.app.Application;

public final class QingYueApplication extends Application {
    private static volatile QingYueApplication current;
    static QingYueApplication instance() { return current; }
    @Override public void onCreate() {
        super.onCreate();
        current=this;
        CrashDiagnostics.install(this);
        PdfPageInsertion.startRecovery(this);
    }
}
