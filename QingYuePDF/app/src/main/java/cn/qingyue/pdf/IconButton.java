package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

/** Compact, density-independent toolbar icons with a full-size touch target. */
public final class IconButton extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final String kind;
    private boolean active;
    private int tint = AppColors.INK;
    private boolean customTint;

    public IconButton(Context context, String kind, String label) {
        super(context);
        this.kind = kind;
        setContentDescription(label);
        setClickable(true);
        setFocusable(true);
        setMinimumWidth(dp(48));
        setMinimumHeight(dp(48));
        setOnLongClickListener(v -> {
            Toast.makeText(getContext(), label, Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    public void setActive(boolean active) {
        this.active = active;
        setSelected(active);
        invalidate();
    }

    public void setTint(int color) {
        tint = color;
        customTint = true;
        invalidate();
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

    @Override public CharSequence getAccessibilityClassName() {
        return "android.widget.Button";
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setSelected(active);
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float opacity = isEnabled() ? 1f : 0.32f;
        paint.setStyle(Paint.Style.FILL);
        if (active || isPressed() || isFocused()) {
            paint.setColor(isPressed() || active ? AppColors.SOFT : AppColors.BACKGROUND);
            paint.setAlpha(Math.round(255 * opacity));
            float inset = dp(3);
            rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            canvas.drawRoundRect(rect, dp(12), dp(12), paint);
        }
        paint.setColor(customTint ? tint : active ? AppColors.ACCENT_DARK : AppColors.INK);
        paint.setAlpha(Math.round(((paint.getColor() >>> 24) & 255) * opacity));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.save();
        float size = dp(24);
        canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        canvas.scale(size / 24f, size / 24f);
        drawIcon(canvas);
        canvas.restore();
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2) {
        c.drawLine(x1, y1, x2, y2, paint);
    }

    private void poly(Canvas c, boolean close, float... points) {
        path.reset();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        if (close) path.close();
        c.drawPath(path, paint);
    }

    private void dot(Canvas c, float x, float y, float radius) {
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
    }

    private void pen(Canvas c) {
        poly(c, true, 4, 20, 5.5f, 14.5f, 16.4f, 3.6f, 20.4f, 7.6f, 9.5f, 18.5f);
        line(c, 14, 6, 18, 10);
        line(c, 5.5f, 14.5f, 9.5f, 18.5f);
    }

    private void drawIcon(Canvas c) {
        switch (kind) {
            case "note":
                rect.set(5, 3, 20, 21);
                c.drawRoundRect(rect, 2, 2, paint);
                line(c, 8, 3, 8, 21);
                line(c, 2.5f, 7, 6, 7); line(c, 2.5f, 12, 6, 12); line(c, 2.5f, 17, 6, 17);
                line(c, 11, 8, 17, 8); line(c, 11, 12, 17, 12);
                break;
            case "image":
                rect.set(3, 4, 21, 20); c.drawRoundRect(rect, 2, 2, paint);
                c.drawCircle(8, 9, 1.5f, paint);
                poly(c, false, 4, 18, 9, 13, 12, 16, 17, 10, 21, 15);
                break;
            case "line":
                line(c, 4, 19, 20, 5); dot(c, 4, 19, 1.6f); dot(c, 20, 5, 1.6f);
                break;
            case "circle":
                c.drawCircle(12, 12, 8.5f, paint);
                break;
            case "rectangle":
                rect.set(3.5f, 5, 20.5f, 19); c.drawRect(rect, paint);
                break;
            case "arrow":
                line(c, 4, 20, 20, 4); poly(c, false, 11, 4, 20, 4, 20, 13);
                break;
            case "select":
                poly(c, true, 5, 3, 19, 12, 12, 13, 9, 20);
                line(c, 12, 13, 17, 21);
                break;
            case "settings":
                line(c, 4, 6, 5.5f, 6);
                line(c, 10.5f, 6, 20, 6);
                line(c, 4, 12, 13.5f, 12);
                line(c, 18.5f, 12, 20, 12);
                line(c, 4, 18, 6.5f, 18);
                line(c, 11.5f, 18, 20, 18);
                c.drawCircle(8, 6, 2.5f, paint);
                c.drawCircle(16, 12, 2.5f, paint);
                c.drawCircle(9, 18, 2.5f, paint);
                break;
            case "info":
                c.drawCircle(12, 12, 9, paint);
                dot(c, 12, 7.4f, 1.15f);
                poly(c, false, 10.5f, 11, 12, 11, 12, 16.5f);
                line(c, 10.5f, 16.5f, 13.5f, 16.5f);
                break;
            case "page":
                poly(c, true, 6, 3, 14, 3, 19, 8, 19, 21, 6, 21);
                poly(c, false, 14, 3, 14, 8, 19, 8);
                line(c, 9, 12, 16, 12);
                line(c, 9, 16, 14, 16);
                break;
            case "vertical":
                rect.set(5, 8, 19, 16);
                c.drawRoundRect(rect, 1.5f, 1.5f, paint);
                line(c, 12, 1, 12, 5);
                poly(c, false, 9.5f, 3.5f, 12, 1, 14.5f, 3.5f);
                line(c, 12, 19, 12, 23);
                poly(c, false, 9.5f, 20.5f, 12, 23, 14.5f, 20.5f);
                break;
            case "horizontal":
                rect.set(8, 5, 16, 19);
                c.drawRoundRect(rect, 1.5f, 1.5f, paint);
                line(c, 1, 12, 5, 12);
                poly(c, false, 3.5f, 9.5f, 1, 12, 3.5f, 14.5f);
                line(c, 19, 12, 23, 12);
                poly(c, false, 20.5f, 9.5f, 23, 12, 20.5f, 14.5f);
                break;
            case "book":
                path.reset();
                path.moveTo(12, 6);
                path.cubicTo(9, 3.5f, 5.5f, 3.5f, 3, 5);
                path.lineTo(3, 19);
                path.cubicTo(6, 17.5f, 9, 18, 12, 20);
                path.cubicTo(15, 18, 18, 17.5f, 21, 19);
                path.lineTo(21, 5);
                path.cubicTo(18.5f, 3.5f, 15, 3.5f, 12, 6);
                path.close();
                c.drawPath(path, paint);
                line(c, 12, 6, 12, 20);
                break;
            case "trash":
                poly(c, false, 4, 6, 20, 6);
                poly(c, false, 9, 6, 9, 3.5f, 15, 3.5f, 15, 6);
                poly(c, false, 6, 6, 7, 21, 17, 21, 18, 6);
                line(c, 10, 10, 10.5f, 17);
                line(c, 14, 10, 13.5f, 17);
                break;
            case "rename":
                pen(c);
                line(c, 13, 21, 21, 21);
                break;
            case "right":
                poly(c, false, 9, 6, 15, 12, 9, 18);
                break;
            case "close":
                line(c, 6, 6, 18, 18);
                line(c, 18, 6, 6, 18);
                break;
            case "search":
                c.drawCircle(10.5f, 10.5f, 6.5f, paint);
                line(c, 15.3f, 15.3f, 21, 21);
                break;
            case "dropper":
                poly(c, true, 5, 14, 13.5f, 5.5f, 18.5f, 10.5f, 10, 19, 6, 19, 3, 21, 5, 17);
                poly(c, false, 12, 7, 16.5f, 2.5f, 21.5f, 7.5f, 17, 12);
                line(c, 11, 6, 18, 13);
                break;
            case "plus":
                line(c, 12, 5, 12, 19);
                line(c, 5, 12, 19, 12);
                break;
            case "sun":
                c.drawCircle(12, 12, 4.5f, paint);
                line(c, 12, 2, 12, 4);
                line(c, 12, 20, 12, 22);
                line(c, 2, 12, 4, 12);
                line(c, 20, 12, 22, 12);
                line(c, 4.9f, 4.9f, 6.3f, 6.3f);
                line(c, 17.7f, 17.7f, 19.1f, 19.1f);
                line(c, 4.9f, 19.1f, 6.3f, 17.7f);
                line(c, 17.7f, 6.3f, 19.1f, 4.9f);
                break;
            case "back":
                poly(c, false, 14.5f, 5, 7.5f, 12, 14.5f, 19);
                break;
            case "hand":
                path.reset();
                path.moveTo(7.5f, 12);
                path.lineTo(7.5f, 6.5f);
                path.cubicTo(7.5f, 4.5f, 10, 4.5f, 10, 6.5f);
                path.lineTo(10, 11);
                path.lineTo(10, 4.5f);
                path.cubicTo(10, 2.5f, 12.5f, 2.5f, 12.5f, 4.5f);
                path.lineTo(12.5f, 11);
                path.lineTo(12.5f, 5.5f);
                path.cubicTo(12.5f, 3.5f, 15, 3.5f, 15, 5.5f);
                path.lineTo(15, 11.5f);
                path.lineTo(15, 8);
                path.cubicTo(15, 6, 17.5f, 6, 17.5f, 8);
                path.lineTo(17.5f, 14.5f);
                path.cubicTo(17.5f, 18.5f, 15.5f, 21, 12, 21);
                path.cubicTo(9.5f, 21, 8, 20, 6.5f, 17.5f);
                path.lineTo(3.5f, 12.5f);
                path.cubicTo(2.5f, 10.5f, 4.5f, 9.5f, 5.7f, 11);
                path.lineTo(7.5f, 13);
                c.drawPath(path, paint);
                break;
            case "pen":
                pen(c);
                break;
            case "marker":
                poly(c, true, 8, 14, 15.5f, 3.5f, 21, 7.5f, 13.5f, 18);
                poly(c, true, 8, 14, 6, 16.5f, 10, 19.5f, 13.5f, 18);
                poly(c, false, 6, 16.5f, 3, 20.5f, 8.5f, 20.5f, 10, 19.5f);
                line(c, 14, 6, 19, 10);
                break;
            case "eraser":
                poly(c, true, 3.5f, 14.5f, 13.5f, 4.5f, 21, 12, 13, 20, 9, 20);
                line(c, 8, 10, 16, 18);
                line(c, 13, 20, 21, 20);
                break;
            case "color":
                path.reset();
                path.moveTo(12, 3);
                path.cubicTo(7, 3, 3, 7, 3, 12);
                path.cubicTo(3, 17, 7, 21, 12, 21);
                path.cubicTo(15.5f, 21, 13.5f, 17.5f, 15.5f, 16.5f);
                path.cubicTo(17, 15.5f, 21, 17, 21, 12);
                path.cubicTo(21, 7, 17, 3, 12, 3);
                c.drawPath(path, paint);
                dot(c, 8, 8.5f, 1.15f);
                dot(c, 13, 6.8f, 1.15f);
                dot(c, 17, 10, 1.15f);
                dot(c, 6.8f, 13.5f, 1.15f);
                break;
            case "width":
                paint.setStrokeWidth(1);
                line(c, 5, 6, 19, 6);
                paint.setStrokeWidth(2.5f);
                line(c, 5, 12, 19, 12);
                paint.setStrokeWidth(4);
                line(c, 5, 18.5f, 19, 18.5f);
                break;
            case "undo":
                poly(c, false, 8, 5, 3.5f, 9.5f, 8, 14);
                path.reset();
                path.moveTo(4, 9.5f);
                path.lineTo(14, 9.5f);
                path.cubicTo(22, 9.5f, 22, 20, 14, 20);
                c.drawPath(path, paint);
                break;
            case "redo":
                poly(c, false, 16, 5, 20.5f, 9.5f, 16, 14);
                path.reset();
                path.moveTo(20, 9.5f);
                path.lineTo(10, 9.5f);
                path.cubicTo(2, 9.5f, 2, 20, 10, 20);
                c.drawPath(path, paint);
                break;
            case "stylus":
                poly(c, true, 5, 18, 6, 13, 15, 4, 19, 8, 10, 17);
                line(c, 13, 6, 17, 10);
                poly(c, false, 14, 18, 16.5f, 20.5f, 21, 15.5f);
                break;
            case "more":
                dot(c, 5, 12, 1.6f);
                dot(c, 12, 12, 1.6f);
                dot(c, 19, 12, 1.6f);
                break;
            case "up":
                poly(c, false, 5, 15, 12, 8, 19, 15);
                break;
            case "down":
                poly(c, false, 5, 9, 12, 16, 19, 9);
                break;
            case "fit":
                poly(c, false, 8, 3.5f, 3.5f, 3.5f, 3.5f, 8);
                poly(c, false, 16, 3.5f, 20.5f, 3.5f, 20.5f, 8);
                poly(c, false, 3.5f, 16, 3.5f, 20.5f, 8, 20.5f);
                poly(c, false, 20.5f, 16, 20.5f, 20.5f, 16, 20.5f);
                rect.set(8, 7, 16, 17);
                c.drawRoundRect(rect, 1, 1, paint);
                break;
            case "check":
                poly(c, false, 4.5f, 12, 9.5f, 17, 19.5f, 7);
                break;
            default:
                c.drawCircle(12, 12, 7, paint);
                break;
        }
    }
}
