package cn.qingyue.pdf;

public final class ZoomLimitsTest {
    public static void main(String[] args) {
        check(ZoomLimits.clamp(.01), .5);
        check(ZoomLimits.clamp(20), 3);
        check(ZoomLimits.clamp(1.25), 1.25);
        check(ZoomLimits.clamp(Double.NaN), 1);
        if (ZoomLimits.percent(.5) != 50 || ZoomLimits.percent(3) != 300 || ZoomLimits.percent(1) != 100)
            throw new AssertionError("Displayed zoom endpoints");
        for (double fitted : new double[]{.00001, .3, .8, 1.7, 3.5, 20, Double.NaN}) {
            double base = ZoomLimits.noteBase(fitted);
            check(ZoomLimits.noteScale(base, base), base);
            check(ZoomLimits.noteScale(base / 100, base), base * .5);
            check(ZoomLimits.noteScale(base * 100, base), base * 3);
            for (double ratio : new double[]{.5, 1, 1.5, 3}) {
                double scale = ZoomLimits.noteScale(base * ratio, base);
                if (scale < .025 || scale > 32) throw new AssertionError("Old note format scale range");
                check(scale / base, ratio);
            }
        }
        // Pinch at a boundary and reverse: shrinking/growing resumes immediately.
        double ratio = ZoomLimits.clamp(2.9 * 2);
        check(ZoomLimits.clamp(ratio * .9), 2.7);
        ratio = ZoomLimits.clamp(.6 / 4);
        check(ZoomLimits.clamp(ratio * 1.2), .6);
        System.out.println("PASS: 50%-300% limits, display, fitted note scales, persistence compatibility and reversed pinches");
    }
    private static void check(double actual, double expected) {
        if (Math.abs(actual - expected) > .0000001) throw new AssertionError(actual + " != " + expected);
    }
}
