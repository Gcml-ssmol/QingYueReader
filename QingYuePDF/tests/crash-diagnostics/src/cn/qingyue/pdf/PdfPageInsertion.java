package cn.qingyue.pdf;

import android.content.Context;

/** Recording dependency for QingYueApplication in this diagnostics-only suite.
 * Production startup/recovery behavior is covered by tests/pdf-page-insertion.
 */
final class PdfPageInsertion {
    static int startCalls;
    static Context lastContext;
    private PdfPageInsertion() { }
    static void startRecovery(Context context) {
        startCalls++;
        lastContext=context;
    }
}
