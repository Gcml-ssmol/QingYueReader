package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/** Asynchronous first-page/note previews with bounded memory and bind tokens. */
public final class LibraryThumbnailLoader implements Closeable {
    public interface Callback {
        /** A null bitmap means the caller should keep its format fallback. */
        void loaded(Token token, Bitmap bitmap);
    }

    public static final class Token {
        public final long id;
        public final String key;
        private volatile boolean cancelled;

        Token(long id, String key) { this.id = id; this.key = key; }
        public void cancel() { cancelled = true; }
        public boolean isCancelled() { return cancelled; }
    }

    private static final int CACHE_KIB = 12 * 1024;
    private static final int MAX_DIMENSION = 768;
    private static final int MAX_PIXELS = 512 * 512;
    private static final int MAX_PREVIEW_ITEMS = 5000;
    private static final int MAX_PREVIEW_POINTS = 100000;

    private static final class Job {
        final Token token;
        final Callback callback;
        Job(Token token, Callback callback) { this.token = token; this.callback = callback; }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicLong serial = new AtomicLong();
    private final Object pendingLock = new Object();
    private final java.util.HashMap<String, ArrayList<Job>> pending = new java.util.HashMap<>();
    private final java.util.HashMap<String, Long> generations = new java.util.HashMap<>();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(CACHE_KIB) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return Math.max(1, bitmap.getByteCount() / 1024);
        }
    };
    private volatile boolean closed;

    public LibraryThumbnailLoader(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Returns a token that a recycled row must cancel or compare in its callback. */
    public Token request(LibraryItem source, int requestedWidth, int requestedHeight, Callback callback) {
        if (source == null || callback == null) throw new IllegalArgumentException("缩略图请求不完整");
        int[] dimensions = boundedDimensions(requestedWidth, requestedHeight);
        LibraryItem item = source.copy();
        final long generation;
        synchronized (pendingLock) {
            Long value = generations.get(item.id);
            generation = value == null ? 0 : value;
        }
        String key = cacheKey(item, dimensions[0], dimensions[1]) + "|g" + generation;
        Token token = new Token(serial.incrementAndGet(), key);
        if (closed) { token.cancel(); return token; }
        Bitmap ready = cache.get(key);
        if (ready != null && !ready.isRecycled()) {
            main.post(() -> deliver(new Job(token, callback), ready));
            return token;
        }
        boolean start;
        synchronized (pendingLock) {
            ArrayList<Job> listeners = pending.get(key);
            start = listeners == null;
            if (listeners == null) {
                listeners = new ArrayList<>();
                pending.put(key, listeners);
            }
            listeners.add(new Job(token, callback));
        }
        if (start) {
            try {
                worker.execute(() -> render(key, item, dimensions[0], dimensions[1]));
            } catch (RejectedExecutionException stopped) {
                synchronized (pendingLock) {
                    ArrayList<Job> abandoned = pending.remove(key);
                    if (abandoned != null) for (Job job : abandoned) job.token.cancel();
                }
            }
        }
        return token;
    }

    public void invalidate(String documentId) {
        if (documentId == null) return;
        String prefix = documentId + "|";
        for (String key : cache.snapshot().keySet()) if (key.startsWith(prefix)) cache.remove(key);
        synchronized (pendingLock) {
            Long generation = generations.get(documentId);
            generations.put(documentId, generation == null ? 1 : generation + 1);
            for (Map.Entry<String, ArrayList<Job>> entry : pending.entrySet()) {
                if (entry.getKey().startsWith(prefix)) for (Job job : entry.getValue()) job.token.cancel();
            }
        }
    }

    @Override public void close() {
        closed = true;
        synchronized (pendingLock) {
            for (ArrayList<Job> jobs : pending.values()) for (Job job : jobs) job.token.cancel();
            pending.clear();
            generations.clear();
        }
        worker.shutdownNow();
        cache.evictAll();
    }

    private void render(String key, LibraryItem item, int width, int height) {
        Bitmap bitmap = null;
        if (!closed && !Thread.currentThread().isInterrupted() &&
                currentGeneration(item.id, key)) {
            try {
                bitmap = item.isNote() ? renderNote(item, width, height) : renderPdf(item, width, height);
            } catch (IOException | RuntimeException | LinkageError | OutOfMemoryError ignored) { }
        }
        if (bitmap != null && !closed && currentGeneration(item.id, key)) cache.put(key, bitmap);
        final Bitmap result = bitmap;
        final ArrayList<Job> listeners;
        synchronized (pendingLock) { listeners = pending.remove(key); }
        if (listeners != null) main.post(() -> {
            for (Job job : listeners) deliver(job, result);
        });
    }

    private void deliver(Job job, Bitmap bitmap) {
        if (!closed && !job.token.cancelled) job.callback.loaded(job.token, bitmap);
    }

    private boolean currentGeneration(String documentId, String key) {
        synchronized (pendingLock) {
            Long value = generations.get(documentId);
            long generation = value == null ? 0 : value;
            return key.endsWith("|g" + generation);
        }
    }

    private Bitmap renderPdf(LibraryItem item, int width, int height) throws IOException {
        File file = new File(context.getFilesDir(), item.id + ".pdf");
        if (!file.isFile()) throw new IOException("PDF 不存在");
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            if (renderer.getPageCount() < 1) throw new IOException("PDF 没有页面");
            try (PdfRenderer.Page page = renderer.openPage(0)) {
                float scale = Math.min(width / (float) Math.max(1, page.getWidth()),
                        height / (float) Math.max(1, page.getHeight()));
                int renderedWidth = Math.max(1, Math.min(width, Math.round(page.getWidth() * scale)));
                int renderedHeight = Math.max(1, Math.min(height, Math.round(page.getHeight() * scale)));
                Bitmap pageBitmap = Bitmap.createBitmap(renderedWidth, renderedHeight, Bitmap.Config.ARGB_8888);
                pageBitmap.eraseColor(Color.WHITE);
                page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                if (renderedWidth == width && renderedHeight == height) return pageBitmap;
                Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(result);
                canvas.drawColor(AppColors.SOFT);
                canvas.drawBitmap(pageBitmap, (width - renderedWidth) / 2f, (height - renderedHeight) / 2f, null);
                pageBitmap.recycle();
                return result;
            }
        }
    }

    private Bitmap renderNote(LibraryItem item, int width, int height) throws IOException {
        NoteDocument document = NoteStore.load(context, item.id);
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawColor(AppColors.SOFT);
        List<NoteDocument.Item> items = firstPageItems(document);
        double[] bounds = noteBounds(document, items, reflectedContentBounds(document), width / (double) height);
        double worldWidth = Math.max(1, bounds[2] - bounds[0]);
        double worldHeight = Math.max(1, bounds[3] - bounds[1]);
        float margin = Math.max(2, Math.min(width, height) * .035f);
        float scale = (float) Math.min((width - margin * 2) / worldWidth, (height - margin * 2) / worldHeight);
        if (!(scale > 0) || Float.isInfinite(scale) || Float.isNaN(scale)) scale = 1;
        float dx = (float) ((width - worldWidth * scale) / 2 - bounds[0] * scale);
        float dy = (float) ((height - worldHeight * scale) / 2 - bounds[1] * scale);

        canvas.save();
        canvas.translate(dx, dy);
        canvas.scale(scale, scale);
        canvas.clipRect((float) bounds[0], (float) bounds[1], (float) bounds[2], (float) bounds[3]);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(AppColors.SURFACE);
        canvas.drawRect((float) bounds[0], (float) bounds[1], (float) bounds[2], (float) bounds[3], paint);
        drawTemplate(canvas, document, bounds, scale, paint);
        drawItems(canvas, items, item.id, scale, paint);
        canvas.restore();
        return result;
    }

    private double[] noteBounds(NoteDocument document, List<NoteDocument.Item> items,
            double[] contentBounds, double aspect) {
        if (!document.infinite()) return new double[]{0, 0, document.width, document.height};
        if (validBounds(contentBounds)) {
            double span = Math.max(1, Math.max(contentBounds[2] - contentBounds[0], contentBounds[3] - contentBounds[1]));
            double padding = Math.max(24, span * .08);
            return new double[]{contentBounds[0] - padding, contentBounds[1] - padding,
                    contentBounds[2] + padding, contentBounds[3] + padding};
        }
        if (items.isEmpty()) {
            double worldWidth = Math.max(240, 600 / Math.max(.25, document.zoom));
            double worldHeight = worldWidth / Math.max(.25, aspect);
            return new double[]{document.centerX - worldWidth / 2, document.centerY - worldHeight / 2,
                    document.centerX + worldWidth / 2, document.centerY + worldHeight / 2};
        }
        double left = Double.POSITIVE_INFINITY, top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY, bottom = Double.NEGATIVE_INFINITY;
        int count = 0;
        for (NoteDocument.Item value : items) {
            left = Math.min(left, value.left()); top = Math.min(top, value.top());
            right = Math.max(right, value.right()); bottom = Math.max(bottom, value.bottom());
            if (++count >= MAX_PREVIEW_ITEMS) break;
        }
        double span = Math.max(1, Math.max(right - left, bottom - top));
        double padding = Math.max(24, span * .08);
        return new double[]{left - padding, top - padding, right + padding, bottom + padding};
    }

    private void drawTemplate(Canvas canvas, NoteDocument document, double[] bounds, float scale, Paint paint) {
        if ("blank".equals(document.template)) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(.35f, .8f / scale));
        paint.setColor(0x35d8a87e);
        double spacing = "grid".equals(document.template) ? 24 : 28;
        long firstY = (long) Math.floor(bounds[1] / spacing);
        long lastY = Math.min(firstY + 300, (long) Math.ceil(bounds[3] / spacing));
        for (long line = firstY; line <= lastY; line++) {
            float y = (float) (line * spacing);
            canvas.drawLine((float) bounds[0], y, (float) bounds[2], y, paint);
        }
        if ("grid".equals(document.template)) {
            long firstX = (long) Math.floor(bounds[0] / spacing);
            long lastX = Math.min(firstX + 300, (long) Math.ceil(bounds[2] / spacing));
            for (long line = firstX; line <= lastX; line++) {
                float x = (float) (line * spacing);
                canvas.drawLine(x, (float) bounds[1], x, (float) bounds[3], paint);
            }
        }
    }

    private void drawItems(Canvas canvas, List<NoteDocument.Item> items, String id, float scale, Paint paint) {
        int itemCount = 0, pointBudget = MAX_PREVIEW_POINTS;
        Path path = new Path();
        RectF rectangle = new RectF();
        File images = new File(new File(new File(context.getFilesDir(), "notes"), id), "images");
        for (NoteDocument.Item item : items) {
            if (++itemCount > MAX_PREVIEW_ITEMS || pointBudget <= 0) break;
            paint.setColor(item.color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(Math.max(item.width, .7f / scale));
            double[] points = item.points;
            switch (item.type) {
                case NoteDocument.IMAGE:
                    Bitmap image = decodeSampled(new File(images, item.image),
                            Math.max(1, Math.round((float) Math.abs(points[2] - points[0]) * scale)),
                            Math.max(1, Math.round((float) Math.abs(points[3] - points[1]) * scale)));
                    if (image != null) {
                        rectangle.set((float) points[0], (float) points[1], (float) points[2], (float) points[3]);
                        paint.setStyle(Paint.Style.FILL);
                        paint.setAlpha(255);
                        canvas.drawBitmap(image, null, rectangle, paint);
                        image.recycle();
                    }
                    pointBudget -= 2;
                    break;
                case NoteDocument.RECTANGLE:
                    rectangle.set((float) Math.min(points[0], points[2]), (float) Math.min(points[1], points[3]),
                            (float) Math.max(points[0], points[2]), (float) Math.max(points[1], points[3]));
                    canvas.drawRect(rectangle, paint);
                    pointBudget -= 2;
                    break;
                case NoteDocument.CIRCLE:
                    rectangle.set((float) Math.min(points[0], points[2]), (float) Math.min(points[1], points[3]),
                            (float) Math.max(points[0], points[2]), (float) Math.max(points[1], points[3]));
                    canvas.drawOval(rectangle, paint);
                    pointBudget -= 2;
                    break;
                case NoteDocument.LINE:
                    canvas.drawLine((float) points[0], (float) points[1], (float) points[2], (float) points[3], paint);
                    pointBudget -= 2;
                    break;
                case NoteDocument.ARROW:
                    canvas.drawLine((float) points[0], (float) points[1], (float) points[2], (float) points[3], paint);
                    double[] arrow = NoteDocument.arrowHeads(points, item.width);
                    canvas.drawLine((float) points[2], (float) points[3], (float) arrow[0], (float) arrow[1], paint);
                    canvas.drawLine((float) points[2], (float) points[3], (float) arrow[2], (float) arrow[3], paint);
                    pointBudget -= 4;
                    break;
                case NoteDocument.STROKE:
                default:
                    int vertices = points.length / 2;
                    int allowed = Math.min(vertices, pointBudget);
                    if (allowed <= 0) break;
                    int step = Math.max(1, vertices / Math.max(2, allowed));
                    path.reset();
                    path.moveTo((float) points[0], (float) points[1]);
                    int drawn = 1;
                    for (int vertex = step; vertex < vertices; vertex += step) {
                        path.lineTo((float) points[vertex * 2], (float) points[vertex * 2 + 1]);
                        drawn++;
                    }
                    if ((vertices - 1) % step != 0) {
                        path.lineTo((float) points[points.length - 2], (float) points[points.length - 1]);
                        drawn++;
                    }
                    if (vertices == 1) canvas.drawPoint((float) points[0], (float) points[1], paint);
                    else canvas.drawPath(path, paint);
                    pointBudget -= drawn;
                    break;
            }
        }
    }

    /** Multi-page notes expose pageItems(0); reflection keeps this source compatible with 1.7. */
    @SuppressWarnings("unchecked")
    private static List<NoteDocument.Item> firstPageItems(NoteDocument document) {
        try {
            Method method = document.getClass().getMethod("pageItems", int.class);
            Object result = method.invoke(document, 0);
            if (result instanceof List<?>) return (List<NoteDocument.Item>) result;
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        List<NoteDocument.Item> legacy = document.items();
        return legacy == null ? Collections.emptyList() : legacy;
    }

    /** Accepts the planned double[4], float[4], or RectF contentBounds(0) API. */
    private static double[] reflectedContentBounds(NoteDocument document) {
        try {
            Method method = document.getClass().getMethod("contentBounds", int.class);
            Object result = method.invoke(document, 0);
            if (result instanceof double[]) {
                double[] values = (double[]) result;
                if (values.length >= 4) return new double[]{values[0], values[1], values[2], values[3]};
            } else if (result instanceof float[]) {
                float[] values = (float[]) result;
                if (values.length >= 4) return new double[]{values[0], values[1], values[2], values[3]};
            } else if (result instanceof RectF) {
                RectF values = (RectF) result;
                return new double[]{values.left, values.top, values.right, values.bottom};
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return null;
    }

    private static boolean validBounds(double[] values) {
        if (values == null || values.length < 4 || !(values[2] > values[0]) || !(values[3] > values[1])) return false;
        for (int index = 0; index < 4; index++) if (Double.isNaN(values[index]) || Double.isInfinite(values[index])) return false;
        return true;
    }

    private static Bitmap decodeSampled(File file, int wantedWidth, int wantedHeight) {
        if (!file.isFile()) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        int targetWidth = Math.max(1, Math.min(MAX_DIMENSION, wantedWidth));
        int targetHeight = Math.max(1, Math.min(MAX_DIMENSION, wantedHeight));
        while (options.outWidth / options.inSampleSize > targetWidth * 2
                || options.outHeight / options.inSampleSize > targetHeight * 2) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private String cacheKey(LibraryItem item, int width, int height) {
        File source = item.isNote()
                ? new File(new File(new File(context.getFilesDir(), "notes"), item.id), "document.qnote")
                : new File(context.getFilesDir(), item.id + ".pdf");
        long revision = Math.max(item.modified, source.lastModified());
        return item.id + "|" + item.type + "|" + revision + "|" + width + "x" + height;
    }

    private static int[] boundedDimensions(int requestedWidth, int requestedHeight) {
        int width = Math.max(24, Math.min(MAX_DIMENSION, requestedWidth));
        int height = Math.max(24, Math.min(MAX_DIMENSION, requestedHeight));
        long pixels = (long) width * height;
        if (pixels > MAX_PIXELS) {
            double scale = Math.sqrt(MAX_PIXELS / (double) pixels);
            width = Math.max(24, (int) Math.floor(width * scale));
            height = Math.max(24, (int) Math.floor(height * scale));
        }
        return new int[]{width, height};
    }
}
