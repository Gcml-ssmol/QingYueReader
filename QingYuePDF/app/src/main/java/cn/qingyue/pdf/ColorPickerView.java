package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;

/** Saturation/value square and a continuous hue strip, with no bitmap dependency. */
public final class ColorPickerView extends View {
    public interface Listener { void changed(int color); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF square = new RectF();
    private final RectF hueBar = new RectF();
    private final float[] hsv = {0f, 0.75f, 0.85f};
    private LinearGradient saturationGradient, valueGradient, hueGradient;
    private Listener listener;
    private int pointerId = MotionEvent.INVALID_POINTER_ID;
    private boolean draggingHue;

    public ColorPickerView(Context context) {
        super(context);
        setMinimumHeight(dp(180));
        setFocusable(true);
        setClickable(true);
        updateDescription();
    }

    public void setColor(int color) {
        Color.colorToHSV(color, hsv);
        updateSaturationGradient();
        updateDescription();
        invalidate();
    }

    public int getColor() { return Color.HSVToColor(hsv); }
    public void setListener(Listener listener) { this.listener = listener; }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(dp(300), widthSpec), resolveSize(dp(180), heightSpec));
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float inset = dp(10);
        float left = getPaddingLeft() + inset;
        float right = Math.max(left + 1, w - getPaddingRight() - inset);
        float top = getPaddingTop() + inset;
        float bottom = Math.max(top + 2, h - getPaddingBottom() - inset);
        float stripHeight = Math.min(dp(20), (bottom - top) * 0.2f);
        float gap = Math.min(dp(24), (bottom - top) * 0.2f);
        hueBar.set(left, bottom - stripHeight, right, bottom);
        square.set(left, top, right, Math.max(top + 1, hueBar.top - gap));
        valueGradient = new LinearGradient(0, square.top, 0, square.bottom,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP);
        hueGradient = new LinearGradient(hueBar.left, 0, hueBar.right, 0,
                new int[]{Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                        Color.BLUE, Color.MAGENTA, Color.RED}, null, Shader.TileMode.CLAMP);
        updateSaturationGradient();
    }

    private void updateSaturationGradient() {
        if (square.width() <= 0) return;
        saturationGradient = new LinearGradient(square.left, 0, square.right, 0,
                Color.WHITE, Color.HSVToColor(new float[]{hsv[0], 1f, 1f}), Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (saturationGradient == null) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(isEnabled() ? 255 : 90);
        paint.setShader(saturationGradient);
        canvas.drawRect(square, paint);
        paint.setShader(valueGradient);
        canvas.drawRect(square, paint);
        paint.setShader(hueGradient);
        canvas.drawRoundRect(hueBar, dp(5), dp(5), paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(AppColors.BORDER);
        paint.setStrokeWidth(dp(1));
        canvas.drawRect(square, paint);
        drawCursor(canvas, square.left + hsv[1] * square.width(),
                square.bottom - hsv[2] * square.height(), getColor());
        drawCursor(canvas, hueBar.left + hsv[0] / 360f * hueBar.width(),
                hueBar.centerY(), Color.HSVToColor(new float[]{hsv[0], 1f, 1f}));
    }

    private void drawCursor(Canvas canvas, float x, float y, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawCircle(x, y, dp(7), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, dp(7), paint);
        paint.setStrokeWidth(Math.max(1f, dp(1)));
        paint.setColor(0xaa36291f);
        canvas.drawCircle(x, y, dp(9), paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointerId = event.getPointerId(0);
                draggingHue = event.getY() >= (square.bottom + hueBar.top) / 2f;
                setPressed(true);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                updateFromTouch(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(pointerId);
                if (index >= 0) updateFromTouch(event.getX(index), event.getY(index));
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == pointerId) finishTouch();
                return true;
            case MotionEvent.ACTION_UP:
                int upIndex = event.findPointerIndex(pointerId);
                if (upIndex >= 0) updateFromTouch(event.getX(upIndex), event.getY(upIndex));
                finishTouch();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                finishTouch();
                return true;
            default:
                return true;
        }
    }

    private void finishTouch() {
        pointerId = MotionEvent.INVALID_POINTER_ID;
        setPressed(false);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    private void updateFromTouch(float x, float y) {
        if (square.width() <= 0 || square.height() <= 0) return;
        if (draggingHue) {
            hsv[0] = clamp((x - hueBar.left) / hueBar.width()) * 360f;
            updateSaturationGradient();
        } else {
            hsv[1] = clamp((x - square.left) / square.width());
            hsv[2] = 1f - clamp((y - square.top) / square.height());
        }
        notifyChanged();
    }

    private float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }

    private void notifyChanged() {
        updateDescription();
        invalidate();
        if (listener != null) listener.changed(getColor());
    }

    private void updateDescription() {
        setContentDescription(String.format(Locale.ROOT,
                "调色盘，当前颜色 #%06X。上方调节饱和度与明度，下方调节色相", getColor() & 0xffffff));
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setScrollable(true);
        info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (isEnabled() && (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            hsv[0] = (hsv[0] + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 15f : 345f)) % 360f;
            updateSaturationGradient();
            notifyChanged();
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!isEnabled()) return super.onKeyDown(keyCode, event);
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) hsv[1] = clamp(hsv[1] - 0.02f);
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) hsv[1] = clamp(hsv[1] + 0.02f);
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) hsv[2] = clamp(hsv[2] + 0.02f);
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) hsv[2] = clamp(hsv[2] - 0.02f);
        else return super.onKeyDown(keyCode, event);
        notifyChanged();
        return true;
    }
}
