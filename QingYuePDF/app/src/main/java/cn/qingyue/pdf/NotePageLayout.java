package cn.qingyue.pdf;

/** Finite note pages share one scroll coordinate; item coordinates remain page local. */
final class NotePageLayout {
    private final double width, height, gap;

    NotePageLayout(double width, double height, double gap) {
        if (!(width > 0) || !(height > 0) || !(gap >= 0)) throw new IllegalArgumentException("Invalid page size");
        this.width = width;
        this.height = height;
        this.gap = gap;
    }

    double stride() { return height + gap; }
    double top(int page) { return page * stride(); }
    double localY(int page, double globalY) { return globalY - top(page); }
    double bottom(int page) { return top(page) + height; }
    double totalHeight(int pages) { return bottom(pages - 1); }

    /** Returns -1 for the paper gap and outside all pages. */
    int hit(double x, double y, int pages) {
        if (x < 0 || x > width || y < 0 || pages < 1) return -1;
        int page = (int) Math.floor(y / stride());
        return page < pages && y <= bottom(page) ? page : -1;
    }

    /** Page nearest the viewport centre, including when the centre is in a gap. */
    int nearest(double y, int pages) {
        if (pages < 1) throw new IllegalArgumentException("No pages");
        int page = (int) Math.floor((y + gap / 2) / stride());
        return Math.max(0, Math.min(pages - 1, page));
    }

    int firstVisible(double top, int pages) {
        return Math.max(0, Math.min(pages, (int) Math.floor(top / stride())));
    }

    int lastVisible(double bottom, int pages) {
        return Math.max(-1, Math.min(pages - 1, (int) Math.floor(bottom / stride())));
    }
}
