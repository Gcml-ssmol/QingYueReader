package android.graphics.pdf;

import android.os.ParcelFileDescriptor;

/** Compile-only stub; manager regressions do not scan PDF pages. */
public final class PdfRenderer implements AutoCloseable {
    public PdfRenderer(ParcelFileDescriptor descriptor) { }
    public int getPageCount() { return 1; }
    @Override public void close() { }
}
