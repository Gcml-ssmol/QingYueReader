package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import java.io.*;
import java.util.*;

/** Vector annotation and current-page PNG export. The source PDF is never modified. */
public final class PdfAnnotationExporter {
    public interface Cancellation {boolean cancelled();}
    public interface Progress {void page(int completed,int total);}
    public static final class ExportCancelled extends InterruptedIOException {public ExportCancelled(){super("Export cancelled");}}
    public static final class ExportOutOfMemory extends IOException {ExportOutOfMemory(OutOfMemoryError cause){super("Not enough memory to export image",cause);}}
    public static final class PageRange {
        public final int fromInclusive,toExclusive;
        public PageRange(int fromInclusive,int toExclusive){if(fromInclusive<0||toExclusive<=fromInclusive)throw new IllegalArgumentException("Invalid page range");this.fromInclusive=fromInclusive;this.toExclusive=toExclusive;}
        public static PageRange all(int pages){return new PageRange(0,pages);}
    }
    public static final class Report {
        public final int pages,inkStrokes,textHighlights;public final boolean sourceHadSignatures;
        Report(int pages,int inkStrokes,int textHighlights,boolean signed){this.pages=pages;this.inkStrokes=inkStrokes;this.textHighlights=textHighlights;this.sourceHadSignatures=signed;}
    }
    private static final long MEMORY_BYTES=24L*1024*1024,MAX_PNG_PIXELS=6000000L;
    private static final int MAX_PNG_SIDE=4096,PNG_HEAP_BYTES_PER_PIXEL=32;private static final float HIGHLIGHT_ALPHA=85f/255f;
    private PdfAnnotationExporter(){}

    /** Lightweight preflight so the UI can obtain confirmation before invalidating signatures. */
    public static boolean sourceHasSignatures(Context context,File source)throws IOException {
        PDFBoxResourceLoader.init(context.getApplicationContext());
        return sourceHasSignatures(source);
    }

    public static boolean sourceHasSignatures(File source)throws IOException {
        if(source==null||!source.isFile())throw new FileNotFoundException("PDF export input");
        try(PDDocument document=PDDocument.load(source,MemoryUsageSetting.setupMixed(MEMORY_BYTES))){return !document.getSignatureDictionaries().isEmpty();}
    }

