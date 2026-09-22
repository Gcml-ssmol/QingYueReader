package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Original vector illustration: a sunlit folder holding a few pages. */
public final class FolderArtView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float density;

    public FolderArtView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = Math.round(80f * density);
        setMeasuredDimension(resolveSize(size + getPaddingLeft() + getPaddingRight(), widthMeasureSpec),
                resolveSize(size + getPaddingTop() + getPaddingBottom(), heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth() - getPaddingLeft() - getPaddingRight();
        float height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (width <= 0 || height <= 0) return;
        float scale = Math.min(width, height) / 96f;
        int save = canvas.save();
        canvas.translate(getPaddingLeft() + (width - 96f * scale) / 2f,
                getPaddingTop() + (height - 96f * scale) / 2f);
        canvas.scale(scale, scale);

        // A soft ground shadow and a warm back panel give the small icon depth.
        fill(Color.argb(23, 171, 87, 32));
        rect.set(15, 81, 84, 89);
        canvas.drawOval(rect, paint);
        fill(Color.rgb(238, 139, 54));
        path.reset();
        path.moveTo(16, 33);
        path.quadTo(16, 28, 21, 28);
        path.lineTo(37, 28);
        path.quadTo(41, 28, 44, 34);
        path.lineTo(79, 34);
        path.quadTo(85, 34, 84, 40);
        path.lineTo(78, 79);
        path.quadTo(77, 84, 72, 84);
        path.lineTo(21, 84);
        path.quadTo(16, 84, 16, 79);
        path.close();
        canvas.drawPath(path, paint);

        int paperSave = canvas.save();
        canvas.rotate(9, 60, 42);
        fill(Color.rgb(255, 235, 215));
        rounded(canvas, 37, 17, 76, 69, 5);
        canvas.restoreToCount(paperSave);

        paperSave = canvas.save();
        canvas.rotate(-6, 46, 40);
        fill(Color.argb(27, 196, 121, 66));
        rounded(canvas, 27, 14, 69, 69, 6);
        fill(Color.rgb(255, 254, 250));
        rounded(canvas, 25, 12, 67, 66, 6);
        fill(Color.rgb(255, 220, 184));
        rounded(canvas, 33, 23, 58, 27, 2);
        fill(Color.rgb(235, 219, 201));
        rounded(canvas, 33, 33, 58, 36, 1.5f);
        rounded(canvas, 33, 41, 58, 44, 1.5f);
        rounded(canvas, 33, 49, 49, 52, 1.5f);
        canvas.restoreToCount(paperSave);

        // The pocket uses a single continuous curve, with no bitmap dependency.
        paint.setShader(new LinearGradient(25, 47, 63, 86,
                Color.rgb(255, 200, 99), Color.rgb(249, 157, 53), Shader.TileMode.CLAMP));
        path.reset();
        path.moveTo(13, 45);
        path.quadTo(12, 40, 18, 40);
        path.lineTo(33, 40);
        path.quadTo(36, 40, 38, 45);
        path.lineTo(79, 45);
        path.quadTo(84, 45, 83, 51);
        path.lineTo(78, 79);
        path.quadTo(77, 85, 71, 85);
        path.lineTo(25, 85);
        path.quadTo(19, 85, 18, 79);
        path.close();
        canvas.drawPath(path, paint);
        fill(Color.argb(170, 255, 255, 246));
        rounded(canvas, 23, 49, 35, 53, 2);

        // Small sun rays add warmth without turning the illustration into a badge.
        fill(Color.rgb(248, 191, 91));
        canvas.drawCircle(13, 19, 3, paint);
        paint.setStrokeWidth(2.6f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(13, 10, 13, 13, paint);
        canvas.drawLine(5, 19, 7, 19, paint);
        canvas.drawLine(7, 12, 9, 14, paint);
        canvas.drawLine(79, 19, 82, 15, paint);
        canvas.drawLine(83, 25, 88, 24, paint);
        fill(Color.argb(180, 255, 219, 163));
        canvas.drawCircle(87, 37, 2, paint);
        canvas.restoreToCount(save);
    }

    private void fill(int color) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
    }

    private void rounded(Canvas canvas, float left, float top, float right, float bottom, float radius) {
        rect.set(left, top, right, bottom);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }
}
