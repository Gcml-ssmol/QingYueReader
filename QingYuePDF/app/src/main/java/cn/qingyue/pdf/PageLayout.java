package cn.qingyue.pdf;

/** Immutable continuous-page geometry. One document unit equals the page width. */
public final class PageLayout {
    public static final double GAP = 0.035;
    private final double[] tops;
    private final double[] heights;
    private final double totalHeight;

    public PageLayout(float[] heightOverWidth) {
        if (heightOverWidth == null || heightOverWidth.length == 0) {
            throw new IllegalArgumentException("At least one page is required");
        }
        tops = new double[heightOverWidth.length];
        heights = new double[heightOverWidth.length];
        double nextTop = GAP;
        for (int i = 0; i < heightOverWidth.length; i++) {
            float ratio = heightOverWidth[i];
            if (!(ratio > 0) || Float.isInfinite(ratio)) {
                throw new IllegalArgumentException("Page ratio must be positive and finite");
            }
            tops[i] = nextTop;
            heights[i] = ratio;
            nextTop += (double) ratio + GAP;
        }
        totalHeight = nextTop;
    }

    public int size() { return tops.length; }
    public double top(int page) { return tops[page]; }
    public double height(int page) { return heights[page]; }
    public double totalHeight() { return totalHeight; }

    /** Returns a recalibrated layout after a page is opened; untouched pages keep estimates. */
    public PageLayout withHeight(int page, float heightOverWidth) {
        if (page < 0 || page >= size() || !(heightOverWidth > 0) || Float.isInfinite(heightOverWidth)) {
            throw new IllegalArgumentException("Invalid page geometry");
        }
        if (Math.abs(heights[page] - heightOverWidth) < 0.0001) return this;
        float[] ratios = new float[size()];
        for (int i = 0; i < ratios.length; i++) ratios[i] = (float) heights[i];
        ratios[page] = heightOverWidth;
        return new PageLayout(ratios);
    }

    /** Gaps select the nearer page; an exact midpoint selects the preceding page. */
    public int pageAt(double documentY) {
        checkNotNaN(documentY);
        if (documentY <= tops[0]) return 0;
        int next = firstTopAfter(documentY);
        if (next == size()) return size() - 1;
        int previous = next - 1;
        double previousBottom = tops[previous] + heights[previous];
        if (documentY <= previousBottom) return previous;
        double midpoint = previousBottom + (tops[next] - previousBottom) / 2;
        return documentY <= midpoint ? previous : next;
    }

    public double clampScroll(double scroll, double viewportHeightUnits) {
        checkNotNaN(scroll);
        checkViewport(viewportHeightUnits);
        double maximum = Math.max(0, totalHeight - viewportHeightUnits);
        return Math.max(0, Math.min(scroll, maximum));
    }

    /**
     * Inclusive first/last visible pages after clamping the viewport to the document.
     * A viewport entirely inside a gap returns the page nearest its midpoint.
     */
    public int[] visibleRange(double scroll, double viewportHeightUnits) {
        double start = clampScroll(scroll, viewportHeightUnits);
        double end = start + viewportHeightUnits;
        int low = 0;
        int high = size();
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (tops[middle] + heights[middle] < start) low = middle + 1;
            else high = middle;
        }
        int first = low;
        int last = firstTopAfter(end) - 1;
        if (first > last) {
            int nearest = pageAt(start + viewportHeightUnits / 2);
            return new int[] { nearest, nearest };
        }
        return new int[] { first, last };
    }

    /** Position the chosen page below a one-gap margin, respecting the bottom limit. */
    public double jumpScroll(int page, double viewportHeightUnits) {
        return clampScroll(tops[page] - GAP, viewportHeightUnits);
    }

    private int firstTopAfter(double value) {
        int low = 0;
        int high = size();
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (tops[middle] <= value) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static void checkNotNaN(double value) {
        if (Double.isNaN(value)) throw new IllegalArgumentException("Coordinate must not be NaN");
    }

    private static void checkViewport(double value) {
        if (!(value >= 0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("Viewport height must be nonnegative and finite");
        }
    }
}
