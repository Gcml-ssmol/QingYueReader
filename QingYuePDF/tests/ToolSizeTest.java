package cn.qingyue.pdf;

public class ToolSizeTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void invalidTool(int tool) {
        try {
            ToolSize.fromProgress(tool, 50);
            throw new AssertionError("fromProgress accepted invalid tool " + tool);
        } catch (IllegalArgumentException expected) {}
        try {
            ToolSize.toProgress(tool, 0.006f);
            throw new AssertionError("toProgress accepted invalid tool " + tool);
        } catch (IllegalArgumentException expected) {}
        try {
            ToolSize.clamp(tool, Float.NaN);
            throw new AssertionError("clamp accepted invalid tool " + tool);
        } catch (IllegalArgumentException expected) {}
    }

    public static void main(String[] args) {
        float[] minimum = {0, 0.0008f, 0.005f, 4f};
        float[] maximum = {0, 0.03f, 0.08f, 96f};
        float[] defaults = {0, 0.006f, 0.03f, 28f};
        for (int tool = 1; tool <= 3; tool++) {
            check(ToolSize.fromProgress(tool, 0) == minimum[tool], "Incorrect minimum");
            check(ToolSize.fromProgress(tool, 100) == maximum[tool], "Incorrect maximum");
            check(ToolSize.fromProgress(tool, Integer.MIN_VALUE) == minimum[tool], "Negative progress not clamped");
            check(ToolSize.fromProgress(tool, Integer.MAX_VALUE) == maximum[tool], "Large progress not clamped");
            check(ToolSize.clamp(tool, -Float.MAX_VALUE) == minimum[tool], "Negative size not clamped");
            check(ToolSize.clamp(tool, Float.MAX_VALUE) == maximum[tool], "Large size not clamped");
            check(ToolSize.clamp(tool, defaults[tool]) == defaults[tool], "Valid value changed");
            check(ToolSize.toProgress(tool, -Float.MAX_VALUE) == 0, "Negative size mapped incorrectly");
            check(ToolSize.toProgress(tool, Float.MAX_VALUE) == 100, "Large size mapped incorrectly");
            for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
                check(ToolSize.clamp(tool, invalid) == defaults[tool], "Invalid value did not restore default");
                check(ToolSize.toProgress(tool, invalid) == ToolSize.toProgress(tool, defaults[tool]), "Invalid value mapped incorrectly");
            }
            float previous = 0;
            for (int progress = 0; progress <= 100; progress++) {
                float value = ToolSize.fromProgress(tool, progress);
                check(value > previous, "Slider is not strictly increasing");
                check(value >= minimum[tool] && value <= maximum[tool], "Size escaped tool range");
                check(ToolSize.toProgress(tool, value) == progress, "Slider round trip failed");
                if (tool != 3) {
                    check(value <= 0.1f, "Ink size exceeds persistence limit");
                    new InkPage.Stroke(0xff000000, value, new float[]{0.1f, 0.1f});
                }
                previous = value;
            }
            double halfway = Math.sqrt((double) minimum[tool] * maximum[tool]);
            check(Math.abs(ToolSize.fromProgress(tool, 50) / halfway - 1) < 0.00001,
                    "Midpoint must be exponential, not linear");
            float lowStep = ToolSize.fromProgress(tool, 1) - ToolSize.fromProgress(tool, 0);
            float highStep = ToolSize.fromProgress(tool, 100) - ToolSize.fromProgress(tool, 99);
            check(lowStep < highStep, "Thin sizes need finer adjustment");
            for (int p = 0; p < 100; p++) {
                // Interpolate between positions in logarithmic space; verify rounding either side of halfway.
                double low = ToolSize.fromProgress(tool, p);
                double ratio = ToolSize.fromProgress(tool, p + 1) / low;
                check(ToolSize.toProgress(tool, (float) (low * Math.pow(ratio, 0.49))) == p,
                        "Position below midpoint rounded up");
                check(ToolSize.toProgress(tool, (float) (low * Math.pow(ratio, 0.51))) == p + 1,
                        "Position above midpoint rounded down");
            }
        }
        check(ToolSize.clamp(1, 28f) == 0.03f, "Eraser units leaked to pen");
        check(ToolSize.clamp(2, 28f) == 0.08f, "Eraser units leaked to highlighter");
        check(ToolSize.clamp(3, 0.006f) == 4f, "Ink units leaked to eraser");
        check(ToolSize.fromProgress(1, 50) < ToolSize.fromProgress(2, 50), "Pen and highlighter ranges collapsed");
        for (int tool : new int[]{Integer.MIN_VALUE, -1, 0, 4, Integer.MAX_VALUE}) invalidTool(tool);
        System.out.println("PASS: exact endpoints, independent ranges, exponential response, monotonic sizes, slider round trips, nearest rounding, clamping, invalid floats/tools, InkPage size compatibility");
    }
}
