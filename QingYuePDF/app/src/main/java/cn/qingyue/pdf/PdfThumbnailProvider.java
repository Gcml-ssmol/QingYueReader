package cn.qingyue.pdf;

import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.*;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Small independent thumbnail queue; cached bitmaps are owned by this provider. */
public final class PdfThumbnailProvider implements AutoCloseable {
    public interface Callback {void ready(int page,Bitmap bitmap,String error);}
    private static final int MAX_PENDING=12;
    private final File file;private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>(),r->{Thread t=new Thread(r,"qingyue-pdf-thumbnails");t.setPriority(Thread.NORM_PRIORITY-1);return t;});
    private final LinkedHashMap<Integer,Bitmap> cache=new LinkedHashMap<>(16,.75f,true);
    private final LinkedHashMap<Integer,Request> pending=new LinkedHashMap<>();
    private final long budget;private boolean closed;private int generation;
    private final class Request implements Runnable {final int page,width,token;final ArrayList<Callback> callbacks=new ArrayList<>();Request(int page,int width,int token,Callback callback){this.page=page;this.width=width;this.token=token;callbacks.add(callback);}@Override public void run(){render(this);}}
    public PdfThumbnailProvider(File file){this.file=file;budget=Math.min(12L*1024*1024,Math.max(4L*1024*1024,Runtime.getRuntime().maxMemory()/16));}
    public void request(int page,int width,Callback callback){
        if(callback==null)return;width=Math.max(72,Math.min(320,width));Request request;synchronized(this){if(closed)return;Bitmap existing=cache.get(page);if(existing!=null&&!existing.isRecycled()){callback.ready(page,existing,null);return;}Request waiting=pending.get(page);if(waiting!=null){waiting.callbacks.add(callback);return;}evictQueued();request=new Request(page,width,generation,callback);pending.put(page,request);}
        try{worker.execute(request);}catch(RejectedExecutionException ignored){synchronized(this){if(pending.get(page)==request)pending.remove(page);}}
    }
    private void evictQueued(){
        while(pending.size()>=MAX_PENDING){boolean removed=false;Iterator<Map.Entry<Integer,Request>> iterator=pending.entrySet().iterator();while(iterator.hasNext()){Map.Entry<Integer,Request> entry=iterator.next();if(worker.getQueue().remove(entry.getValue())){iterator.remove();removed=true;break;}}if(!removed)break;}
    }
    /** Cancel detached/offscreen bindings, including running work before it publishes. */
    public synchronized void cancelOutside(int firstPage,int lastPage){
        Iterator<Map.Entry<Integer,Request>> iterator=pending.entrySet().iterator();
        while(iterator.hasNext()){Map.Entry<Integer,Request> entry=iterator.next();if(entry.getKey()<firstPage||entry.getKey()>lastPage){worker.getQueue().remove(entry.getValue());entry.getValue().callbacks.clear();iterator.remove();}}
    }
    private void render(Request request){Bitmap bitmap=null;String error=null;
        synchronized(this){if(closed||pending.get(request.page)!=request)return;}
        try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(descriptor);PdfRenderer.Page source=renderer.openPage(request.page)){
            int[] size=PageMath.dimensions(source.getWidth(),source.getHeight(),request.width);double shrink=Math.min(1,Math.sqrt(750000d/(size[0]*(double)size[1])));size[0]=Math.max(1,(int)(size[0]*shrink));size[1]=Math.max(1,(int)(size[1]*shrink));bitmap=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);source.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        }catch(Exception|OutOfMemoryError problem){if(bitmap!=null){bitmap.recycle();bitmap=null;}error="缩略图不可用";}
        final Bitmap ready=bitmap;final String failure=error;main.post(()->{ArrayList<Callback> callbacks;Bitmap delivered=ready;synchronized(PdfThumbnailProvider.this){Request current=pending.get(request.page);if(current!=request||closed||request.token!=generation){if(ready!=null)ready.recycle();return;}pending.remove(request.page);callbacks=new ArrayList<>(request.callbacks);if(ready!=null){cache.put(request.page,ready);trim();delivered=cache.get(request.page);}}for(Callback item:callbacks)item.ready(request.page,delivered,delivered==null?"缩略图缓存不足":failure);});
    }
    // Published bitmaps may still be attached to ImageViews/display lists. Drop our cache
    // reference and let GC free them after the last view releases them; never recycle here.
    private void trim(){long bytes=0;for(Bitmap bitmap:cache.values())bytes+=bitmap.getAllocationByteCount();Iterator<Map.Entry<Integer,Bitmap>> iterator=cache.entrySet().iterator();while(bytes>budget&&iterator.hasNext()){Bitmap bitmap=iterator.next().getValue();bytes-=bitmap.getAllocationByteCount();iterator.remove();}}
    @Override public synchronized void close(){if(closed)return;closed=true;generation++;worker.shutdownNow();pending.clear();main.removeCallbacksAndMessages(null);cache.clear();}
}
