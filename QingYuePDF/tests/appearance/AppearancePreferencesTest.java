package cn.qingyue.pdf;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import java.util.HashMap;
import java.util.Map;

/** Desktop preference regression; this does not claim to exercise Android rendering. */
public final class AppearancePreferencesTest {
    private static final class MemoryContext extends Context implements SharedPreferences {
        final Map<String, Integer> values = new HashMap<>();
        final Resources resources = new Resources();
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            check("appearance".equals(name), "Only the display preferences may be read");
            return this;
        }
        @Override public Resources getResources() { return resources; }
        @Override public int getInt(String key, int fallback) {
            Integer value = values.get(key);
            return value == null ? fallback : value;
        }
        void select(String key, int value) { values.put(key, value); AppColors.apply(this); }
        void systemNight(boolean value) {
            resources.getConfiguration().uiMode = value ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
            AppColors.apply(this);
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(int expected, int actual, String label) {
        check(expected == actual, label + ": expected " + Integer.toHexString(expected) + ", actual " + Integer.toHexString(actual));
    }
    private static void palette(int... expected) {
        int[] actual = {AppColors.BACKGROUND, AppColors.SURFACE, AppColors.INK, AppColors.MUTED,
                AppColors.ACCENT, AppColors.ON_ACCENT, AppColors.ACCENT_DARK, AppColors.SOFT, AppColors.BORDER, AppColors.CANVAS};
        equal(expected.length, actual.length, "palette size");
        for (int i = 0; i < actual.length; i++) equal(expected[i], actual[i], "palette[" + i + "]");
    }
    private static double luminance(int color) {
        double[] channel = {(color >> 16 & 255) / 255d, (color >> 8 & 255) / 255d, (color & 255) / 255d};
        for (int i = 0; i < channel.length; i++)
            channel[i] = channel[i] <= .04045 ? channel[i] / 12.92 : Math.pow((channel[i] + .055) / 1.055, 2.4);
        return .2126 * channel[0] + .7152 * channel[1] + .0722 * channel[2];
    }
    private static void readable() {
        double foreground = luminance(AppColors.ACCENT), surface = luminance(AppColors.SURFACE);
        double button = luminance(AppColors.ON_ACCENT);
        check((Math.max(foreground, surface) + .05) / (Math.min(foreground, surface) + .05) >= 4.5,
                "Accent text remains readable on cards");
        check((Math.max(foreground, button) + .05) / (Math.min(foreground, button) + .05) >= 4.5,
                "Primary button text remains readable");
    }
    public static void main(String[] args) {
        MemoryContext fresh = new MemoryContext();
        AppColors.apply(fresh);
        check(!AppColors.isClassic() && !AppColors.isDark(), "Fresh install defaults to the neutral UI and follows daylight");
        check(fresh.values.isEmpty(), "Applying defaults must not write display or document preferences");
        palette(0xfff8f8f7, 0xffffffff, 0xff20201f, 0xff62625e, 0xffa9490f, 0xffffffff,
                0xffa9490f, 0xffececeb, 0xffe5e5e3, 0xffeeeeed);
        readable();
        int revision = AppColors.revision();
        AppColors.apply(fresh);
        equal(revision, AppColors.revision(), "Unchanged preference does not recreate activities");
        fresh.select("nightPages", 1);
        equal(revision + 1, AppColors.revision(), "Screen page choice refreshes open readers");
        check(!AppColors.isDark(), "Page filter does not change interface theme");
        fresh.select("nightPages", 0);
        revision = AppColors.revision();
        fresh.systemNight(true);
        check(AppColors.isDark(), "System night change is applied");
        equal(revision + 1, AppColors.revision(), "System night change invalidates displayed screens");
        fresh.select("mode", 1);
        check(!AppColors.isDark(), "Explicit light overrides a dark system");
        fresh.systemNight(false);
        revision = AppColors.revision();
        fresh.systemNight(true);
        equal(revision, AppColors.revision(), "Explicit light ignores system mode changes");

        MemoryContext upgraded = new MemoryContext();
        upgraded.values.put("mode", 2);
        upgraded.values.put("paper", 1);
        upgraded.values.put("unrelated", 42);
        Map<String, Integer> before = new HashMap<>(upgraded.values);
        AppColors.apply(upgraded);
        check(!AppColors.isClassic() && AppColors.isDark(), "Existing user gets the neutral UI while retaining explicit dark mode");
        check(before.equals(upgraded.values), "Existing preference values must not migrate or be overwritten");
        equal(0xffffffff, AppColors.PAPER, "Existing white paper retained on upgrade");
        palette(0xff191919, 0xff222222, 0xfff2f2f1, 0xffc3c3bf, 0xffffb783, 0xff191919,
                0xffffb783, 0xff323232, 0xff393939, 0xff151515);
        readable();
        revision = AppColors.revision();
        upgraded.select("style", AppColors.STYLE_CLASSIC);
        check(AppColors.isClassic() && AppColors.isDark(), "Switching style preserves explicit dark mode");
        equal(revision + 1, AppColors.revision(), "Style change invalidates displayed screens");
        palette(0xff211d19, 0xff2d2722, 0xfff4e9dd, 0xffd2c1b1, 0xffffb783, 0xff211d19,
                0xffffb783, 0xff473426, 0xff5c4839, 0xff171411);
        readable();
        equal(1, upgraded.getInt("paper", -1), "Style does not rewrite paper");
        upgraded.select("mode", 1);
        palette(0xfffff5e9, 0xfffffcf6, 0xff36291f, 0xff705b4c, 0xffa6430c, 0xffffffff,
                0xffa6430c, 0xffffe6cf, 0xffefdcc9, 0xffefe6db);
        readable();
        upgraded.select("style", AppColors.STYLE_CREAM);
        equal(1, upgraded.getInt("mode", -1), "Style round-trip preserves mode");
        upgraded.select("paper", 0);
        equal(0xfffffcf6, AppColors.PAPER, "Ivory display paper remains unchanged");
        equal(0xfffffcf6, AppColors.EXPORT_PAPER, "Export paper is not restyled");
        equal(0xffe8ded1, AppColors.PAPER_GRID, "Grid ink is not restyled");
        equal(0xffe2d5c5, AppColors.PAPER_RULE, "Rule ink is not restyled");
        upgraded.select("style", 88);
        check(!AppColors.isClassic(), "Unknown style safely falls back to cream");
        equal(42, upgraded.getInt("unrelated", -1), "Unrelated preferences remain intact");
        System.out.println("PASS: appearance defaults, preference preservation, classic palettes, system modes, revision and paper isolation");
    }
}
