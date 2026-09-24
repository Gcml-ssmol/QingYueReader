package cn.qingyue.pdf;

import android.graphics.Canvas;
import android.graphics.Paint;

/** Screen overlay only; never part of document coordinates or exported content. */
final class ZoomBadge {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int lastPercent = -1;
    private String label;
    void draw(Canvas canvas, int viewWidth, float density, double ratio) {
        int percent = ZoomLimits.percent(ratio);
        if (lastPercent != percent) { lastPercent = percent; label = percent + "%"; }
        paint.setTextSize(13 * density);
        paint.setTextAlign(Paint.Align.CENTER);
        float width = paint.measureText(label) + 24 * density;
        float right = Math.max(width, viewWidth - 12 * density), top = 12 * density;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(AppColors.SURFACE);
        canvas.drawRoundRect(right - width, top, right, top + 30 * density, 15 * density, 15 * density, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        paint.setColor(AppColors.BORDER);
        canvas.drawRoundRect(right - width, top, right, top + 30 * density, 15 * density, 15 * density, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(AppColors.INK);
        float baseline = top + 15 * density - (paint.ascent() + paint.descent()) / 2;
        canvas.drawText(label, right - width / 2, baseline, paint);
    }
}
