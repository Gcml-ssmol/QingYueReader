package cn.qingyue.pdf;

import android.content.Context;
import android.util.TypedValue;

/** Small shared scale for new screen chrome; document and PDF coordinates stay separate. */
public final class UiMetrics {
    private UiMetrics() { }

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    public static float sp(Context context, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                context.getResources().getDisplayMetrics());
    }

    public static int touch(Context context) { return dp(context, 48); }
    public static int cardRadius(Context context) { return dp(context, AppColors.isClassic() ? 18 : 14); }
}
