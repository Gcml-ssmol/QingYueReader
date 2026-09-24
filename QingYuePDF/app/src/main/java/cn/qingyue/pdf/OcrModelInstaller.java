package cn.qingyue.pdf;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.security.*;
import java.util.Locale;

/** Installs pinned tessdata assets into an app-private cache after verifying exact bytes. */
public final class OcrModelInstaller {
    private static final Model[] MODELS = new Model[] {
        new Model("chi_sim.traineddata", 13077423L,
                "4fef2d1306c8e87616d4d3e4c6c67faf5d44be3342290cf8f2f0f6e3aa7e735b"),
        new Model("eng.traineddata", 4113088L,
                "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2")
    };

    public interface Progress { void update(int percent, String stage); }
    private OcrModelInstaller() { }

    /** Returns the parent directory that contains the required {@code tessdata/} directory. */
    public static synchronized File ensure(Context context, OcrCancellation cancellation,
            Progress progress)
            throws IOException {
        File root = new File(context.getFilesDir(), "ocr-models");
        File data = new File(root, "tessdata");
        if (!data.isDirectory() && !data.mkdirs()) throw new IOException("无法创建本地 OCR 模型目录");
        long total = 0, finished = 0; for (Model model : MODELS) total += model.bytes;
        for (Model model : MODELS) {
            cancellation.throwIfCancelled();
            File destination = new File(data, model.name);
            if (!matches(destination, model)) install(context, destination, model, cancellation,
                    progress, finished, total);
            finished += model.bytes;
            if (progress != null) progress.update((int)Math.min(100, finished * 100 / total),
                    "准备离线中英文模型");
        }
        return root;
    }

    private static void install(Context context, File destination, Model model,
            OcrCancellation cancellation, Progress progress, long before, long total)
            throws IOException {
        AtomicFile file = new AtomicFile(destination); FileOutputStream out = null;
        try (InputStream in = new BufferedInputStream(
                context.getAssets().open("tessdata/" + model.name))) {
            MessageDigest digest = sha256(); out = file.startWrite(); byte[] buffer = new byte[32768];
            long copied = 0; int count;
            while ((count = in.read(buffer)) != -1) {
                cancellation.throwIfCancelled(); out.write(buffer, 0, count); digest.update(buffer, 0, count);
                copied += count;
                if (copied > model.bytes) throw new IOException("OCR 模型大小与清单不一致");
                if (progress != null) progress.update((int)Math.min(99,
                        (before + copied) * 100 / total), "准备离线中英文模型");
            }
            if (copied != model.bytes || !hex(digest.digest()).equals(model.sha256))
                throw new IOException("OCR 模型校验失败，请重新安装应用");
            out.getFD().sync(); file.finishWrite(out); out = null;
        } catch (IOException | RuntimeException failure) {
            if (out != null) file.failWrite(out);
            if (failure instanceof IOException) throw (IOException)failure;
            throw new IOException("无法准备 OCR 模型", failure);
        }
    }

    private static boolean matches(File file, Model model) throws IOException {
        if (!file.isFile() || file.length() != model.bytes) return false;
        MessageDigest digest = sha256(); byte[] buffer = new byte[32768]; int count;
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return model.sha256.equals(hex(digest.digest()));
    }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("设备不支持 SHA-256", impossible); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value));
        return out.toString();
    }

    private static final class Model {
        final String name, sha256; final long bytes;
        Model(String name, long bytes, String sha256) {
            this.name = name; this.bytes = bytes; this.sha256 = sha256;
        }
    }
}
