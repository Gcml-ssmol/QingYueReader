package cn.qingyue.pdf;

/** Continuous sizes: ink uses a fraction of page width; eraser uses a diameter in dp. */
public final class ToolSize {
    private ToolSize() {}

    /** Exponential interpolation gives small sizes finer control than a linear slider. */
    public static float fromProgress(int tool, int progress) {
        float min = minimum(tool), max = maximum(tool);
        if (progress <= 0) return min;
        if (progress >= 100) return max;
        return (float) (min * Math.pow((double) max / min, progress / 100.0));
    }

    /** Maps a saved size back to the nearest slider position, sanitizing invalid values. */
    public static int toProgress(int tool, float value) {
        float min = minimum(tool), max = maximum(tool);
        float bounded = clamp(tool, value);
        double position = Math.log((double) bounded / min) / Math.log((double) max / min);
        return Math.max(0, Math.min(100, (int) Math.round(position * 100)));
    }

    /** Invalid floating point values restore the default for the specified tool. */
    public static float clamp(int tool, float value) {
        float min = minimum(tool), max = maximum(tool);
        if (Float.isNaN(value) || Float.isInfinite(value)) return defaultSize(tool);
        return Math.max(min, Math.min(max, value));
    }

    private static float minimum(int tool) {
        switch (tool) {
            case 1: return 0.0008f;
            case 2: return 0.005f;
            case 3: return 4f;
            default: throw new IllegalArgumentException("Unknown drawing tool: " + tool);
        }
    }

    private static float maximum(int tool) {
        switch (tool) {
            case 1: return 0.03f;
            case 2: return 0.08f;
            case 3: return 96f;
            default: throw new IllegalArgumentException("Unknown drawing tool: " + tool);
        }
    }

    private static float defaultSize(int tool) {
        switch (tool) {
            case 1: return 0.006f;
            case 2: return 0.03f;
            case 3: return 28f;
            default: throw new IllegalArgumentException("Unknown drawing tool: " + tool);
        }
    }
}
