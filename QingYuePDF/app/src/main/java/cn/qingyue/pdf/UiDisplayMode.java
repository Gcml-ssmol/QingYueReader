package cn.qingyue.pdf;

import android.content.Context;
import android.app.Activity;
import android.app.AlertDialog;

/** Screen-only PDF and note page treatment. Never used by storage or export. */
public final class UiDisplayMode {
    public static final int FOLLOW_THEME = 0;
    public static final int ORIGINAL = 1;
    public static final int NIGHT_PAGE = 2;
    private UiDisplayMode() { }

    public static int selected(Context context) {
        int value = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
                .getInt("nightPages", FOLLOW_THEME);
        return value >= FOLLOW_THEME && value <= NIGHT_PAGE ? value : FOLLOW_THEME;
    }

    public static boolean nightPage(Context context) {
        int value = selected(context);
        return value == NIGHT_PAGE || value == FOLLOW_THEME && AppColors.isDark();
    }

    public static void select(Context context, int value) {
        if (value < FOLLOW_THEME || value > NIGHT_PAGE) throw new IllegalArgumentException("nightPages");
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit()
                .putInt("nightPages", value).apply();
        AppColors.apply(context);
    }

    public static AlertDialog chooser(Activity owner, Runnable changed) {
        String[] options = {"跟随界面主题", "保持原色", "夜间页面（PDF 照片可能变色）"};
        AlertDialog dialog = new AlertDialog.Builder(owner)
                .setTitle("阅读页面显示")
                .setSingleChoiceItems(options, selected(owner), (whichDialog, choice) -> {
                    select(owner, choice);
                    if (changed != null) changed.run();
                    whichDialog.dismiss();
                })
                .setNegativeButton("取消", null).create();
        dialog.show();
        return dialog;
    }
}
