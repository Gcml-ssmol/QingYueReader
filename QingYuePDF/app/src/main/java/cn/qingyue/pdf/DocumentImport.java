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

/** Convert local Office, EPUB and plain text into a readable, annotatable PDF. */
public final class DocumentImport {
    private DocumentImport() {}
    public enum PagePreset { PHONE, A4 }
    public enum FontSize { SMALL, MEDIUM, LARGE }
    public static final class LayoutOptions {
        public final PagePreset page;
        public final FontSize font;
        private final DocumentPagePlan plan;
        public LayoutOptions(PagePreset page,FontSize font){
            if(page==null||font==null)throw new IllegalArgumentException("layout options");
            this.page=page;this.font=font;this.plan=DocumentPagePlan.create(page==PagePreset.PHONE,font.ordinal());
        }
        public static LayoutOptions defaultFor(Context context){
            int smallest=context.getResources().getConfiguration().smallestScreenWidthDp;
            return new LayoutOptions(smallest>=600?PagePreset.A4:PagePreset.PHONE,FontSize.MEDIUM);
        }
        public int pageWidth(){return plan.width;}
        public int pageHeight(){return plan.height;}
        public int left(){return plan.left;}
        public int top(){return plan.top;}
        public int bottom(){return plan.bottom;}
        public float textSize(){return plan.textSize;}
        public float lineExtra(){return plan.lineExtra;}
    }
    private static final LayoutOptions LEGACY_A4=new LayoutOptions(PagePreset.A4,FontSize.SMALL);
    public interface Callback {
        boolean isCancelled();
        /** Byte progress reported by the source extractor; total may be -1. */
        void extracting(long completed,long total);
        /** Completed output pages; total is -1 until conversion finishes. */
        void page(int completed,int total);
    }
    /** Returns DOC, DOCX, EPUB or TXT. Original layout and embedded media are not reproduced. */
    public static String convert(Context context,File input,String displayName,String mime,File outputPdf) throws IOException {
        return convert(context,input,displayName,mime,outputPdf,null,LEGACY_A4);
    }
    /** Cancellable/progress-reporting overload used by lifecycle-independent imports. */
    public static String convert(Context context,File input,String displayName,String mime,File outputPdf,Callback callback) throws IOException {
        return convert(context,input,displayName,mime,outputPdf,callback,LEGACY_A4);
    }
    /** New-import layout; existing PDFs are never reflowed by this API. */
    public static String convert(Context context,File input,String displayName,String mime,File outputPdf,
            Callback callback,LayoutOptions options) throws IOException {
        if(options==null)throw new IllegalArgumentException("options");
        PdfDocument pdf=null;PdfDocument.Page page=null;boolean success=false;
        try {
            active(callback);
            DocumentText.Result extracted=DocumentText.extract(input,displayName,mime,new DocumentText.Monitor(){
                @Override public boolean isCancelled(){return cancelled(callback);}
                @Override public void progress(long completed,long total){if(callback!=null)try{callback.extracting(completed,total);}catch(RuntimeException ignored){}}
            });
            active(callback);
            pdf=new PdfDocument();
            TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.rgb(38,33,28));paint.setTextSize(options.textSize());
            int offset=0,pageNumber=0;float y=options.top();
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
                StaticLayout layout=StaticLayout.Builder.obtain(chunk,0,chunk.length(),paint,options.pageWidth()-2*options.left())
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(options.lineExtra(),1f)
                        .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build();
                int line=0;
                while(line<layout.getLineCount()) {
                    active(callback);
                    if(page==null) {
                        DocumentPagePlan.requirePage(++pageNumber);
                        page=pdf.startPage(new PdfDocument.PageInfo.Builder(options.pageWidth(),options.pageHeight(),pageNumber).create());
                        decorate(page.getCanvas(),displayName,pageNumber,options);y=options.top();
                    }
                    int first=line,top=layout.getLineTop(first);
                    while(line<layout.getLineCount() && y+layout.getLineBottom(line)-top<=options.bottom())line++;
                    if(line==first) {pdf.finishPage(page);page=null;reportPage(callback,pageNumber,-1);continue;}
                    float height=layout.getLineBottom(line-1)-top;
                    Canvas canvas=page.getCanvas();canvas.save();canvas.clipRect(options.left(),y,options.pageWidth()-options.left(),y+height);
                    canvas.translate(options.left(),y-top);layout.draw(canvas);canvas.restore();y+=height;
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
    private static void decorate(Canvas canvas,String name,int page,LayoutOptions options) {
        canvas.drawColor(Color.WHITE);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(0xff817467);paint.setTextSize(options.page==PagePreset.PHONE?8f:9f);
        String title=name==null?"导入文档":name.replace('\n',' ').replace('\r',' ');
        if(title.length()>160)title=title.substring(0,160);
        int count=paint.breakText(title,true,options.pageWidth()-2*options.left(),null);
        if(count<title.length())title=title.substring(0,Math.max(0,count-1))+"…";
        int heading=options.page==PagePreset.PHONE?27:34,rule=options.page==PagePreset.PHONE?34:43;
        canvas.drawText(title,options.left(),heading,paint);paint.setColor(0xffeee8e1);paint.setStrokeWidth(.5f);canvas.drawLine(options.left(),rule,options.pageWidth()-options.left(),rule,paint);
        paint.setColor(0xff817467);paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(String.valueOf(page),options.pageWidth()/2f,options.pageHeight()-(options.page==PagePreset.PHONE?18:24),paint);
    }
}
