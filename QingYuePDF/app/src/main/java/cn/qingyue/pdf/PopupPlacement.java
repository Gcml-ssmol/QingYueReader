package cn.qingyue.pdf;

/** Screen-pixel placement for an icon-anchored panel; right/bottom bounds are exclusive. */
public final class PopupPlacement {
    private PopupPlacement() {}

    /**
     * Returns {x, y, width, height}, entirely inside the visible screen rectangle.
     * Prefers below the anchor, switching above only when below cannot fit the requested
     * height and above has more space. Margins shrink on tiny screens to leave one pixel.
     * Nonpositive desired dimensions become one pixel; negative margin/gap become zero.
     * If neither direction has room, a one-pixel panel is clamped inside the screen.
     */
    public static int[] place(int screenLeft, int screenTop, int screenRight, int screenBottom,
                              int anchorLeft, int anchorTop, int anchorRight, int anchorBottom,
                              int desiredWidth, int desiredHeight, int margin, int gap) {
        if (screenRight <= screenLeft || screenBottom <= screenTop) {
            throw new IllegalArgumentException("Visible screen rectangle must have positive dimensions");
        }
        // Long intermediates also handle valid rectangles crossing the integer origin.
        long screenWidth = (long) screenRight - screenLeft;
        long screenHeight = (long) screenBottom - screenTop;
        long horizontalMargin = Math.min(Math.max(0L, margin), (screenWidth - 1) / 2);
        long verticalMargin = Math.min(Math.max(0L, margin), (screenHeight - 1) / 2);
        long left = screenLeft + horizontalMargin;
        long top = screenTop + verticalMargin;
        long right = screenRight - horizontalMargin;
        long bottom = screenBottom - verticalMargin;
        long width = Math.min(Math.max(1L, desiredWidth), right - left);
        long requestedHeight = Math.max(1L, desiredHeight);
        long spacing = Math.max(0L, gap);
        long anchorUpper = Math.min(anchorTop, anchorBottom);
        long anchorLower = Math.max(anchorTop, anchorBottom);
        long belowTop = clamp(anchorLower + spacing, top, bottom);
        long aboveBottom = clamp(anchorUpper - spacing, top, bottom);
        long belowSpace = bottom - belowTop;
        long aboveSpace = aboveBottom - top;
        boolean above = belowSpace < requestedHeight && aboveSpace > belowSpace;
        long availableHeight = above ? aboveSpace : belowSpace;
        long height = Math.min(requestedHeight, Math.max(1L, availableHeight));
        // Arithmetic shift divides by two toward negative infinity, including negative
        // odd coordinates, without Math.floorDiv (only available from Android API 24).
        long centeredLeft = ((long) anchorLeft + anchorRight - width) >> 1;
        long x = clamp(centeredLeft, left, right - width);
        long y = clamp(above ? aboveBottom - height : belowTop, top, bottom - height);
        return new int[]{(int) x, (int) y, (int) width, (int) height};
    }

    /**
     * Adds a usable-height fallback for keyboards and short landscape windows. If the
     * anchored panel would be shorter than minimumUsableHeight, use the full inner
     * viewport (up to desiredHeight), allowing overlap with the anchor. The original
     * position is kept as far as the new height permits. A nonpositive minimum disables
     * this fallback; the panel always stays inside the same margins and screen bounds.
     */
    public static int[] place(int screenLeft, int screenTop, int screenRight, int screenBottom,
                              int anchorLeft, int anchorTop, int anchorRight, int anchorBottom,
                              int desiredWidth, int desiredHeight, int margin, int gap,
                              int minimumUsableHeight) {
        int[] result = place(screenLeft, screenTop, screenRight, screenBottom,
                anchorLeft, anchorTop, anchorRight, anchorBottom,
                desiredWidth, desiredHeight, margin, gap);
        if (result[3] >= minimumUsableHeight) return result;
        long screenHeight = (long) screenBottom - screenTop;
        long verticalMargin = Math.min(Math.max(0L, margin), (screenHeight - 1) / 2);
        long top = screenTop + verticalMargin;
        long bottom = screenBottom - verticalMargin;
        long height = Math.min(Math.max(1L, desiredHeight), bottom - top);
        result[1] = (int) clamp(result[1], top, bottom - height);
        result[3] = (int) height;
        return result;
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
