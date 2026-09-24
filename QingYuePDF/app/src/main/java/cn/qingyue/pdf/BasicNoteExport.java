package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** Renders one frozen note document directly to a user-selected destination. */
final class BasicNoteExport {
    private BasicNoteExport() { }

    static void write(Context context, String noteId, NoteDocument note, Uri destination, boolean pdf) throws IOException {
        BasicNoteExportBounds area = BasicNoteExportBounds.forDocument(note);
        if (pdf) {
            PdfDocument document = new PdfDocument();
            try {
                for(int index=0;index<note.pageCount();index++){List<NoteDocument.Item> items=note.pageItems(index);BasicNoteExportBounds pageArea=BasicNoteExportBounds.forItems(note,items);int width=(int)Math.ceil(pageArea.width),height=(int)Math.ceil(pageArea.height);PdfDocument.Page page=document.startPage(new PdfDocument.PageInfo.Builder(width,height,index+1).create());try{render(context,noteId,note,items,pageArea,page.getCanvas(),width/pageArea.width,height/pageArea.height);}finally{document.finishPage(page);}}
                try (OutputStream output = openDestination(context, destination)) { document.writeTo(output); }
            } finally { document.close(); }
        } else {
            Bitmap bitmap = Bitmap.createBitmap(area.pixelsWide, area.pixelsHigh, Bitmap.Config.ARGB_8888);
            try {
                render(context, noteId, note, note.items(), area, new Canvas(bitmap), area.pixelsWide / area.width, area.pixelsHigh / area.height);
                try (OutputStream output = openDestination(context, destination)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("PNG 编码失败。");
                }
            } finally { bitmap.recycle(); }
        }
    }

    private static OutputStream openDestination(Context context, Uri destination) throws IOException {
        OutputStream output = context.getContentResolver().openOutputStream(destination, "w");
        if (output == null) throw new IOException("无法打开导出位置。");
        return output;
    }

    private static void render(Context context, String noteId, NoteDocument note, List<NoteDocument.Item> items, BasicNoteExportBounds area, Canvas canvas, double sx, double sy) throws IOException {
        canvas.drawColor(AppColors.EXPORT_PAPER);
        canvas.save();
        canvas.scale((float)sx, (float)sy);
        canvas.translate((float)-area.left, (float)-area.top);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (!"blank".equals(note.template)) {
            double spacing = "grid".equals(note.template) ? 20 : 28;
            paint.setColor("grid".equals(note.template) ? 0xffe8ded1 : 0xffe2d5c5);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(.7f);
            double right = area.left + area.width, bottom = area.top + area.height;
            for (double y = Math.ceil(area.top / spacing) * spacing; y <= bottom; y += spacing)
                canvas.drawLine((float)area.left, (float)y, (float)right, (float)y, paint);
            if ("grid".equals(note.template))
                for (double x = Math.ceil(area.left / spacing) * spacing; x <= right; x += spacing)
                    canvas.drawLine((float)x, (float)area.top, (float)x, (float)bottom, paint);
        }
        for (NoteDocument.Item item : items) drawItem(context, noteId, canvas, paint, item);
        canvas.restore();
    }

    private static void drawItem(Context context, String noteId, Canvas canvas, Paint paint, NoteDocument.Item item) throws IOException {
        double[] p = item.points;
        paint.setColor(item.color); paint.setStrokeWidth(item.width);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        if (item.type == NoteDocument.TEXT) {
            NoteTextLayout.draw(canvas, item);
        } else if (item.type == NoteDocument.IMAGE) {
            File file = new File(NoteStore.imagesDirectory(context, noteId), item.image);
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("笔记图片无法读取：" + item.image);
            int sample = 1;
            while (options.outWidth / sample > 4096 || options.outHeight / sample > 4096) sample *= 2;
            options.inJustDecodeBounds = false; options.inSampleSize = sample;
            Bitmap image = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (image == null) throw new IOException("笔记图片解码失败：" + item.image);
            try {
                paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.FILL);
                canvas.drawBitmap(image, null, new RectF((float)item.left(), (float)item.top(), (float)item.right(), (float)item.bottom()), paint);
            } finally { image.recycle(); }
        } else if (item.type == NoteDocument.STROKE) {
            if (p.length == 2) { paint.setStyle(Paint.Style.FILL); canvas.drawCircle((float)p[0], (float)p[1], item.width / 2, paint); }
            else { Path path = new Path(); path.moveTo((float)p[0], (float)p[1]); for (int i = 2; i < p.length; i += 2) path.lineTo((float)p[i], (float)p[i+1]); canvas.drawPath(path, paint); }
        } else if (item.type == NoteDocument.RECTANGLE) {
            canvas.drawRect((float)item.left(), (float)item.top(), (float)item.right(), (float)item.bottom(), paint);
        } else if (item.type == NoteDocument.CIRCLE) {
            canvas.drawOval((float)item.left(), (float)item.top(), (float)item.right(), (float)item.bottom(), paint);
        } else {
            canvas.drawLine((float)p[0], (float)p[1], (float)p[2], (float)p[3], paint);
            if (item.type == NoteDocument.ARROW) {
                double[] a = NoteDocument.arrowHeads(p, item.width);
                canvas.drawLine((float)p[2], (float)p[3], (float)a[0], (float)a[1], paint);
                canvas.drawLine((float)p[2], (float)p[3], (float)a[2], (float)a[3], paint);
            }
        }
    }
}
