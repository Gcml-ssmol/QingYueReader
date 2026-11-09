package cn.qingyue.pdf;

import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Bounded local image decoding and scan-PDF page rendering for OCR. */
public final class OcrImageLoader {
    private static final int MAX_EDGE = 3000;
    private static final long MAX_PIXELS = 10000000L;
    private OcrImageLoader() { }

    public static final class Loaded {
        public final Bitmap bitmap;
        public final String displayName;
        public final long sourceBytes;
        Loaded(Bitmap bitmap, String displayName, long sourceBytes) {
            this.bitmap = bitmap; this.displayName = displayName; this.sourceBytes = sourceBytes;
        }
    }

    public static Loaded load(Context context, Uri uri, OcrCancellation cancellation)
            throws IOException {
        if (uri == null) throw new IOException("没有选择图片");
        ContentResolver resolver = context.getContentResolver();
        Metadata metadata = metadata(resolver, uri);
        int orientation = 1;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in != null) orientation = readExifOrientation(in);
        } catch (RuntimeException ignored) { orientation = 1; }
        cancellation.throwIfCancelled();

        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException("无法读取所选图片");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            throw new IOException("图片格式不受支持或内容已损坏");
        if (bounds.outWidth > 100000 || bounds.outHeight > 100000)
            throw new IOException("图片尺寸异常，无法安全解码");
        int sample = 1;
        while ((Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE * 2)
                || ((long)Math.max(1, bounds.outWidth / sample)
                * Math.max(1, bounds.outHeight / sample) > MAX_PIXELS * 2)) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inDither = false;
        Bitmap decoded;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException("无法再次读取所选图片");
            decoded = BitmapFactory.decodeStream(in, null, options);
        } catch (OutOfMemoryError exhausted) {
            throw new IOException("图片过大，设备内存不足", exhausted);
        }
        if (decoded == null) throw new IOException("图片解码失败");
        Bitmap owned = decoded;
        try {
            cancellation.throwIfCancelled();
            Bitmap oriented = orient(owned, orientation);
            if (oriented != owned) { owned.recycle(); owned = oriented; }
            Bitmap scaled = limit(owned);
            if (scaled != owned) { owned.recycle(); owned = scaled; }
            cancellation.throwIfCancelled();
            Bitmap result = owned; owned = null;
            return new Loaded(result, metadata.name, metadata.bytes);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IOException) throw (IOException)failure;
            throw new IOException("无法准备 OCR 图片", failure);
        } catch (LinkageError incompatible) {
            throw new IOException("此设备无法加载图片解码组件", incompatible);
        } catch (OutOfMemoryError exhausted) {
            throw new IOException("图片过大，设备内存不足", exhausted);
        } finally {
            if (owned != null && !owned.isRecycled()) owned.recycle();
        }
    }

    public static Loaded renderPdfPage(File pdf, int requestedPage, OcrCancellation cancellation)
            throws IOException {
        return renderPdfPage(pdf, requestedPage, MAX_EDGE, MAX_PIXELS, cancellation);
    }

    /** OCR source for optional deskew. Baseline and rotated candidate use the same bounded raster. */
    public static Loaded renderPdfPageForDeskew(File pdf, int requestedPage,
            OcrCancellation cancellation) throws IOException {
        return renderPdfPage(pdf, requestedPage, 2500, 4000000L, cancellation);
    }

    /** A bounded display bitmap. Never use its coordinates for the OCR text layer. */
    public static Loaded renderPdfPreview(File pdf, int requestedPage,
            OcrCancellation cancellation) throws IOException {
        return renderPdfPage(pdf, requestedPage, 1600, 2500000L, cancellation);
    }

    private static Loaded renderPdfPage(File pdf, int requestedPage, int maxEdge,
            long maxPixels, OcrCancellation cancellation) throws IOException {
        if (pdf == null || !pdf.isFile()) throw new IOException("找不到当前 PDF");
        cancellation.throwIfCancelled();
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(pdf,
                ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            if (renderer.getPageCount() < 1) throw new IOException("PDF 没有可识别页面");
            if (requestedPage < 0 || requestedPage >= renderer.getPageCount())
                throw new IOException("PDF 页码超出范围");
            int pageIndex = requestedPage;
            try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
                double scale = Math.min(maxEdge / (double)Math.max(page.getWidth(), page.getHeight()),
                        Math.sqrt(maxPixels / (double)((long)page.getWidth() * page.getHeight())));
                if (!(scale > 0.0) || Double.isInfinite(scale) || Double.isNaN(scale))
                    throw new IOException("PDF 页面尺寸无效");
                long widthLong = Math.max(1L, Math.round(page.getWidth() * scale));
                long heightLong = Math.max(1L, Math.round(page.getHeight() * scale));
                if (widthLong > maxEdge || heightLong > maxEdge
                        || widthLong * heightLong > maxPixels
                        || widthLong > Integer.MAX_VALUE || heightLong > Integer.MAX_VALUE)
                    throw new IOException("PDF 页面尺寸超过安全渲染上限");
                int width = (int)widthLong, height = (int)heightLong;
                Bitmap bitmap;
                try { bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); }
                catch (OutOfMemoryError exhausted) {
                    throw new IOException("页面较大，设备内存不足，无法进行 OCR", exhausted);
                }
                boolean transferred = false;
                try {
                    bitmap.eraseColor(Color.WHITE);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    cancellation.throwIfCancelled();
                    transferred = true;
                    return new Loaded(bitmap, pdf.getName(), pdf.length());
                } finally { if (!transferred && !bitmap.isRecycled()) bitmap.recycle(); }
            }
        } catch (SecurityException | IllegalStateException failure) {
            throw new IOException("无法渲染当前 PDF 页面", failure);
        } catch (LinkageError incompatible) {
            throw new IOException("此设备无法加载 PDF 页面渲染组件", incompatible);
        }
    }

    static int readExifOrientation(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(stream));
        if (in.readUnsignedByte() != 0xff || in.readUnsignedByte() != 0xd8) return 1;
        while (true) {
            int prefix;
            do { prefix = in.read(); if (prefix < 0) return 1; } while (prefix != 0xff);
            int marker; do { marker = in.read(); if (marker < 0) return 1; } while (marker == 0xff);
            if (marker == 0xd9 || marker == 0xda) return 1;
            int length = in.readUnsignedShort();
            if (length < 2) return 1;
            int payload = length - 2;
            if (marker == 0xe1 && payload <= 65533) {
                byte[] data = new byte[payload]; in.readFully(data);
                int parsed = parseExif(data); if (parsed >= 1 && parsed <= 8) return parsed;
            } else skipFully(in, payload);
        }
    }

    private static int parseExif(byte[] data) {
        if (data.length < 14 || data[0] != 'E' || data[1] != 'x' || data[2] != 'i'
                || data[3] != 'f' || data[4] != 0 || data[5] != 0) return 1;
        int base = 6; boolean little;
        if (data[base] == 'I' && data[base + 1] == 'I') little = true;
        else if (data[base] == 'M' && data[base + 1] == 'M') little = false;
        else return 1;
        if (u16(data, base + 2, little) != 42) return 1;
        long relative = u32(data, base + 4, little); long ifdLong = base + relative;
        if (relative < 8 || ifdLong < 0 || ifdLong + 2 > data.length) return 1;
        int ifd = (int)ifdLong; int count = u16(data, ifd, little);
        for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry < 0 || entry + 12 > data.length) return 1;
            if (u16(data, entry, little) == 0x0112 && u16(data, entry + 2, little) == 3
                    && u32(data, entry + 4, little) == 1) return u16(data, entry + 8, little);
        }
        return 1;
    }

    private static int u16(byte[] data, int offset, boolean little) {
        if (offset < 0 || offset + 2 > data.length) return -1;
        int a = data[offset] & 255, b = data[offset + 1] & 255;
        return little ? a | b << 8 : a << 8 | b;
    }

    private static long u32(byte[] data, int offset, boolean little) {
        if (offset < 0 || offset + 4 > data.length) return -1;
        long a = data[offset] & 255, b = data[offset + 1] & 255;
        long c = data[offset + 2] & 255, d = data[offset + 3] & 255;
        return little ? a | b << 8 | c << 16 | d << 24 : a << 24 | b << 16 | c << 8 | d;
    }

    private static void skipFully(InputStream in, int bytes) throws IOException {
        while (bytes > 0) { long skipped = in.skip(bytes); if (skipped > 0) bytes -= skipped;
            else { if (in.read() < 0) throw new EOFException(); bytes--; } }
    }

    private static Bitmap orient(Bitmap source, int orientation) {
        if (orientation <= 1 || orientation > 8) return source;
        Matrix matrix = new Matrix();
        switch (orientation) {
            case 2: matrix.setScale(-1, 1); break;
            case 3: matrix.setRotate(180); break;
            case 4: matrix.setScale(1, -1); break;
            case 5: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case 6: matrix.setRotate(90); break;
            case 7: matrix.setRotate(-90); matrix.postScale(-1, 1); break;
            case 8: matrix.setRotate(-90); break;
            default: return source;
        }
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private static Bitmap limit(Bitmap source) {
        double scale = Math.min(1.0, Math.min(MAX_EDGE / (double)Math.max(source.getWidth(),
                source.getHeight()), Math.sqrt(MAX_PIXELS / (double)((long)source.getWidth()
                * source.getHeight()))));
        if (scale >= .999) return source;
        return Bitmap.createScaledBitmap(source, Math.max(1, (int)Math.round(source.getWidth() * scale)),
                Math.max(1, (int)Math.round(source.getHeight() * scale)), true);
    }

    private static Metadata metadata(ContentResolver resolver, Uri uri) {
        String name = "图片"; long bytes = -1;
        try (Cursor cursor = resolver.query(uri, new String[] { OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) bytes = cursor.getLong(sizeColumn);
            }
        } catch (RuntimeException ignored) { }
        if (name == null || name.trim().length() == 0) name = "图片";
        name = name.replace('\u0000', '\ufffd').trim();
        if (name.length() == 0) name = "图片";
        if (name.length() > 512) name = name.substring(0, 512);
        return new Metadata(name, bytes);
    }

    private static final class Metadata {
        final String name; final long bytes;
        Metadata(String name, long bytes) { this.name = name; this.bytes = bytes; }
    }
}
