package cn.qingyue.pdf;

import android.content.Context;
import android.content.res.Configuration;

/** Display palette only. Stored ink and exported paper never follow the UI theme. */
public final class AppColors {
    private AppColors() { }

    public static final int STYLE_CREAM = 0;
    public static final int STYLE_CLASSIC = 1;
    public static int BACKGROUND = 0xfffff8ef;
    public static int SURFACE = 0xfffffcf7;
    public static int INK = 0xff2d241e;
    public static int MUTED = 0xff887567;
    public static int ACCENT = 0xffde5c16;
    public static int ON_ACCENT = 0xffffffff;
    public static int ACCENT_DARK = 0xffaa4210;
    public static int SOFT = 0xffffe7d1;
    public static int BORDER = 0xffefdfd0;
    public static int CANVAS = 0xfff4eade;
    public static int PAPER = 0xfffffcf6;
    public static final int EXPORT_PAPER = 0xfffffcf6;
    public static final int PAPER_GRID = 0xffe8ded1;
    public static final int PAPER_RULE = 0xffe2d5c5;
    private static boolean dark;
    private static int appliedMode = -1, appliedPaper = -1, appliedStyle = -1, revision;

    public static synchronized void apply(Context context) {
        android.content.SharedPreferences p = context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
        int mode = p.getInt("mode", 0), paper = p.getInt("paper", 0);
        // A separate display-only key makes the new look the default for upgrades too.
        // Existing night-mode and paper selections remain untouched.
        int style = p.getInt("style", STYLE_CREAM) == STYLE_CLASSIC ? STYLE_CLASSIC : STYLE_CREAM;
        boolean nextDark = mode == 2 || (mode == 0 &&
                (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        if (appliedMode == mode && appliedPaper == paper && appliedStyle == style && dark == nextDark) return;
        appliedMode = mode; appliedPaper = paper; appliedStyle = style; dark = nextDark;
        if (isClassic()) {
            BACKGROUND = dark ? 0xff211d19 : 0xfffff5e9;
            SURFACE = dark ? 0xff2d2722 : 0xfffffcf6;
            INK = dark ? 0xfff4e9dd : 0xff36291f;
            MUTED = dark ? 0xffc0b09f : 0xff917b68;
            ACCENT = dark ? 0xffe6a36f : 0xffd8702a;
            ON_ACCENT = dark ? 0xff211d19 : 0xffffffff;
            ACCENT_DARK = dark ? 0xffffbd88 : 0xffad4a14;
            SOFT = dark ? 0xff473426 : 0xffffe6cf;
            BORDER = dark ? 0xff5c4839 : 0xffefdcc9;
            CANVAS = dark ? 0xff171411 : 0xffefe6db;
        } else {
            BACKGROUND = dark ? 0xff29231f : 0xfffff8ef;
            SURFACE = dark ? 0xff332b25 : 0xfffffcf7;
            INK = dark ? 0xfff8eee4 : 0xff2d241e;
            MUTED = dark ? 0xffc9b5a4 : 0xff887567;
            ACCENT = dark ? 0xfff2a16b : 0xffde5c16;
            ON_ACCENT = dark ? 0xff2d241e : 0xffffffff;
            ACCENT_DARK = dark ? 0xffffc69e : 0xffaa4210;
            SOFT = dark ? 0xff4d3427 : 0xffffe7d1;
            BORDER = dark ? 0xff614b3b : 0xffefdfd0;
            CANVAS = dark ? 0xff1c1815 : 0xfff4eade;
        }
        PAPER = paper == 1 ? 0xffffffff : EXPORT_PAPER;
        revision++;
    }

    public static boolean isDark() { return dark; }
    public static boolean isClassic() { return appliedStyle == STYLE_CLASSIC; }
    public static int style() { return isClassic() ? STYLE_CLASSIC : STYLE_CREAM; }
    public static int revision() { return revision; }
}
