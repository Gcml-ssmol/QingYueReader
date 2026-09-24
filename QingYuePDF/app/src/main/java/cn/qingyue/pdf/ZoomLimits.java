package cn.qingyue.pdf;

/** User zoom is relative to the fitted viewport, independent of screen pixel density. */
final class ZoomLimits {
    static final double MIN = .5, MAX = 3;
    private ZoomLimits() { }
    static double clamp(double ratio) {
        return Double.isNaN(ratio) ? 1 : Math.max(MIN, Math.min(MAX, ratio));
    }
    static int percent(double ratio) { return (int)Math.round(clamp(ratio) * 100); }
    // Keep the existing note format's absolute scale valid (.025..32).
    static double noteBase(double fittedScale) {
        if (Double.isNaN(fittedScale) || Double.isInfinite(fittedScale)) return 1;
        return Math.max(.05, Math.min(32.0 / MAX, fittedScale));
    }
    static double noteScale(double absoluteScale, double base) {
        return base * clamp(absoluteScale / base);
    }
    /** Infinite notes may zoom farther, while the persisted v3 absolute scale stays <=32. */
    static double noteInfiniteScale(double absoluteScale, double base) {
        if (Double.isNaN(absoluteScale) || Double.isInfinite(absoluteScale)) return base;
        return Math.max(.025, Math.min(32, base * Math.max(.5, Math.min(10, absoluteScale / base))));
    }
}
