package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.Locale;

/** Solid color dot with a 48 dp touch target and a separate selection ring. */
public final class ColorSwatch extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String label;
    private int color;
    private boolean active;

    public ColorSwatch(Context context, int color, String label) {
        super(context);
        this.label = label;
        this.color = color | 0xff000000;
        setMinimumWidth(dp(48));
        setMinimumHeight(dp(48));
        setClickable(true);
        setFocusable(true);
        updateDescription();
        setOnLongClickListener(v -> {
            Toast.makeText(getContext(), getContentDescription(), Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    public void setColor(int color) {
        this.color = color | 0xff000000;
        updateDescription();
        invalidate();
    }

    public void setActive(boolean active) {
        this.active = active;
        setSelected(active);
        updateDescription();
        invalidate();
    }

    private void updateDescription() {
        setContentDescription(String.format(Locale.ROOT, "%s，#%06X%s", label,
                color & 0xffffff, active ? "，已选择" : ""));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(dp(48), widthSpec), resolveSize(dp(48), heightSpec));
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float radius = Math.min(dp(12), Math.min(getWidth(), getHeight()) / 4f);
        paint.setStyle(Paint.Style.FILL);
        if (isPressed() || isFocused()) {
            paint.setColor(AppColors.SOFT);
            canvas.drawCircle(cx, cy, radius + dp(9), paint);
        }
        paint.setColor(color);
        paint.setAlpha(isEnabled() ? 255 : 85);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1, dp(1)));
        paint.setColor(0x4036291f);
        canvas.drawCircle(cx, cy, radius, paint);
        if (active) {
            paint.setStrokeWidth(dp(2));
            paint.setColor(AppColors.ACCENT_DARK);
            canvas.drawCircle(cx, cy, radius + dp(5), paint);
        }
    }

    @Override public CharSequence getAccessibilityClassName() { return "android.widget.RadioButton"; }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setCheckable(true);
        info.setChecked(active);
        info.setSelected(active);
    }

    @Override public boolean performClick() { super.performClick(); return true; }
}