    /** Adds vector overlays to original PDF pages, retaining original text, links and images. */
    public static Report exportPdf(Context context,File source,File inkDirectory,String documentId,
            ReaderDocument metadata,PageRange range,OutputStream destination,Cancellation cancellation,Progress progress)throws IOException {
        if(context==null||source==null||!source.isFile()||destination==null)throw new FileNotFoundException("PDF export input");
        check(cancellation);if(metadata==null)metadata=new ReaderDocument();PDFBoxResourceLoader.init(context.getApplicationContext());
        File scratch=new File(context.getCacheDir(),"pdf-export");if(!scratch.isDirectory()&&!scratch.mkdirs())throw new IOException("Cannot create export scratch directory");
        List<ReaderDocument.Markup> markups=metadata.markups();HashMap<Integer,List<ReaderDocument.Markup>> markupsByPage=new HashMap<>();for(ReaderDocument.Markup markup:markups)if(markup.page>=range.fromInclusive&&markup.page<range.toExclusive)markupsByPage.computeIfAbsent(markup.page,key->new ArrayList<>()).add(markup);int strokeCount=0,markupCount=0;HashMap<Integer,PDExtendedGraphicsState> alphaStates=new HashMap<>();
        File fontFile=null;try{if(!metadata.textBoxes().isEmpty()){fontFile=File.createTempFile("reader-text-",".ttf",scratch);try(InputStream input=context.getAssets().open("ocr/ocr-font.ttf");OutputStream output=new FileOutputStream(fontFile)){byte[] bytes=new byte[65536];int length;while((length=input.read(bytes))!=-1){check(cancellation);output.write(bytes,0,length);}}}
        try(PDDocument document=PDDocument.load(source,MemoryUsageSetting.setupMixed(MEMORY_BYTES).setTempDir(scratch))){
            PDType0Font textFont=fontFile==null?null:PDType0Font.load(document,fontFile);
            int total=document.getNumberOfPages();if(range.toExclusive>total)throw new IOException("Page range exceeds document");
            if(!document.getCurrentAccessPermission().canModify())throw new IOException("PDF permissions do not allow annotated export");
            boolean signed=!document.getSignatureDictionaries().isEmpty();int complete=0;
            for(int pageIndex=range.fromInclusive;pageIndex<range.toExclusive;pageIndex++){
                check(cancellation);PDPage page=document.getPage(pageIndex);List<InkPage.Stroke> strokes=InkStore.load(inkDirectory,documentId+"-"+pageIndex);List<ReaderDocument.Markup> pageMarkups=markupsByPage.get(pageIndex);if(pageMarkups==null)pageMarkups=Collections.emptyList();
                List<ReaderDocument.TextBox> pageText=metadata.textBoxes(pageIndex);
                if(!strokes.isEmpty()||!pageMarkups.isEmpty()||!pageText.isEmpty()){boolean transparent=!pageMarkups.isEmpty();if(!transparent)for(InkPage.Stroke stroke:strokes)if((stroke.color>>>24&255)<255){transparent=true;break;}if(!transparent)for(ReaderDocument.TextBox box:pageText)if((box.color>>>24&255)<255){transparent=true;break;}if(transparent&&document.getVersion()<1.4f)document.setVersion(1.4f);try(PDPageContentStream stream=new PDPageContentStream(document,page,PDPageContentStream.AppendMode.APPEND,true,true)){
                    stream.saveGraphicsState();for(ReaderDocument.Markup markup:pageMarkups){check(cancellation);appendMarkup(stream,page,markup,cancellation,alphaStates);markupCount++;}
                    for(ReaderDocument.TextBox box:pageText){check(cancellation);appendText(stream,page,box,textFont,cancellation);}
                    for(InkPage.Stroke stroke:strokes){check(cancellation);appendStroke(stream,page,stroke,cancellation,alphaStates);strokeCount++;}stream.restoreGraphicsState();
                }
                }
                complete++;if(progress!=null)progress.page(complete,range.toExclusive-range.fromInclusive);
            }
            if(range.fromInclusive!=0||range.toExclusive!=total){for(int page=total-1;page>=0;page--)if(page<range.fromInclusive||page>=range.toExclusive)document.removePage(page);document.getDocumentCatalog().setDocumentOutline(null);}
            check(cancellation);document.save(destination);destination.flush();return new Report(range.toExclusive-range.fromInclusive,strokeCount,markupCount,signed);
        }}finally{if(fontFile!=null)fontFile.delete();}
    }

