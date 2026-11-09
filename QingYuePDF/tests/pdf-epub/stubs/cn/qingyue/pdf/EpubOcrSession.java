package cn.qingyue.pdf;

import android.content.Context;
import java.io.File;
import java.io.IOException;

/** Desktop test compiles the injected OCR path without Android native libraries. */
public final class EpubOcrSession implements PdfEpubExporter.OcrAdapter, AutoCloseable {
    public EpubOcrSession(boolean deskew) { }
    public static String cacheKey(boolean deskew) { return "desktop-ocr-" + deskew; }
    @Override public PdfEpubExporter.OcrData recognize(Context c,File source,int page,OcrCancellation token)
            throws IOException { throw new IOException("Desktop test requires injected OCR"); }
    @Override public void close() { }
}
