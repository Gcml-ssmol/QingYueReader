package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.LruCache;
import android.view.View;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small live page preview. Image decoding is deferred until a row is visible. */
final class NotePagePreviewView extends View {
    private static final ExecutorService IMAGES=Executors.newSingleThreadExecutor();
    private static final LruCache<String,Bitmap> CACHE=new LruCache<String,Bitmap>(6*1024*1024){@Override protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}};
    private static final Set<String> LOADING=Collections.synchronizedSet(new HashSet<>()),FAILED=Collections.synchronizedSet(new HashSet<>());
    private static final HashMap<String,ArrayList<WeakReference<NotePagePreviewView>>> WAITING=new HashMap<>();
    private final NoteDocument document;
    private final String noteId;
    private int page;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Rect visible=new Rect();

    NotePagePreviewView(Context context,NoteDocument document,String noteId,int page){super(context);this.document=document;this.noteId=noteId;this.page=page;setContentDescription("第"+(page+1)+"页预览");}

    void bindPage(int page){this.page=page;setContentDescription("第"+(page+1)+"页预览");invalidate();}

    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float viewWidth=getWidth(),viewHeight=getHeight();paint.setStyle(Paint.Style.FILL);paint.setColor(AppColors.PAPER);canvas.drawRect(1,1,viewWidth-1,viewHeight-1,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);paint.setColor(AppColors.BORDER);canvas.drawRect(1,1,viewWidth-1,viewHeight-1,paint);
        if(!getLocalVisibleRect(visible))return;List<NoteDocument.Item> items=document.pageItems(page);double left=0,top=0,right=document.width,bottom=document.height;
        if(document.infinite()){if(items.isEmpty()){right=595;bottom=842;}else{left=top=Double.POSITIVE_INFINITY;right=bottom=Double.NEGATIVE_INFINITY;for(NoteDocument.Item item:items){left=Math.min(left,item.left());top=Math.min(top,item.top());right=Math.max(right,item.right());bottom=Math.max(bottom,item.bottom());}left-=24;top-=24;right+=24;bottom+=24;}}
        double rangeW=right-left,rangeH=bottom-top;if(!NoteDocument.finite(rangeW)||!NoteDocument.finite(rangeH)||rangeW<=0||rangeH<=0)return;float scale=(float)Math.min((viewWidth-8)/rangeW,(viewHeight-8)/rangeH);if(Float.isNaN(scale)||Float.isInfinite(scale)||scale<=0)return;int saved=canvas.save();canvas.clipRect(3,3,viewWidth-3,viewHeight-3);canvas.translate((float)((viewWidth-rangeW*scale)/2),(float)((viewHeight-rangeH*scale)/2));canvas.scale(scale,scale);canvas.translate((float)-left,(float)-top);drawTemplate(canvas,left,top,right,bottom);for(NoteDocument.Item item:items)drawItem(canvas,item);canvas.restoreToCount(saved);
    }

    private void drawTemplate(Canvas canvas,double left,double top,double right,double bottom){if("blank".equals(document.template))return;double spacing="grid".equals(document.template)?20:28;paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(.7f);paint.setColor("grid".equals(document.template)?0xffe8ded1:0xffe2d5c5);for(double y=Math.ceil(top/spacing)*spacing,n=0;n<150&&y<=bottom;y+=spacing,n++)canvas.drawLine((float)left,(float)y,(float)right,(float)y,paint);if("grid".equals(document.template))for(double x=Math.ceil(left/spacing)*spacing,n=0;n<150&&x<=right;x+=spacing,n++)canvas.drawLine((float)x,(float)top,(float)x,(float)bottom,paint);}

    private void drawItem(Canvas canvas,NoteDocument.Item item){double[] p=item.points;paint.setColor(item.color);paint.setStrokeWidth(item.width);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStyle(Paint.Style.STROKE);
        if(item.type==NoteDocument.TEXT){NoteTextLayout.draw(canvas,item);return;}
        if(item.type==NoteDocument.IMAGE){drawImage(canvas,item);return;}
        if(item.type==NoteDocument.STROKE){if(p.length==2){paint.setStyle(Paint.Style.FILL);canvas.drawCircle((float)p[0],(float)p[1],item.width/2,paint);}else{Path path=new Path();path.moveTo((float)p[0],(float)p[1]);for(int i=2;i<p.length;i+=2)path.lineTo((float)p[i],(float)p[i+1]);canvas.drawPath(path,paint);}return;}
        if(item.type==NoteDocument.RECTANGLE){canvas.drawRect((float)item.left(),(float)item.top(),(float)item.right(),(float)item.bottom(),paint);return;}
        if(item.type==NoteDocument.CIRCLE){canvas.drawOval((float)item.left(),(float)item.top(),(float)item.right(),(float)item.bottom(),paint);return;}
        canvas.drawLine((float)p[0],(float)p[1],(float)p[2],(float)p[3],paint);if(item.type==NoteDocument.ARROW){double[] head=NoteDocument.arrowHeads(p,item.width);canvas.drawLine((float)p[2],(float)p[3],(float)head[0],(float)head[1],paint);canvas.drawLine((float)p[2],(float)p[3],(float)head[2],(float)head[3],paint);}
    }

    private void drawImage(Canvas canvas,NoteDocument.Item item){String key=noteId+"/"+item.image;Bitmap bitmap; synchronized(CACHE){bitmap=CACHE.get(key);}RectF bounds=new RectF((float)item.left(),(float)item.top(),(float)item.right(),(float)item.bottom());paint.setStyle(Paint.Style.FILL);paint.setColor(AppColors.SOFT);canvas.drawRect(bounds,paint);if(bitmap!=null){paint.setColor(Color.WHITE);canvas.drawBitmap(bitmap,null,bounds,paint);}else if(isShown()&&!FAILED.contains(key)){synchronized(WAITING){ArrayList<WeakReference<NotePagePreviewView>> rows=WAITING.get(key);if(rows==null){rows=new ArrayList<>();WAITING.put(key,rows);}boolean found=false;for(WeakReference<NotePagePreviewView> row:rows)if(row.get()==this){found=true;break;}if(!found)rows.add(new WeakReference<>(this));}if(LOADING.add(key))loadImage(key,item.image);}}

    private void loadImage(String key,String filename){Context app=getContext().getApplicationContext();IMAGES.execute(()->{Bitmap bitmap=null;try{File file=new File(NoteStore.imagesDirectory(app,noteId),filename);BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),options);if(options.outWidth>0&&options.outHeight>0){options.inSampleSize=1;while(options.outWidth/options.inSampleSize>256||options.outHeight/options.inSampleSize>256)options.inSampleSize*=2;options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;bitmap=BitmapFactory.decodeFile(file.getAbsolutePath(),options);}}catch(IOException|RuntimeException|OutOfMemoryError ignored){}if(bitmap==null)FAILED.add(key);else synchronized(CACHE){CACHE.put(key,bitmap);}LOADING.remove(key);ArrayList<WeakReference<NotePagePreviewView>> rows;synchronized(WAITING){rows=WAITING.remove(key);}if(rows!=null)for(WeakReference<NotePagePreviewView> reference:rows){NotePagePreviewView row=reference.get();if(row!=null)row.postInvalidate();}});}
}
