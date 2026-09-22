package cn.qingyue.pdf;

import android.content.Context;
import android.content.res.Configuration;

/** Display palette only. Stored ink and exported paper never follow the UI theme. */
public final class AppColors {
    private AppColors() { }

    public static int BACKGROUND = 0xfffff5e9;
    public static int SURFACE = 0xfffffcf6;
    public static int INK = 0xff36291f;
    public static int MUTED = 0xff917b68;
    public static int ACCENT = 0xffd8702a;
    public static int ON_ACCENT = 0xffffffff;
    public static int ACCENT_DARK = 0xffad4a14;
    public static int SOFT = 0xffffe6cf;
    public static int BORDER = 0xffefdcc9;
    public static int CANVAS = 0xffefe6db;
    public static int PAPER = 0xfffffcf6;
    public static final int EXPORT_PAPER = 0xfffffcf6;
    public static final int PAPER_GRID = 0xffe8ded1;
    public static final int PAPER_RULE = 0xffe2d5c5;
    private static boolean dark;
    private static int appliedMode = -1, appliedPaper = -1, revision;

    public static synchronized void apply(Context context) {
        android.content.SharedPreferences p = context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
        int mode = p.getInt("mode", 0), paper = p.getInt("paper", 0);
        boolean nextDark = mode == 2 || (mode == 0 &&
                (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        if (appliedMode == mode && appliedPaper == paper && dark == nextDark) return;
        appliedMode = mode; appliedPaper = paper; dark = nextDark;
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
        PAPER = paper == 1 ? 0xffffffff : EXPORT_PAPER;
        revision++;
    }

    public static boolean isDark() { return dark; }
    public static int revision() { return revision; }
}
