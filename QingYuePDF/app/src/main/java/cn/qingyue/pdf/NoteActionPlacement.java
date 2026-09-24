package cn.qingyue.pdf;

/** Window offsets measured from the visible app frame, including split screen insets. */
final class NoteActionPlacement {
    private NoteActionPlacement() { }

    static int[] place(int frameLeft, int frameTop, int frameRight, int frameBottom,
                       int anchorLeft, int anchorBottom, int width, int height, int margin) {
        int availableWidth = Math.max(1, frameRight - frameLeft);
        int availableHeight = Math.max(1, frameBottom - frameTop);
        int x = Math.max(margin, Math.min(anchorLeft - frameLeft, availableWidth - width - margin));
        int y = anchorBottom - frameTop + margin;
        if (y + height + margin <= availableHeight) return new int[]{x, y, 1};
        return new int[]{Math.max(0, (availableWidth - width) / 2),
                Math.max(0, (availableHeight - height) / 2), 0};
    }
}
