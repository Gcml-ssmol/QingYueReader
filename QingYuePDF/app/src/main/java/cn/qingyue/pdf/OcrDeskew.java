package cn.qingyue.pdf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import java.io.IOException;

/** Conservative small-angle projection search; never changes the caller's bitmap. */
final class OcrDeskew {
    static final double MIN_ANGLE = 0.4;
    private static final int MAX_EDGE = 2500, MAX_PIXELS = 4_000_000;
    private static final int SAMPLE_EDGE = 500;
    private static final double[] ANGLES = {-2, -1.5, -1, -0.5, 0, 0.5, 1, 1.5, 2};

    private OcrDeskew() { }

    static double estimate(Bitmap source, OcrCancellation token) throws IOException {
        int width = source.getWidth(), height = source.getHeight();
        if (width < 128 || height < 128 || width > MAX_EDGE || height > MAX_EDGE
                || (long) width * height > MAX_PIXELS) return 0;
        int scale = Math.max(1, (int)Math.ceil(Math.max(width, height) / (double)SAMPLE_EDGE));
        int sw = Math.max(1, width / scale), sh = Math.max(1, height / scale);
        if (sw < 80 || sh < 80) return 0;
        byte[] ink = new byte[sw * sh];
        int[] row = new int[width];
        int dark = 0;
        for (int y = 0; y < sh; y++) {
            token.throwIfCancelled();
            source.getPixels(row, 0, width, 0, Math.min(height - 1, y * scale), width, 1);
            for (int x = 0; x < sw; x++) {
                int c = row[Math.min(width - 1, x * scale)];
                int gray = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100;
                if (gray < 140 && Color.alpha(c) > 127) { ink[y * sw + x] = 1; dark++; }
            }
        }
        if (dark < 100 || dark > ink.length / 3) return 0;
        return estimateInk(ink, sw, sh, token);
    }

    static double estimateInk(byte[] ink, int width, int height, OcrCancellation token)
            throws IOException {
        if (ink.length != width * height || width < 1 || height < 1) return 0;
        double base = score(ink, width, height, 0, token), best = base, angle = 0;
        for (double current : ANGLES) {
            if (current == 0) continue;
            double value = score(ink, width, height, current, token);
            if (value > best) { best = value; angle = current; }
        }
        // Noise and ordinary letter shapes can also increase projection score.
        return best > base * 1.12 ? angle : 0;
    }

    private static double score(byte[] ink, int width, int height, double angle,
            OcrCancellation token) throws IOException {
        int margin = (int)Math.ceil(width * Math.tan(Math.toRadians(2))) + 2;
        int[] lines = new int[height + margin * 2];
        double slope = Math.tan(Math.toRadians(angle));
        for (int y = 0; y < height; y++) {
            if ((y & 31) == 0) token.throwIfCancelled();
            for (int x = 0; x < width; x++) if (ink[y * width + x] != 0) {
                int projected = (int)Math.round(y - (x - width / 2.0) * slope) + margin;
                if (projected >= 0 && projected < lines.length) lines[projected]++;
            }
        }
        double sum = 0;
        for (int count : lines) sum += (double)count * count;
        return sum;
    }

    static Bitmap rotate(Bitmap source, double angle, OcrCancellation token) throws IOException {
        if (Math.abs(angle) < MIN_ANGLE || Math.abs(angle) > 2
                || source.getWidth() > MAX_EDGE || source.getHeight() > MAX_EDGE
                || (long)source.getWidth() * source.getHeight() > MAX_PIXELS) return null;
        token.throwIfCancelled();
        Bitmap output = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        boolean keep = false;
        try {
            Canvas canvas = new Canvas(output);
            canvas.drawColor(Color.WHITE);
            canvas.rotate((float)-angle, source.getWidth() / 2f, source.getHeight() / 2f);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(source, 0, 0, paint);
            token.throwIfCancelled();
            keep = true;
            return output;
        } finally { if (!keep) output.recycle(); }
    }

    static boolean prefer(OcrEngine.Result original, OcrEngine.Result adjusted) {
        if (adjusted == null || adjusted.confidence < original.confidence + 3) return false;
        int oldChars = meaningful(original.text), newChars = meaningful(adjusted.text);
        if (newChars == 0 || newChars < oldChars || han(adjusted.text) < han(original.text)) return false;
        return !adjusted.hocr.isEmpty();
    }

    private static int meaningful(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) if (!Character.isWhitespace(text.charAt(i))) count++;
        return count;
    }

    private static int han(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++)
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) count++;
        return count;
    }
}
