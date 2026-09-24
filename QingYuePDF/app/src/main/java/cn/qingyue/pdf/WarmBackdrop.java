package cn.qingyue.pdf;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Quiet, scalable cream-and-apricot backgrounds for the library and import card. */
public final class WarmBackdrop extends Drawable {
    private final boolean hero;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF area = new RectF();
    private final float density = Resources.getSystem().getDisplayMetrics().density;
    private int opacity = 255;

    public WarmBackdrop(boolean hero) {
        this.hero = hero;
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        if (!AppColors.isClassic()) {
            drawCream(canvas, bounds);
            return;
        }
        area.set(bounds);
        int save = canvas.saveLayerAlpha(area, opacity);
        canvas.clipRect(bounds);
        float w = area.width();
        float h = area.height();
        if (hero) {
            float radius = 24f * density;
            // The rounded silhouette remains clean even without parent clipping.
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(area, radius, radius, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            paint.setShader(new LinearGradient(area.left, area.top, area.right, area.bottom,
                    AppColors.isDark() ? AppColors.SURFACE : Color.rgb(255, 235, 213), AppColors.isDark() ? AppColors.SOFT : Color.rgb(255, 216, 181), Shader.TileMode.CLAMP));
            canvas.drawRect(area, paint);
            paint.setShader(null);
            paint.setColor(Color.argb(34, 241, 154, 91));
            canvas.drawCircle(area.left + w * .80f, area.bottom + h * .42f,
                    Math.max(h * 1.04f, w * .22f), paint);
            paint.setColor(Color.argb(44, 255, 255, 255));
            canvas.drawCircle(area.right + h * .02f, area.top - h * .19f, h * 1.12f, paint);
            paint.setColor(Color.argb(19, 240, 139, 65));
            canvas.drawCircle(area.left + w * .62f, area.bottom + h * .75f, h * 1.2f, paint);
        } else {
            paint.setShader(new LinearGradient(area.left, area.top, area.left, area.bottom,
                    AppColors.isDark() ? AppColors.BACKGROUND : Color.rgb(255, 248, 237), AppColors.isDark() ? AppColors.SURFACE : Color.rgb(255, 243, 228), Shader.TileMode.CLAMP));
            canvas.drawRect(area, paint);
            // Keep decoration at the edges, leaving the document list uncluttered.
            float r = Math.min(w * .52f, h * .48f);
            paint.setShader(new RadialGradient(area.right, area.bottom, Math.max(1f, r * 1.65f),
                    new int[]{Color.argb(90, 255, 210, 169), Color.argb(0, 255, 210, 169)},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(area, paint);
            paint.setShader(null);
            paint.setColor(Color.argb(51, 249, 193, 145));
            canvas.drawCircle(area.right + r * .32f, area.bottom + r * .10f, r, paint);
            paint.setColor(Color.argb(43, 255, 204, 160));
            canvas.drawCircle(area.left - r * .33f, area.bottom + r * .38f, r * 1.07f, paint);
            paint.setColor(Color.argb(29, 250, 193, 144));
            canvas.drawCircle(area.left - r * .49f, area.bottom - r * .13f, r * .81f, paint);
        }
        paint.setShader(null);
        canvas.restoreToCount(save);
    }

    private void drawCream(Canvas canvas, Rect bounds) {
        area.set(bounds);
        int save = canvas.saveLayerAlpha(area, opacity);
        canvas.clipRect(bounds);
        float w = area.width(), h = area.height();
        boolean dark = AppColors.isDark();
        if (hero) {
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(area, 22 * density, 22 * density, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            paint.setShader(new LinearGradient(area.left, area.top, area.right, area.bottom,
                    dark ? 0xff403025 : 0xffffead5, dark ? 0xff4b3425 : 0xffffdfbf, Shader.TileMode.CLAMP));
            canvas.drawRect(area, paint);
            paint.setShader(null);
            paint.setColor(dark ? 0x155e3b25 : 0x55fffaf1);
            canvas.drawCircle(area.right + h * .06f, area.top - h * .1f, h * .9f, paint);
        } else {
            paint.setShader(new LinearGradient(area.left, area.top, area.left, area.bottom,
                    AppColors.BACKGROUND, dark ? 0xff2e261f : 0xfffff4e7, Shader.TileMode.CLAMP));
            canvas.drawRect(area, paint);
            float radius = Math.max(1, Math.min(w * .7f, h * .48f));
            paint.setShader(new RadialGradient(area.right, area.bottom, radius,
                    new int[]{dark ? 0x144d3427 : 0x30ffcc9c, 0x00ffcc9c}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(area, paint);
            paint.setShader(null);
            paint.setColor(dark ? 0x124d3427 : 0x14edc79c);
            canvas.drawCircle(area.left - radius * .54f, area.top + h * .78f, radius * .7f, paint);
        }
        paint.setShader(null);
        canvas.restoreToCount(save);
    }

    @Override public void setAlpha(int alpha) {
        opacity = Math.max(0, Math.min(255, alpha));
        invalidateSelf();
    }

    @Override public int getAlpha() { return opacity; }

    @Override public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
