package cn.qingyue.pdf;

public final class PageLayoutTest {
    public static void main(String[] args) {
        PageLayout layout = new PageLayout(new float[] { 1.5f, 0.75f, 2f });
        equal(layout.size(), 3, "page count");
        close(layout.top(0), 0.035, "leading margin");
        close(layout.top(1), 1.57, "second page top");
        close(layout.top(2), 2.355, "third page top");
        close(layout.height(1), 0.75, "landscape page ratio");
        close(layout.totalHeight(), 4.39, "total height including trailing margin");

        equal(layout.pageAt(-100), 0, "before document");
        equal(layout.pageAt(Double.NEGATIVE_INFINITY), 0, "negative infinity clamps");
        equal(layout.pageAt(0.01), 0, "leading gap");
        equal(layout.pageAt(1.0), 0, "inside first page");
        equal(layout.pageAt(1.54), 0, "gap closer to previous page");
        equal(layout.pageAt(1.56), 1, "gap closer to next page");
        double firstBottom = layout.top(0) + layout.height(0);
        double midpoint = firstBottom + (layout.top(1) - firstBottom) / 2;
        equal(layout.pageAt(midpoint), 0, "gap midpoint tie");
        equal(layout.pageAt(layout.top(1)), 1, "exact page top");
        equal(layout.pageAt(layout.top(2) + layout.height(2)), 2, "last page bottom");
        equal(layout.pageAt(100), 2, "after document");
        equal(layout.pageAt(Double.POSITIVE_INFINITY), 2, "positive infinity clamps");

        close(layout.clampScroll(-1, 1), 0, "negative scroll clamps");
        close(layout.clampScroll(100, 1), 3.39, "bottom scroll clamps");
        close(layout.clampScroll(1, 10), 0, "viewport taller than document");
        close(layout.clampScroll(1, 0), 1, "zero viewport");
        close(layout.jumpScroll(0, 1), 0, "jump to first page");
        close(layout.jumpScroll(1, 1), 1.535, "jump leaves margin");
        close(layout.jumpScroll(2, 3), 1.39, "jump near end clamps");

        range(layout.visibleRange(0, 1), 0, 0, "first page only");
        range(layout.visibleRange(1.4, 0.3), 0, 1, "viewport crosses gap");
        range(layout.visibleRange(1.6, 0.6), 1, 1, "middle page only");
        range(layout.visibleRange(2.3, 0.5), 1, 2, "mixed aspect page boundary");
        range(layout.visibleRange(100, 0.5), 2, 2, "beyond bottom viewport");
        range(layout.visibleRange(-100, 100), 0, 2, "whole document");
        range(layout.visibleRange(0, 0.01), 0, 0, "only leading margin");
        range(layout.visibleRange(1.54, 0.005), 0, 0, "only gap near previous");
        range(layout.visibleRange(1.56, 0.005), 1, 1, "only gap near next");
        range(layout.visibleRange(layout.top(1), 0), 1, 1, "zero viewport at page top");
        range(layout.visibleRange(100, 0), 2, 2, "only trailing margin");

        float[] original = { 1 };
        PageLayout singleton = new PageLayout(original);
        original[0] = 10;
        close(singleton.height(0), 1, "constructor copies input");
        close(singleton.totalHeight(), 1.07, "single-page margins");
        range(singleton.visibleRange(0, 5), 0, 0, "single page fits viewport");

        int count = 100000;
        float[] ratios = new float[count];
        for (int i = 0; i < count; i++) ratios[i] = 1.25f;
        PageLayout longDocument = new PageLayout(ratios);
        double expected = PageLayout.GAP + count * (1.25 + PageLayout.GAP);
        if (Math.abs(longDocument.totalHeight() - expected) > 0.000001) {
            throw new AssertionError("Long document accumulated float-like error");
        }
        equal(longDocument.pageAt(longDocument.top(count - 1) + 0.3), count - 1, "long document lookup");
        range(longDocument.visibleRange(longDocument.jumpScroll(54321, 0.5), 0.5),
                54320, 54321, "long document jump includes preceding page boundary");

        rejects(() -> new PageLayout(null), "null array");
        rejects(() -> new PageLayout(new float[0]), "empty array");
        for (float invalid : new float[] { 0, -1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY }) {
            rejects(() -> new PageLayout(new float[] { 1, invalid }), "invalid ratio");
        }
        rejects(() -> layout.pageAt(Double.NaN), "NaN position");
        rejects(() -> layout.clampScroll(Double.NaN, 1), "NaN scroll");
        rejects(() -> layout.clampScroll(0, -1), "negative viewport");
        rejects(() -> layout.visibleRange(0, Double.NaN), "NaN viewport");
        rejects(() -> layout.jumpScroll(0, Double.POSITIVE_INFINITY), "infinite viewport");
        System.out.println("PASS: continuous page layout, mixed sizes, gaps, boundaries, visible ranges, jumps, long documents and invalid inputs");
    }

    private static void equal(int actual, int expected, String message) {
        if (actual != expected) throw new AssertionError(message + ": " + actual + " != " + expected);
    }

    private static void close(double actual, double expected, String message) {
        if (Math.abs(actual - expected) > 0.000000001) {
            throw new AssertionError(message + ": " + actual + " != " + expected);
        }
    }

    private static void range(int[] actual, int first, int last, String message) {
        equal(actual[0], first, message + " first");
        equal(actual[1], last, message + " last");
    }

    private static void rejects(Runnable action, String message) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Accepted " + message);
    }
}
