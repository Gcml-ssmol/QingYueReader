package cn.qingyue.pdf;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

/** Shared point-coordinate text layout for the editor and PDF/PNG export. */
final class NoteTextLayout {
    private NoteTextLayout() { }

    static StaticLayout layout(String text, float size, int color, double width) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setTextSize(size);
        return new StaticLayout(text, paint, Math.max(1, (int)Math.ceil(width)),
                Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
    }

    static double height(String text, float size, int color, double width) {
        return Math.max(size * 1.25, layout(text, size, color, width).getHeight());
    }

    static void draw(Canvas canvas, NoteDocument.Item item) {
        draw(canvas, item, item.color);
    }

    /** Display override must not mutate the stored item used by export. */
    static void draw(Canvas canvas, NoteDocument.Item item, int color) {
        int save = canvas.save();
        canvas.translate((float)item.left(), (float)item.top());
        StaticLayout layout = layout(item.text, item.width, color, item.right() - item.left());
        layout.draw(canvas);
        canvas.restoreToCount(save);
    }
}