    /** Renders only one page to PNG; this is intentionally not used for PDF export. */
    public static Report exportPagePng(File source,File inkDirectory,String documentId,ReaderDocument metadata,
            int page,int requestedWidth,OutputStream destination,Cancellation cancellation)throws IOException {
        if(source==null||!source.isFile()||destination==null)throw new FileNotFoundException("PNG export input");Bitmap bitmap=null;
        try{check(cancellation);if(metadata==null)metadata=new ReaderDocument();List<InkPage.Stroke> strokes=InkStore.load(inkDirectory,documentId+"-"+page);check(cancellation);
        try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(descriptor)){
            if(page<0||page>=renderer.getPageCount())throw new IOException("Page does not exist");check(cancellation);
            try(PdfRenderer.Page pdfPage=renderer.openPage(page)){
                int[] size=pngDimensions(pdfPage.getWidth(),pdfPage.getHeight(),requestedWidth,Runtime.getRuntime().maxMemory());
                bitmap=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);pdfPage.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
            }
            check(cancellation);Canvas canvas=new Canvas(bitmap);canvas.save();canvas.scale(bitmap.getWidth(),bitmap.getWidth());Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);pen.setStrokeCap(Paint.Cap.ROUND);pen.setStrokeJoin(Paint.Join.ROUND);
            for(ReaderDocument.Markup markup:metadata.markups(page)){check(cancellation);paint.setStyle(Paint.Style.FILL);paint.setColor((markup.color&0xffffff)|0x55000000);int boxIndex=0;for(ReaderDocument.Box box:markup.boxes){if((boxIndex++&255)==0)check(cancellation);canvas.drawRect(box.left,box.top,box.right,box.bottom,paint);}}
            for(ReaderDocument.TextBox box:metadata.textBoxes(page)){check(cancellation);PdfTextBoxes.draw(canvas,paint,box);}for(InkPage.Stroke stroke:strokes){check(cancellation);drawStroke(canvas,pen,stroke,cancellation);}canvas.restore();check(cancellation);
            if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,destination))throw new IOException("PNG encoder failed");check(cancellation);destination.flush();return new Report(1,strokes.size(),metadata.markups(page).size(),false);
        }
        }catch(OutOfMemoryError problem){throw new ExportOutOfMemory(problem);}
        finally{if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
    }

    static int[] pngDimensions(int pageWidth,int pageHeight,int requestedWidth,long maxMemory){
        int targetWidth=Math.max(600,Math.min(MAX_PNG_SIDE,requestedWidth));int[] size=PageMath.dimensions(pageWidth,pageHeight,targetWidth);
        long memoryPixels=Math.max(1,maxMemory/PNG_HEAP_BYTES_PER_PIXEL),pixelLimit=Math.min(MAX_PNG_PIXELS,memoryPixels);double pixels=size[0]*(double)size[1];
        double shrink=Math.min(1d,Math.min(MAX_PNG_SIDE/(double)Math.max(size[0],size[1]),Math.sqrt(pixelLimit/pixels)));size[0]=Math.max(1,(int)(size[0]*shrink));size[1]=Math.max(1,(int)(size[1]*shrink));return size;
    }

    private static void appendStroke(PDPageContentStream stream,PDPage page,InkPage.Stroke stroke,Cancellation cancellation,Map<Integer,PDExtendedGraphicsState> alphaStates)throws IOException {
        int alphaByte=stroke.color>>>24&255;graphics(stream,alphaByte,alphaStates);stream.setStrokingColor(((stroke.color>>16)&255)/255f,((stroke.color>>8)&255)/255f,(stroke.color&255)/255f);stream.setLineCapStyle(1);stream.setLineJoinStyle(1);
        if(stroke.pressures!=null){
            stream.setNonStrokingColor(((stroke.color>>16)&255)/255f,((stroke.color>>8)&255)/255f,(stroke.color&255)/255f);
            InkPage.outline(stroke,new InkPage.OutlineSink<IOException>(){
                public void move(float x,float y)throws IOException{check(cancellation);float[] p=toPdf(page,x,y);stream.moveTo(p[0],p[1]);}
                public void line(float x,float y)throws IOException{float[] p=toPdf(page,x,y);stream.lineTo(p[0],p[1]);}
                public void curve(float a,float b,float c,float d,float e,float f)throws IOException{float[] p=toPdf(page,a,b),q=toPdf(page,c,d),r=toPdf(page,e,f);stream.curveTo(p[0],p[1],q[0],q[1],r[0],r[1]);}
                public void close()throws IOException{stream.closePath();}
            });
            stream.fill();return;
        }
        float scale=displayWidth(page),baseWidth=Math.max(.1f,stroke.width*scale);float[] points=stroke.points;
        if(points.length==2){float[] point=toPdf(page,points[0],points[1]);stream.setLineWidth(baseWidth*(stroke.pressures==null?1:stroke.pressures[0]));stream.moveTo(point[0],point[1]);stream.lineTo(point[0]+.01f,point[1]+.01f);stream.stroke();return;}
        if(stroke.pressures==null){stream.setLineWidth(baseWidth);float[] start=toPdf(page,points[0],points[1]);stream.moveTo(start[0],start[1]);for(int i=2;i<points.length;i+=2){if((i&511)==0)check(cancellation);float[] point=toPdf(page,points[i],points[i+1]);stream.lineTo(point[0],point[1]);}stream.stroke();}
        else for(int i=2;i<points.length;i+=2){if((i&511)==0)check(cancellation);float pressure=(stroke.pressures[i/2-1]+stroke.pressures[i/2])/2;stream.setLineWidth(baseWidth*Math.max(.15f,Math.min(1.5f,pressure)));float[] start=toPdf(page,points[i-2],points[i-1]),end=toPdf(page,points[i],points[i+1]);stream.moveTo(start[0],start[1]);stream.lineTo(end[0],end[1]);stream.stroke();}
    }
    private static void appendMarkup(PDPageContentStream stream,PDPage page,ReaderDocument.Markup markup,Cancellation cancellation,Map<Integer,PDExtendedGraphicsState> alphaStates)throws IOException {
        graphics(stream,Math.round(HIGHLIGHT_ALPHA*255),alphaStates);stream.setNonStrokingColor(((markup.color>>16)&255)/255f,((markup.color>>8)&255)/255f,(markup.color&255)/255f);
        int index=0;for(ReaderDocument.Box box:markup.boxes){if((index++&255)==0)check(cancellation);float[] a=toPdf(page,box.left,box.top),b=toPdf(page,box.right,box.top),c=toPdf(page,box.right,box.bottom),d=toPdf(page,box.left,box.bottom);stream.moveTo(a[0],a[1]);stream.lineTo(b[0],b[1]);stream.lineTo(c[0],c[1]);stream.lineTo(d[0],d[1]);stream.closePath();stream.fill();}
    }
    private static void appendText(PDPageContentStream stream,PDPage page,ReaderDocument.TextBox box,PDType0Font font,Cancellation cancellation)throws IOException {
        if(font==null)throw new IOException("Missing export font");PDExtendedGraphicsState opaque=new PDExtendedGraphicsState();opaque.setNonStrokingAlphaConstant((box.color>>>24&255)/255f);stream.setGraphicsStateParameters(opaque);stream.setNonStrokingColor((box.color>>16)&255,(box.color>>8)&255,box.color&255);float scale=displayWidth(page)*box.size;int rotation=((page.getRotation()%360)+360)%360;int line=0;
        for(String value:PdfTextBoxes.lines(box.text)){check(cancellation);if(!value.isEmpty()){try{font.encode(value);}catch(IOException|IllegalArgumentException glyph){throw new IOException("导出字体缺少文字字符",glyph);}float[] origin=toPdf(page,box.x,box.y+box.size+line*PdfTextBoxes.lineHeight(box));stream.beginText();stream.setFont(font,1);if(rotation==90)stream.setTextMatrix(0,scale,-scale,0,origin[0],origin[1]);else if(rotation==180)stream.setTextMatrix(-scale,0,0,-scale,origin[0],origin[1]);else if(rotation==270)stream.setTextMatrix(0,-scale,scale,0,origin[0],origin[1]);else stream.setTextMatrix(scale,0,0,scale,origin[0],origin[1]);stream.showText(value);stream.endText();}line++;}
    }
    private static void graphics(PDPageContentStream stream,int alphaByte,Map<Integer,PDExtendedGraphicsState> states)throws IOException {PDExtendedGraphicsState state=states.get(alphaByte);if(state==null){state=new PDExtendedGraphicsState();float alpha=alphaByte/255f;state.setStrokingAlphaConstant(alpha);state.setNonStrokingAlphaConstant(alpha);states.put(alphaByte,state);}stream.setGraphicsStateParameters(state);}
    /** Maps display top-left, page-width-normalized coordinates into crop-box PDF coordinates. */
    public static float[] toPdf(PDPage page,float normalizedX,float normalizedY){
        PDRectangle crop=page.getCropBox();float width=crop.getWidth(),height=crop.getHeight(),scale=displayWidth(page),dx=normalizedX*scale,dy=normalizedY*scale;int rotation=((page.getRotation()%360)+360)%360;float x,y;
        if(rotation==90){x=crop.getLowerLeftX()+dy;y=crop.getLowerLeftY()+dx;}
        else if(rotation==180){x=crop.getLowerLeftX()+width-dx;y=crop.getLowerLeftY()+dy;}
        else if(rotation==270){x=crop.getLowerLeftX()+width-dy;y=crop.getLowerLeftY()+height-dx;}
        else{x=crop.getLowerLeftX()+dx;y=crop.getLowerLeftY()+height-dy;}return new float[]{x,y};
    }
    private static float displayWidth(PDPage page){PDRectangle crop=page.getCropBox();int rotation=((page.getRotation()%360)+360)%360;return rotation==90||rotation==270?crop.getHeight():crop.getWidth();}
    private static void drawStroke(Canvas canvas,Paint pen,InkPage.Stroke stroke,Cancellation cancellation)throws IOException {check(cancellation);InkStrokeRenderer.draw(canvas,pen,stroke);check(cancellation);}
    private static void check(Cancellation cancellation)throws ExportCancelled{if(Thread.currentThread().isInterrupted()||cancellation!=null&&cancellation.cancelled())throw new ExportCancelled();}
}
