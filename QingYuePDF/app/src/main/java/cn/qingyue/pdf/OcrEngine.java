package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.Bitmap;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.*;

/** Real, on-device simplified-Chinese + English OCR backed by bundled Tesseract models. */
public final class OcrEngine {
    public interface Progress { void update(int percent, String stage); }

    public static final class Result {
        public final String text, hocr, engineVersion;
        public final int confidence;
        Result(String text, String hocr, int confidence, String engineVersion) {
            this.text = text == null ? "" : text;
            this.hocr = hocr == null ? "" : hocr;
            this.confidence = confidence;
            this.engineVersion = engineVersion == null ? "" : engineVersion;
        }
    }

    private OcrEngine() { }

    public enum Language { CHINESE, MIXED, ENGLISH }
    public enum Layout { BODY, SPARSE }

    /** Blocking worker-thread call. The input bitmap remains owned by the caller. */
    public static Result recognize(Context context, Bitmap image, final int page,
            final OcrCancellation cancellation, final Progress progress) throws IOException {
        return recognize(context, image, page, Language.CHINESE, Layout.BODY, cancellation, progress);
    }

    public static Result recognize(Context context, Bitmap image, final int page,
            Language language, Layout layout, final OcrCancellation cancellation,
            final Progress progress) throws IOException {
        if (context == null || image == null || image.isRecycled())
            throw new IOException("没有可识别的图片");
        if (image.getWidth() < 16 || image.getHeight() < 16)
            throw new IOException("图片尺寸太小，无法识别文字");
        cancellation.throwIfCancelled();
        File dataRoot = OcrModelInstaller.ensure(context.getApplicationContext(), cancellation,
                new OcrModelInstaller.Progress() {
                    @Override public void update(int percent, String stage) {
                        if (progress != null) progress.update(Math.min(12, percent * 12 / 100), stage);
                    }
                });
        cancellation.throwIfCancelled();

        TessBaseAPI api = null;
        OcrCancellation.Stopper stopper = null;
        try {
            api = new TessBaseAPI(new TessBaseAPI.ProgressNotifier() {
                @Override public void onProgressValues(TessBaseAPI.ProgressValues values) {
                    if (progress != null) progress.update(12 + values.getPercent() * 88 / 100,
                            "正在识别印刷文字");
                }
            });
            final TessBaseAPI active = api;
            stopper = new OcrCancellation.Stopper() {
                @Override public void stop() { active.stop(); }
            };
            cancellation.setStopper(stopper);
            cancellation.throwIfCancelled();
            OcrSelection selection = OcrSelection.fromIndices(language.ordinal(), layout.ordinal());
            if (!api.init(dataRoot.getAbsolutePath(), selection.models, TessBaseAPI.OEM_LSTM_ONLY))
                throw new IOException("离线 OCR 模型初始化失败");
            api.setPageSegMode(selection.sparse
                    ? TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT : TessBaseAPI.PageSegMode.PSM_AUTO);
            api.setVariable("preserve_interword_spaces", "1");
            api.setVariable("user_defined_dpi", "300");
            api.setImage(image);
            cancellation.throwIfCancelled();

            // getHOCRText performs the recognition through the interruptible native path.
            String hocr = api.getHOCRText(Math.max(0, page));
            cancellation.throwIfCancelled();
            String text = api.getUTF8Text();
            cancellation.throwIfCancelled();
            int confidence = api.meanConfidence();
            String version = api.getVersion();
            if (progress != null) progress.update(100, "识别完成");
            return new Result(clean(text), hocr, Math.max(0, Math.min(100, confidence)), version);
        } catch (InterruptedIOException cancelled) {
            throw cancelled;
        } catch (LinkageError incompatible) {
            throw new IOException("此设备无法加载 OCR 原生引擎", incompatible);
        } catch (RuntimeException failure) {
            if (cancellation.isCancelled()) throw new InterruptedIOException("已取消文字识别");
            throw new IOException("本地文字识别失败", failure);
        } finally {
            if (stopper != null) cancellation.clearStopper(stopper);
            if (api != null) try { api.recycle(); } catch (RuntimeException | LinkageError ignored) { }
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.length() <= 2_000_000) return normalized;
        return normalized.substring(0, 2_000_000);
    }
}
