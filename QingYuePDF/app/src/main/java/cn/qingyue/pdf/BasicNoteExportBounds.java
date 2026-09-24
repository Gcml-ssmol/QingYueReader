package cn.qingyue.pdf;

import java.io.IOException;
import java.util.List;

/** A bounded, single-page export area in note-point coordinates. */
final class BasicNoteExportBounds {
    static final int MAX_SIDE = 4096;
    static final long MAX_PIXELS = 16_000_000L;
    static final double MAX_PAGE_POINTS = 14400;
    final double left, top, width, height;
    final int pixelsWide, pixelsHigh;

    private BasicNoteExportBounds(double left, double top, double width, double height, int pixelsWide, int pixelsHigh) {
        this.left = left; this.top = top; this.width = width; this.height = height;
        this.pixelsWide = pixelsWide; this.pixelsHigh = pixelsHigh;
    }

    static BasicNoteExportBounds forDocument(NoteDocument document) throws IOException {
        return forItems(document,document.items());
    }
    static BasicNoteExportBounds forItems(NoteDocument document,List<NoteDocument.Item> items) throws IOException {
        double left = 0, top = 0, right = document.width, bottom = document.height;
        if (document.infinite()) {
            if (items.isEmpty()) { right = 595.3; bottom = 841.9; }
            else {
                left = top = Double.POSITIVE_INFINITY;
                right = bottom = Double.NEGATIVE_INFINITY;
                for (NoteDocument.Item item : items) {
                    double pad = item.type == NoteDocument.IMAGE || item.type == NoteDocument.TEXT ? 0 : item.width / 2.0;
                    left = Math.min(left, item.left() - pad); top = Math.min(top, item.top() - pad);
                    right = Math.max(right, item.right() + pad); bottom = Math.max(bottom, item.bottom() + pad);
                }
                left -= 24; top -= 24; right += 24; bottom += 24;
            }
        }
        double width = right - left, height = bottom - top;
        if (Double.isNaN(width) || Double.isInfinite(width) || Double.isNaN(height) || Double.isInfinite(height) || width <= 0 || height <= 0 ||
                Math.abs(left) > 1_000_000 || Math.abs(top) > 1_000_000 ||
                Math.abs(right) > 1_000_000 || Math.abs(bottom) > 1_000_000 ||
                width > MAX_PAGE_POINTS || height > MAX_PAGE_POINTS)
            throw new IOException("画布内容范围过大，无法导出为单页；请缩小内容范围。");
        double scale = Math.min(2, Math.min(MAX_SIDE / width, MAX_SIDE / height));
        scale = Math.min(scale, Math.sqrt(MAX_PIXELS / (width * height)));
        int pw = Math.max(1, (int)Math.ceil(width * scale));
        int ph = Math.max(1, (int)Math.ceil(height * scale));
        while (pw > MAX_SIDE || ph > MAX_SIDE || (long)pw * ph > MAX_PIXELS) {
            scale *= .999; pw = Math.max(1, (int)Math.ceil(width * scale)); ph = Math.max(1, (int)Math.ceil(height * scale));
        }
        return new BasicNoteExportBounds(left, top, width, height, pw, ph);
    }
}
