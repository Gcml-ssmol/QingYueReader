package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Convert local Office, EPUB and plain text into a readable, annotatable A4 PDF. */
public final class DocumentImport {
    private static final int PAGE_WIDTH=595, PAGE_HEIGHT=842, LEFT=44, TOP=62, BOTTOM=794, MAX_PAGES=400;
    private DocumentImport() {}
    public interface Callback {
        boolean isCancelled();
        /** Byte progress reported by the source extractor; total may be -1. */
        void extracting(long completed,long total);
        /** Completed output pages; total is -1 until conversion finishes. */
        void page(int completed,int total);
    }
    /** Returns DOC, DOCX, EPUB or TXT. Original layout and embedded media are not reproduced. */
    public static String convert(Context context,File input,String displayName,String mime,File outputPdf) throws IOException {
        return convert(context,input,displayName,mime,outputPdf,null);
    }
    /** Cancellable/progress-reporting overload used by lifecycle-independent imports. */
    public static String convert(Context context,File input,String displayName,String mime,File outputPdf,Callback callback) throws IOException {
        PdfDocument pdf=null;PdfDocument.Page page=null;boolean success=false;
        try {
            active(callback);
            DocumentText.Result extracted=DocumentText.extract(input,displayName,mime,new DocumentText.Monitor(){
                @Override public boolean isCancelled(){return cancelled(callback);}
                @Override public void progress(long completed,long total){if(callback!=null)try{callback.extracting(completed,total);}catch(RuntimeException ignored){}}
            });
            active(callback);
            pdf=new PdfDocument();
            TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.rgb(38,33,28));paint.setTextSize(12.5f);
            int offset=0,pageNumber=0;float y=TOP;
            while(offset<extracted.text.length()) {
                active(callback);
                // Short layouts keep long documents bounded and preserve text across page breaks.
                int end=Math.min(offset+8192,extracted.text.length());
                if(end<extracted.text.length()) {
                    int paragraph=extracted.text.lastIndexOf('\n',end-1);
                    if(paragraph>offset+4096)end=paragraph+1;
                    else if(Character.isHighSurrogate(extracted.text.charAt(end-1)))end--;
                }
                String chunk=extracted.text.substring(offset,end);
                StaticLayout layout=StaticLayout.Builder.obtain(chunk,0,chunk.length(),paint,PAGE_WIDTH-2*LEFT)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(3f,1f)
                        .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build();
                int line=0;
                while(line<layout.getLineCount()) {
                    active(callback);
                    if(page==null) {
                        if(++pageNumber>MAX_PAGES)throw new IOException("重排后超过 400 页，请拆分文档或先导出为 PDF");
                        page=pdf.startPage(new PdfDocument.PageInfo.Builder(PAGE_WIDTH,PAGE_HEIGHT,pageNumber).create());
                        decorate(page.getCanvas(),displayName,pageNumber);y=TOP;
                    }
                    int first=line,top=layout.getLineTop(first);
                    while(line<layout.getLineCount() && y+layout.getLineBottom(line)-top<=BOTTOM)line++;
                    if(line==first) {pdf.finishPage(page);page=null;reportPage(callback,pageNumber,-1);continue;}
                    float height=layout.getLineBottom(line-1)-top;
                    Canvas canvas=page.getCanvas();canvas.save();canvas.clipRect(LEFT,y,PAGE_WIDTH-LEFT,y+height);
                    canvas.translate(LEFT,y-top);layout.draw(canvas);canvas.restore();y+=height;
                    if(line<layout.getLineCount()) {pdf.finishPage(page);page=null;reportPage(callback,pageNumber,-1);}
                }
                offset=end;
            }
            if(page!=null){pdf.finishPage(page);page=null;reportPage(callback,pageNumber,-1);}
            active(callback);reportPage(callback,pageNumber,pageNumber);
            try(FileOutputStream out=new FileOutputStream(outputPdf)){pdf.writeTo(out);out.getFD().sync();}
            active(callback);
            success=true;return extracted.format;
        } catch(OutOfMemoryError memory) {throw new IOException("文档内容较复杂，当前设备内存不足。请拆分文档或先导出为 PDF",memory);}
        catch(LinkageError unavailable) {throw new IOException("此设备无法处理这个 Word 文档，请另存为 DOCX / PDF 后导入",unavailable);}
        finally {
            if(pdf!=null){if(page!=null){try{pdf.finishPage(page);}catch(RuntimeException ignored){}}pdf.close();}
            if(!success&&outputPdf!=null)outputPdf.delete();
        }
    }
    private static boolean cancelled(Callback callback){if(Thread.currentThread().isInterrupted())return true;if(callback==null)return false;try{return callback.isCancelled();}catch(RuntimeException failure){return true;}}
    private static void active(Callback callback)throws IOException{if(cancelled(callback))throw new java.io.InterruptedIOException("已取消导入");}
    private static void reportPage(Callback callback,int completed,int total){if(callback!=null)try{callback.page(completed,total);}catch(RuntimeException ignored){}}
    private static void decorate(Canvas canvas,String name,int page) {
        canvas.drawColor(Color.WHITE);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(0xff817467);paint.setTextSize(9f);
        String title=name==null?"导入文档":name.replace('\n',' ').replace('\r',' ');
        if(title.length()>160)title=title.substring(0,160);
        int count=paint.breakText(title,true,PAGE_WIDTH-2*LEFT,null);
        if(count<title.length())title=title.substring(0,Math.max(0,count-1))+"…";
        canvas.drawText(title,LEFT,34,paint);paint.setColor(0xffeee8e1);paint.setStrokeWidth(.5f);canvas.drawLine(LEFT,43,PAGE_WIDTH-LEFT,43,paint);
        paint.setColor(0xff817467);paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(String.valueOf(page),PAGE_WIDTH/2f,PAGE_HEIGHT-24,paint);
    }
}
