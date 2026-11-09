package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.Bitmap;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;

/** One-worker, bounded-page OCR session for EPUB export. The caller must close it. */
public final class EpubOcrSession implements PdfEpubExporter.OcrAdapter, AutoCloseable {
    private static final int MAX_RECOGNITIONS_PER_NATIVE = 12;
    private static final String MODEL_CHI = "4fef2d1306c8e87616d4d3e4c6c67faf5d44be3342290cf8f2f0f6e3aa7e735b";
    private static final String MODEL_ENG = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2";
    private final boolean deskew;
    private TessBaseAPI api;
    private String dataPath;
    private int recognitionsSinceInit;
    private boolean closed;
    private Thread owner;

    public EpubOcrSession(boolean deskew) { this.deskew = deskew; }

    public static String cacheKey(boolean deskew) {
        return "tesseract4android-4.9.0|chi_sim+eng|lstm|psm-auto|hocr|"
                + MODEL_CHI + "|" + MODEL_ENG + "|preprocess-1|deskew-"
                + (deskew ? "bounded-v1" : "off");
    }

    @Override public PdfEpubExporter.OcrData recognize(Context context, File snapshotPdf,
            int pageIndex, OcrCancellation token) throws IOException {
        checkThread();
        if (closed) throw new IOException("OCR 会话已关闭");
        token.throwIfCancelled();
        OcrImageLoader.Loaded loaded = deskew
                ? OcrImageLoader.renderPdfPageForDeskew(snapshotPdf, pageIndex, token)
                : OcrImageLoader.renderPdfPage(snapshotPdf, pageIndex, token);
        Bitmap original = loaded.bitmap, candidate = null;
        try {
            OcrEngine.Result baseline = run(context, original, pageIndex, token);
            OcrEngine.Result chosen = baseline;
            if (deskew && !token.isCancelled()) {
                double angle = OcrDeskew.estimate(original, token);
                if (Math.abs(angle) >= OcrDeskew.MIN_ANGLE) {
                    try {
                        candidate = OcrDeskew.rotate(original, angle, token);
                        if (candidate != null) {
                            OcrEngine.Result adjusted = run(context, candidate, pageIndex, token);
                            if (OcrDeskew.prefer(baseline, adjusted)) chosen = adjusted;
                        }
                    } catch (InterruptedIOException cancelled) { throw cancelled; }
                    catch (IOException | RuntimeException | OutOfMemoryError adjustmentFailed) {
                        token.throwIfCancelled();
                        // Optional preprocessing may fail; preserve the original OCR result.
                    }
                }
            }
            token.throwIfCancelled();
            return new PdfEpubExporter.OcrData(chosen.text, chosen.confidence, chosen.hocr);
        } finally {
            if (candidate != null && !candidate.isRecycled()) candidate.recycle();
            if (!original.isRecycled()) original.recycle();
            if (token.isCancelled()) discard();
        }
    }

    private OcrEngine.Result run(Context context, Bitmap bitmap, int page,
            OcrCancellation token) throws IOException {
        token.throwIfCancelled();
        String path = dataPath;
        if (api == null) {
            // The installed assets are hashed once when creating a native session.
            // A discarded session verifies them again before reinitialization.
            File root = OcrModelInstaller.ensure(context.getApplicationContext(), token, null);
            path = root.getAbsolutePath();
        }
        boolean success = false;
        OcrCancellation.Stopper stopper = null;
        try {
            if (api == null) {
                api = new TessBaseAPI();
                if (!api.init(path, "chi_sim+eng", TessBaseAPI.OEM_LSTM_ONLY))
                    throw new IOException("离线 OCR 模型初始化失败");
                dataPath = path;
                api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
                api.setVariable("preserve_interword_spaces", "1");
                api.setVariable("user_defined_dpi", "300");
            }
            final TessBaseAPI active = api;
            stopper = new OcrCancellation.Stopper() {
                @Override public void stop() { active.stop(); }
            };
            token.setStopper(stopper);
            token.throwIfCancelled();
            api.setImage(bitmap);
            token.throwIfCancelled();
            String hocr = api.getHOCRText(Math.max(0, page));
            token.throwIfCancelled();
            String text = api.getUTF8Text();
            token.throwIfCancelled();
            int confidence = api.meanConfidence();
            String version = api.getVersion();
            token.throwIfCancelled();
            success = true;
            return new OcrEngine.Result(text == null ? "" : text.replace("\r\n", "\n")
                    .replace('\r', '\n').trim(), hocr, Math.max(0, Math.min(100, confidence)), version);
        } catch (InterruptedIOException cancelled) {
            throw cancelled;
        } catch (LinkageError incompatible) {
            throw new IOException("此设备无法加载 OCR 原生引擎", incompatible);
        } catch (RuntimeException failure) {
            if (token.isCancelled()) throw new InterruptedIOException("已取消文字识别");
            throw new IOException("本地文字识别失败", failure);
        } finally {
            if (stopper != null) token.clearStopper(stopper);
            if (success && !token.isCancelled()) {
                try {
                    api.clear(); // Release per-page image and recognition result.
                    // This AAR has no clearAdaptiveClassifier API. Bound any learned state.
                    if (++recognitionsSinceInit >= MAX_RECOGNITIONS_PER_NATIVE) discard();
                }
                catch (RuntimeException | LinkageError failure) { discard(); }
            } else discard();
        }
    }

    private void checkThread() {
        Thread current = Thread.currentThread();
        if (owner == null) owner = current;
        else if (owner != current) throw new IllegalStateException("OCR 会话只能在创建它的工作线程上使用");
    }

    private void discard() {
        TessBaseAPI old = api; api = null; dataPath = null; recognitionsSinceInit = 0;
        if (old != null) try { old.recycle(); } catch (RuntimeException | LinkageError ignored) { }
    }

    @Override public void close() { checkThread(); closed = true; discard(); }
}
