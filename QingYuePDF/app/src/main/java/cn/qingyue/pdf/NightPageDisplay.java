package cn.qingyue.pdf;

import android.graphics.ColorMatrixColorFilter;

/** Screen-only page colors. Source PDF, stored ink, images and exports keep original bytes. */
final class NightPageDisplay {
    static final int PAPER = 0xff202327;
    static final int RULE = 0xff343941;
    // One shared filter; no full-page bitmap allocation during drawing.
    static final ColorMatrixColorFilter PDF_FILTER = new ColorMatrixColorFilter(new float[] {
            -0.78f, 0, 0, 0, 224,
            0, -0.78f, 0, 0, 224,
            0, 0, -0.78f, 0, 224,
            0, 0, 0, 1, 0
    });

    private NightPageDisplay() { }

    /** Lift opaque dark ink while keeping the persisted ARGB untouched. */
    static int ink(int argb) { return NightInk.display(argb, PAPER); }
}
