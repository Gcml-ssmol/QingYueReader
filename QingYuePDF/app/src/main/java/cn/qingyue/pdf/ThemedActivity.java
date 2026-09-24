package cn.qingyue.pdf;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

/** Recreates only the resumed screen when display preferences have changed. */
public class ThemedActivity extends Activity {
    private int paletteRevision;
    @Override protected void onCreate(Bundle state) {
        AppColors.apply(this);
        paletteRevision = AppColors.revision();
        String theme = AppColors.isClassic() ? (AppColors.isDark() ? "DarkAppTheme" : "AppTheme")
                : (AppColors.isDark() ? "CreamDarkAppTheme" : "CreamAppTheme");
        int style = getResources().getIdentifier(theme, "style", getPackageName());
        if (style != 0) setTheme(style);
        super.onCreate(state);
    }
    @Override protected void onPostCreate(Bundle state) { super.onPostCreate(state); applyChrome(); }
    @Override protected void onResume() {
        super.onResume();
        AppColors.apply(this);
        if (paletteRevision != AppColors.revision()) { recreate(); return; }
        applyChrome();
    }
    @Override protected void onPostResume() { super.onPostResume(); applyChrome(); }
    private void applyChrome() {
        getWindow().setStatusBarColor(AppColors.BACKGROUND);
        getWindow().setNavigationBarColor(Build.VERSION.SDK_INT >= 27 ? AppColors.BACKGROUND
                : AppColors.isClassic() ? 0xff36291f : 0xff2d241e);
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 27) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if (!AppColors.isDark()) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 27) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }
}
