package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Locale;

/** A true screen-pixel size sample, scaled only when it would not fit. */
public final class SizePreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int color = 0xff222222;
    private float diameterPx = 3f;
    private boolean eraser;

    public SizePreviewView(Context context) {
        super(context);
        setMinimumHeight(dp(100));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        updateDescription();
    }

    public void setPreview(int color, float diameterPx, boolean eraser) {
        this.color = color;
        this.diameterPx = Float.isNaN(diameterPx) || Float.isInfinite(diameterPx)
                ? 1f : Math.max(0.1f, diameterPx);
        this.eraser = eraser;
        updateDescription();
        invalidate();
    }

    private void updateDescription() {
        setContentDescription(String.format(Locale.ROOT, "%s大小预览，屏幕直径 %.1f 像素",
                eraser ? "橡皮" : "画笔", diameterPx));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(dp(280), widthSpec), resolveSize(dp(100), heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(AppColors.BACKGROUND);
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, dp(12), dp(12), paint);

        float textSize = dp(11);
        float sampleTop = dp(10), sampleBottom = Math.max(sampleTop + 1, h - dp(29));
        float sampleY = (sampleTop + sampleBottom) / 2f;
        float circleX = w * 0.21f;
        float lineLeft = w * 0.49f, lineRight = w * 0.84f;
        float maxDiameter = Math.max(1f, Math.min(sampleBottom - sampleTop,
                Math.min(w * 0.29f, (w - lineRight - dp(8)) * 2f)));
        float scale = Math.min(1f, maxDiameter / diameterPx);
        float drawDiameter = diameterPx * scale;
        float radius = drawDiameter / 2f;

        if (radius < dp(5)) {
            // Four detached ticks locate a tiny dot without exaggerating its diameter.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            paint.setColor(AppColors.BORDER);
            float inner = dp(7), outer = dp(11);
            canvas.drawLine(circleX - outer, sampleY, circleX - inner, sampleY, paint);
            canvas.drawLine(circleX + inner, sampleY, circleX + outer, sampleY, paint);
            canvas.drawLine(circleX, sampleY - outer, circleX, sampleY - inner, paint);
            canvas.drawLine(circleX, sampleY + inner, circleX, sampleY + outer, paint);
        }

        if (eraser) {
            // A pale interior and a crisp boundary convey the actual erasing footprint.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(AppColors.SURFACE);
            canvas.drawCircle(circleX, sampleY, radius, paint);
            rect.set(lineLeft - radius, sampleY - radius, lineRight + radius, sampleY + radius);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(AppColors.MUTED);
            // Keep the outer edge at the requested diameter, rather than growing the preview.
            float border = Math.min(dp(1.5f), drawDiameter / 3f);
            paint.setStrokeWidth(border);
            canvas.drawCircle(circleX, sampleY, Math.max(0, radius - border / 2f), paint);
            rect.inset(border / 2f, border / 2f);
            canvas.drawRoundRect(rect, Math.max(0, radius - border / 2f),
                    Math.max(0, radius - border / 2f), paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawCircle(circleX, sampleY, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(drawDiameter);
            canvas.drawLine(lineLeft, sampleY, lineRight, sampleY, paint);
            // Very light inks remain discernible against the preview surface.
            int red = (color >> 16) & 255, green = (color >> 8) & 255, blue = color & 255;
            if (red > 225 && green > 225 && blue > 225) {
                float border = Math.min(1f, drawDiameter / 4f);
                paint.setColor(0x5036291f);
                paint.setStrokeWidth(border);
                canvas.drawCircle(circleX, sampleY, Math.max(0, radius - border / 2f), paint);
                rect.set(lineLeft - radius + border / 2f, sampleY - radius + border / 2f,
                        lineRight + radius - border / 2f, sampleY + radius - border / 2f);
                canvas.drawRoundRect(rect, radius, radius, paint);
            }
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(AppColors.MUTED);
        paint.setTextSize(textSize);
        paint.setTextAlign(Paint.Align.CENTER);
        String detail = String.format(Locale.ROOT, "%.1f px  ·  %s", diameterPx,
                scale < 0.999f ? String.format(Locale.ROOT, "缩小至 %.0f%%", scale * 100f) : "实际大小 1:1");
        canvas.drawText(detail, w / 2f, h - dp(10), paint);
    }
}
