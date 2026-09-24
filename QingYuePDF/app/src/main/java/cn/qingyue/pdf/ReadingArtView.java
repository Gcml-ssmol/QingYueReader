package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Original scalable illustration of a reading corner; no bundled bitmap or network. */
public final class ReadingArtView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    public ReadingArtView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0 || getHeight() == 0) return;
        int save = canvas.save();
        float scale = Math.min(getWidth() / 124f, getHeight() / 150f);
        canvas.translate((getWidth() - 124 * scale) / 2, (getHeight() - 150 * scale) / 2);
        canvas.scale(scale, scale);
        // Sunlight through a small window.
        fill(AppColors.isDark() ? 0x285f4533 : 0x80fffaf1);
        box(canvas, 26, 0, 122, 103, 2);
        fill(AppColors.isDark() ? 0xff705340 : 0xffefc39e);
        box(canvas, 25, 0, 28, 107, 1);
        box(canvas, 28, 103, 124, 107, 1);
        fill(0x20bc6a30);
        rect.set(3, 130, 124, 148); canvas.drawOval(rect, paint);
        fill(AppColors.isDark() ? 0xff8b654b : 0xffd79b6b);
        box(canvas, 5, 135, 124, 141, 3);
        book(canvas, 28, 119, 105, 132, 0xffa8693b);
        book(canvas, 22, 105, 97, 117, 0xff818875);
        book(canvas, 31, 94, 103, 104, 0xffce9c6d);
        // Branches with paired leaves, retaining warm greens in both themes.
        fill(0xff788267); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f);
        path.reset(); path.moveTo(70, 80); path.cubicTo(71, 58, 68, 33, 70, 17);
        canvas.drawPath(path, paint); paint.setStyle(Paint.Style.FILL);
        leaf(canvas, 70, 28, 61, 6, 0xff899274);
        leaf(canvas, 70, 39, 88, 20, 0xffa3aa88);
        leaf(canvas, 70, 49, 48, 28, 0xffa3aa88);
        leaf(canvas, 70, 60, 94, 41, 0xff7f8b6b);
        leaf(canvas, 70, 71, 48, 52, 0xff889575);
        leaf(canvas, 71, 79, 91, 60, 0xffa6ad8c);
        paint.setShader(new LinearGradient(56, 77, 88, 95, new int[]{0xffe9ad73, 0xffb86e3e}, null, Shader.TileMode.CLAMP));
        path.reset(); path.moveTo(55, 77); path.lineTo(88, 77); path.lineTo(83, 92); path.quadTo(72, 99, 61, 92); path.close();
        canvas.drawPath(path, paint); fill(0xfff2c49b); rect.set(54, 74, 89, 81); canvas.drawOval(rect, paint);
        fill(0xff806b49); rect.set(58, 75, 85, 79); canvas.drawOval(rect, paint);
        canvas.restoreToCount(save);
    }

    private void book(Canvas c, float l, float t, float r, float b, int color) {
        fill(color); box(c, l, t, r, b, 2);
        fill(0xfffff4df); box(c, l + 5, t + 3, r - 2, b - 2, 1);
    }
    private void leaf(Canvas c, float x, float y, float tipX, float tipY, int color) {
        fill(color); path.reset(); path.moveTo(x, y);
        path.quadTo(tipX - 8, y - 2, tipX, tipY);
        path.quadTo(x + 8, tipY + 4, x, y); path.close(); c.drawPath(path, paint);
    }
    private void box(Canvas c, float l, float t, float r, float b, float radius) {
        rect.set(l, t, r, b); c.drawRoundRect(rect, radius, radius, paint);
    }
    private void fill(int color) { paint.setShader(null); paint.setStyle(Paint.Style.FILL); paint.setColor(color); }
}
