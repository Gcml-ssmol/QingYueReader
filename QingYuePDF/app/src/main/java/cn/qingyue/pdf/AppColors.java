package cn.qingyue.pdf;

import android.content.Context;
import android.content.res.Configuration;

/** Display palette only. Stored ink and exported paper never follow the UI theme. */
public final class AppColors {
    private AppColors() { }

    public static final int STYLE_CREAM = 0;
    public static final int STYLE_CLASSIC = 1;
    public static int BACKGROUND = 0xfff8f8f7;
    public static int SURFACE = 0xffffffff;
    public static int INK = 0xff20201f;
    public static int MUTED = 0xff62625e;
    public static int ACCENT = 0xffa9490f;
    public static int ON_ACCENT = 0xffffffff;
    public static int ACCENT_DARK = 0xffa9490f;
    public static int SOFT = 0xffececeb;
    public static int BORDER = 0xffe5e5e3;
    public static int CANVAS = 0xffeeeeed;
    public static int PAPER = 0xfffffcf6;
    public static final int EXPORT_PAPER = 0xfffffcf6;
    public static final int PAPER_GRID = 0xffe8ded1;
    public static final int PAPER_RULE = 0xffe2d5c5;
    private static boolean dark;
    private static int appliedMode = -1, appliedPaper = -1, appliedStyle = -1, appliedNightPages = -1, revision;

    public static synchronized void apply(Context context) {
        android.content.SharedPreferences p = context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
        int mode = p.getInt("mode", 0), paper = p.getInt("paper", 0);
        // A separate display-only key makes the new look the default for upgrades too.
        // Existing night-mode and paper selections remain untouched.
        int style = p.getInt("style", STYLE_CREAM) == STYLE_CLASSIC ? STYLE_CLASSIC : STYLE_CREAM;
        int nightPages = p.getInt("nightPages", 0);
        boolean nextDark = mode == 2 || (mode == 0 &&
                (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        if (appliedMode == mode && appliedPaper == paper && appliedStyle == style
                && appliedNightPages == nightPages && dark == nextDark) return;
        appliedMode = mode; appliedPaper = paper; appliedStyle = style;
        appliedNightPages = nightPages; dark = nextDark;
        if (isClassic()) {
            BACKGROUND = dark ? 0xff211d19 : 0xfffff5e9;
            SURFACE = dark ? 0xff2d2722 : 0xfffffcf6;
            INK = dark ? 0xfff4e9dd : 0xff36291f;
            MUTED = dark ? 0xffd2c1b1 : 0xff705b4c;
            ACCENT = dark ? 0xffffb783 : 0xffa6430c;
            ON_ACCENT = dark ? 0xff211d19 : 0xffffffff;
            ACCENT_DARK = dark ? 0xffffb783 : 0xffa6430c;
            SOFT = dark ? 0xff473426 : 0xffffe6cf;
            BORDER = dark ? 0xff5c4839 : 0xffefdcc9;
            CANVAS = dark ? 0xff171411 : 0xffefe6db;
        } else {
            BACKGROUND = dark ? 0xff191919 : 0xfff8f8f7;
            SURFACE = dark ? 0xff222222 : 0xffffffff;
            INK = dark ? 0xfff2f2f1 : 0xff20201f;
            MUTED = dark ? 0xffc3c3bf : 0xff62625e;
            ACCENT = dark ? 0xffffb783 : 0xffa9490f;
            ON_ACCENT = dark ? 0xff191919 : 0xffffffff;
            ACCENT_DARK = dark ? 0xffffb783 : 0xffa9490f;
            SOFT = dark ? 0xff323232 : 0xffececeb;
            BORDER = dark ? 0xff393939 : 0xffe5e5e3;
            CANVAS = dark ? 0xff151515 : 0xffeeeeed;
        }
        PAPER = paper == 1 ? 0xffffffff : EXPORT_PAPER;
        revision++;
    }

    public static boolean isDark() { return dark; }
    public static boolean isClassic() { return appliedStyle == STYLE_CLASSIC; }
    public static int style() { return isClassic() ? STYLE_CLASSIC : STYLE_CREAM; }
    public static int revision() { return revision; }
}
