package cn.qingyue.pdf;

/** Width policy shared by the PDF and note rails, independent of Android pixels. */
final class ReaderSidebarLayout {
    private ReaderSidebarLayout() {}
    static boolean canDock(int widthDp) { return widthDp >= 600; }
    static int width(int widthDp) {
        if (!canDock(widthDp)) return 0;
        return Math.max(112, Math.min(168, widthDp / 6));
    }
}
