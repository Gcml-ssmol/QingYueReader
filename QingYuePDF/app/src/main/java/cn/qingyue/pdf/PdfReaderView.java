package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.view.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** Continuous scrolling or animated single-page canvas; ink always uses page coordinates. */
public final class PdfReaderView extends View {
    public interface Host {void changed(int page);void error(String message);void colorPicked(int color);void readingScrolled();}
    private final Host host;
    private final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    private final ExecutorService worker;
    private final File pdf,directory;
    private final String docId;
    private final int count;
    private final HashMap<Integer,PageState> cache=new HashMap<>();
    private PageLayout layout;
    private volatile boolean disposed;
    private boolean loading,metadataFailed,preparing;
    private int initialPage,selectedPage;
    private double scroll;
    private float zoom=1,panX,panY;
    private boolean horizontalPaging;
    private float slideOffset;
    private int slideTarget=-1;
    private android.animation.ValueAnimator pageAnimator;
    private int mode,color=0xffd93838;
    private float width=0.006f;
    private float eraserDiameter=28f;
    private boolean picking,sampleValid,cursorVisible;
    private int sampledColor=Color.BLACK;
    private final InkInput.Picker sampleInput=new InkInput.Picker();
    private float sampleX,sampleY,cursorX,cursorY;
    private boolean penOnly=true;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG),pen=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final InkInput.Router penRouter=new InkInput.Router();
    private PageState writing;
    private ArrayList<Float> draft;
    private ArrayList<InkPage.Stroke> erased;
    private float eraseX,eraseY,draftWidth;
    private int draftColor;
    private float downX,downY,lastX,lastY,initialPan,initialMaxPan;
    private int downPage,axis;
    private boolean blockUntilDown,multi,touchWriting;
    private final TwoFingerTap twoFingerTap;
    private boolean twoFingerUndoEnabled=true,navigationActive,scrollReported,horizontalHidePending;
    private float gestureViewY,scrolledPixels;
    private final ArrayList<MotionEvent> pendingFingerInk=new ArrayList<>();
    private float focusX,focusY,distance;
    private VelocityTracker velocity;
    private float flingVelocity;
    private long lastFrame;
    private final Runnable flingFrame=()->advanceFling();
    private String documentStatus="准备文档…";
    private static final class PageState {
        final int number;final String key;
        Bitmap bitmap;InkPage ink=new InkPage(Collections.emptyList());
        boolean readable,failed;long revision;String status="已保存";
        PageState(int page,String key){number=page;this.key=key;}
    }

    public PdfReaderView(Context context,File pdf,File directory,String id,int count,int page,ExecutorService worker,Host host){
        super(context);this.pdf=pdf;this.directory=directory;this.docId=id;this.count=count;this.initialPage=page;this.selectedPage=page;this.worker=worker;this.host=host;
        twoFingerTap=new TwoFingerTap(dp(7));
        setBackgroundColor(AppColors.CANVAS);setContentDescription("连续 PDF 页面，上下滚动，左右翻页，双指缩放、短按撤销；手写笔可批注");
        pen.setStrokeCap(Paint.Cap.ROUND);pen.setStrokeJoin(Paint.Join.ROUND);prepare();
    }
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
    private float fittedWidth(int page){return HorizontalPaging.fitWidth(getWidth(),getHeight(),layout.height(page),dp(10));}
    private float pageWidth(){return (horizontalPaging&&layout!=null?fittedWidth(selectedPage):Math.max(1,getWidth()-dp(20)))*zoom;}
    private float left(){return (getWidth()-pageWidth())/2+panX;}
    private double viewport(){return getHeight()/pageWidth();}
    private float maxPan(){return Math.max(0,(pageWidth()-getWidth()+dp(20))/2);}
    private float top(int page){return horizontalPaging?(getHeight()-(float)layout.height(page)*pageWidth())/2+panY:(float)((layout.top(page)-scroll)*pageWidth());}
    private void prepare(){
        if(preparing||disposed)return;preparing=true;metadataFailed=false;documentStatus="准备文档…";notifyHost();worker.execute(()->{
            try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd)){
                float[] ratios=new float[count];for(int i=0;i<count;i++){if(disposed)return;try(PdfRenderer.Page page=renderer.openPage(i)){ratios[i]=page.getHeight()/(float)page.getWidth();}catch(RuntimeException e){ratios[i]=1.4142f;}}
                PageLayout ready=new PageLayout(ratios);main.post(()->{preparing=false;if(disposed)return;layout=ready;documentStatus="";jumpTo(initialPage);});
            }catch(Exception|OutOfMemoryError e){main.post(()->{preparing=false;if(!disposed){metadataFailed=true;documentStatus="文档加载失败，点击顶部状态重试";notifyHost();invalidate();}});}
        });
    }
    public void setTool(int mode,int color,float width,float eraserDiameter,boolean penOnly){finishGesture();stopFling();cancelPageAnimation();cancelColorPick();this.mode=mode;this.color=color;this.width=width;this.eraserDiameter=eraserDiameter;this.penOnly=penOnly;invalidate();}
    public float previewDiameter(float normalizedWidth){return normalizedWidth*pageWidth();}
    public void setTwoFingerUndoEnabled(boolean enabled){twoFingerUndoEnabled=enabled;twoFingerTap.reset();discardPendingFingerInk();}
    public void setHorizontalPaging(boolean enabled){
        if(horizontalPaging==enabled)return;finishGesture();stopFling();cancelPageAnimation();horizontalPaging=enabled;zoom=1;panX=panY=0;
        if(layout!=null){scroll=layout.jumpScroll(selectedPage,viewport());updateViewport();}blockUntilDown=true;
    }
    public boolean isPickingColor(){return picking;}
    public void beginColorPick(){finishGesture();stopFling();cancelPageAnimation();resetVelocity();picking=true;sampleInput.reset();sampleValid=false;notifyHost();invalidate();}
    public void cancelColorPick(){picking=false;sampleInput.reset();sampleValid=false;blockUntilDown=true;notifyHost();invalidate();}
    public int page(){return selectedPage;}
    public boolean ready(){PageState p=cache.get(selectedPage);return p!=null&&p.readable&&p.bitmap!=null&&!p.failed;}
    public boolean canUndo(){return ready()&&cache.get(selectedPage).ink.canUndo();}
    public boolean canRedo(){return ready()&&cache.get(selectedPage).ink.canRedo();}
    public String status(){if(picking)return "拖动取色，松手应用 · 点此取消";if(layout==null)return documentStatus;PageState p=cache.get(selectedPage);return p==null?"加载页面…":p.failed?"页面加载失败，点此重试":!p.readable?"批注读取失败，点此重试":p.status;}
    public void undo(){finishGesture();cancelPageAnimation();if(canUndo()&&cache.get(selectedPage).ink.undo())persist(cache.get(selectedPage));}
    public void redo(){finishGesture();cancelPageAnimation();if(canRedo()&&cache.get(selectedPage).ink.redo())persist(cache.get(selectedPage));}
    public void clearPage(int page){finishGesture();PageState p=cache.get(page);if(p!=null&&p.readable&&!p.ink.strokes().isEmpty()){p.ink.replace(Collections.emptyList());persist(p);}}
    public void retry(){
        if(disposed)return;if(metadataFailed){prepare();invalidate();return;}
        PageState p=cache.get(selectedPage);if(p!=null){if(p.failed||!p.readable){if(p.bitmap!=null)p.bitmap.recycle();cache.remove(selectedPage);}else if(InkStore.pending.containsKey(p.key))persist(p);}
        requestPage();
    }
    public void fitWidth(){finishGesture();stopFling();cancelPageAnimation();double anchor=scroll;zoom=1;panX=panY=0;scroll=anchor;updateViewport();}
    public void jumpTo(int page){
        if(page<0||page>=count||disposed)return;
        if(horizontalPaging&&layout!=null&&getWidth()>0&&Math.abs(page-selectedPage)==1){animatePage(page);return;}
        finishGesture();stopFling();cancelPageAnimation();initialPage=page;selectedPage=page;
        if(layout!=null){scroll=layout.jumpScroll(page,viewport());panX=panY=0;if(horizontalPaging)zoom=1;notifyHost();invalidate();requestPage();}
    }
    private void cancelPageAnimation(){
        if(pageAnimator!=null){pageAnimator.removeAllListeners();pageAnimator.removeAllUpdateListeners();pageAnimator.cancel();pageAnimator=null;}
        slideOffset=0;slideTarget=-1;invalidate();
    }
    private void animatePage(int target){
        if(layout==null||disposed)return;finishGesture();stopFling();resetVelocity();blockUntilDown=true;
        if(pageAnimator!=null){pageAnimator.removeAllListeners();pageAnimator.removeAllUpdateListeners();pageAnimator.cancel();pageAnimator=null;}
        final int destination=Math.max(0,Math.min(count-1,target));
        if(destination!=selectedPage)slideTarget=destination;
        float end=destination==selectedPage?0:(destination>selectedPage?-getWidth():getWidth());
        if(Math.abs(end-slideOffset)<0.5f){cancelPageAnimation();if(destination!=selectedPage){selectedPage=initialPage=destination;zoom=1;panX=panY=0;notifyHost();updateViewport();host.readingScrolled();}return;}
        android.animation.ValueAnimator animator=android.animation.ValueAnimator.ofFloat(slideOffset,end);pageAnimator=animator;
        animator.setDuration(Math.max(150,Math.min(300,(long)(240*Math.abs(end-slideOffset)/Math.max(1,getWidth())))));
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
        animator.addUpdateListener(a->{slideOffset=(float)a.getAnimatedValue();invalidate();});
        animator.addListener(new android.animation.AnimatorListenerAdapter(){
            @Override public void onAnimationEnd(android.animation.Animator a){
                if(pageAnimator!=a||disposed)return;pageAnimator=null;slideOffset=0;slideTarget=-1;
                boolean pageChanged=destination!=selectedPage;
                if(pageChanged){selectedPage=initialPage=destination;zoom=1;panX=panY=0;notifyHost();}
                updateViewport();
                // The host may resize this view. Commit and release the animator first.
                if(pageChanged)host.readingScrolled();
            }
        });requestPage();animator.start();
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        super.onSizeChanged(w,h,oldw,oldh);
        if(layout!=null){
            if(horizontalPaging)cancelPageAnimation();
            if(oldw==0)jumpTo(initialPage);
            else{
                // Collapsing the title row moves this view while a finger is still down.
                // Keep document content under that finger; navigation deltas use raw coordinates.
                if(navigationActive&&w==oldw&&!horizontalPaging){int[] position=new int[2];getLocationOnScreen(position);scroll+=(position[1]-gestureViewY)/pageWidth();gestureViewY=position[1];}
                updateViewport();
            }
        }
    }
    private void updateViewport(){
        if(layout==null||disposed)return;
        if(horizontalPaging){panX=HorizontalPaging.clampPan(panX,pageWidth(),getWidth(),dp(10));panY=HorizontalPaging.clampPan(panY,(float)layout.height(selectedPage)*pageWidth(),getHeight(),dp(10));invalidate();requestPage();return;}
        scroll=layout.clampScroll(scroll,viewport());float limit=maxPan();panX=Math.max(-limit,Math.min(limit,panX));
        if(writing==null){int next=layout.pageAt(scroll+Math.min(viewport()*0.35,0.5));if(next!=selectedPage){selectedPage=next;notifyHost();}}
        invalidate();requestPage();
    }
    private void notifyHost(){if(!disposed)host.changed(selectedPage);}
    private int[] visible(){return horizontalPaging?new int[]{selectedPage,selectedPage}:layout.visibleRange(scroll,viewport());}
    private void requestPage(){
        if(layout==null||disposed||loading||getWidth()<1||getHeight()<1)return;
        int[] range=visible();int wanted=-1;
        if(!cache.containsKey(selectedPage))wanted=selectedPage;
        else if(slideTarget>=0&&!cache.containsKey(slideTarget))wanted=slideTarget;
        else for(int p=range[0];p<=range[1];p++)if(!cache.containsKey(p)){wanted=p;break;}
        long bytes=0;for(PageState p:cache.values())if(p.bitmap!=null)bytes+=p.bitmap.getAllocationByteCount();
        long budget=Math.min(48L*1024*1024,Runtime.getRuntime().maxMemory()/5);
        if(wanted<0&&cache.size()<5&&bytes+10L*1024*1024<=budget)for(int p:new int[]{selectedPage+1,selectedPage-1})if(p>=0&&p<count&&!cache.containsKey(p)){wanted=p;break;}
        if(wanted<0){trimCache();return;}
        final int page=wanted;final int pixels=Math.max(800,Math.min(1800,(int)(getWidth()*1.6f)));loading=true;
        worker.execute(()->{
            if(disposed){return;}PageState result=new PageState(page,docId+"-"+page);
            try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd);PdfRenderer.Page source=renderer.openPage(page)){
                int[] d=PageMath.dimensions(source.getWidth(),source.getHeight(),pixels);
                double shrink=Math.min(1,Math.sqrt(2500000.0/((double)d[0]*d[1])));d[0]=Math.max(1,(int)(d[0]*shrink));d[1]=Math.max(1,(int)(d[1]*shrink));
                result.bitmap=Bitmap.createBitmap(d[0],d[1],Bitmap.Config.ARGB_8888);result.bitmap.eraseColor(Color.WHITE);source.render(result.bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                try{result.ink=new InkPage(InkStore.load(directory,result.key));result.readable=true;result.status=InkStore.pending.containsKey(result.key)?"未保存，点此重试":"已保存";}catch(IOException e){result.readable=false;}
            }catch(Exception|OutOfMemoryError e){if(result.bitmap!=null){result.bitmap.recycle();result.bitmap=null;}result.failed=true;}
            main.post(()->{
                loading=false;if(disposed){if(result.bitmap!=null)result.bitmap.recycle();return;}
                cache.put(page,result);trimCache();notifyHost();invalidate();requestPage();
            });
        });
    }
    private void trimCache(){
        if(layout==null)return;int[] visible=visible();long bytes=0;for(PageState p:cache.values())if(p.bitmap!=null)bytes+=p.bitmap.getAllocationByteCount();
        long budget=Math.min(48L*1024*1024,Runtime.getRuntime().maxMemory()/5);
        ArrayList<PageState> candidates=new ArrayList<>(cache.values());Collections.sort(candidates,(a,b)->Integer.compare(Math.abs(b.number-selectedPage),Math.abs(a.number-selectedPage)));
        for(PageState p:candidates){
            if(p==writing||p.number==selectedPage||p.number==slideTarget||p.number>=visible[0]&&p.number<=visible[1])continue;
            if(cache.size()<=5&&bytes<=budget&&Math.abs(p.number-selectedPage)<=2)continue;
            if(p.bitmap!=null){bytes-=p.bitmap.getAllocationByteCount();p.bitmap.recycle();}cache.remove(p.number);
        }
    }
    private void persist(PageState p){
        List<InkPage.Stroke> snapshot=p.ink.strokes();long version=++p.revision;InkStore.pending.put(p.key,snapshot);p.status="保存中";notifyHost();invalidate();
        worker.execute(()->{boolean ok=true;try{InkStore.save(directory,p.key,snapshot);}catch(IOException|RuntimeException e){ok=false;}final boolean saved=ok;
            main.post(()->{if(p.revision==version){p.status=saved?"已保存":"未保存，点此重试";notifyHost();}if(!saved)host.error("批注保存失败，请检查空间并返回对应页点击状态重试");});
        });
    }
    private void drawStroke(Canvas c,int color,float width,float[] points){
        pen.setColor(color);pen.setStrokeWidth(width);if(points.length==2){pen.setStyle(Paint.Style.FILL);c.drawCircle(points[0],points[1],width/2,pen);return;}
        pen.setStyle(Paint.Style.STROKE);path.reset();path.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);c.drawPath(path,pen);
    }
    private void drawPage(Canvas c,int page,float x,float y,float w){
        float h=(float)(layout.height(page)*w);PageState p=cache.get(page);paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x1636291f);c.drawRect(x-dp(1),y+dp(2),x+w+dp(1),y+h+dp(3),paint);
        paint.setColor(Color.WHITE);c.drawRect(x,y,x+w,y+h,paint);
        if(p==null||p.bitmap==null){paint.setColor(AppColors.MUTED);paint.setTextSize(dp(13));paint.setTextAlign(Paint.Align.CENTER);c.drawText(p!=null&&p.failed?"此页加载失败":"正在加载第 "+(page+1)+" 页",x+w/2,Math.max(dp(24),Math.min(getHeight()-dp(28),y+h/2)),paint);return;}
        c.drawBitmap(p.bitmap,null,new RectF(x,y,x+w,y+h),paint);c.save();c.translate(x,y);c.scale(w,w);c.clipRect(0,0,1,(float)layout.height(page));
        for(InkPage.Stroke s:p==writing&&erased!=null?erased:p.ink.strokes())drawStroke(c,s.color,s.width,s.points);
        if(p==writing&&draft!=null&&!draft.isEmpty()){float[] points=new float[draft.size()];for(int i=0;i<points.length;i++)points[i]=draft.get(i);drawStroke(c,draftColor,draftWidth,points);}c.restore();
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);paint.setStyle(Paint.Style.FILL);
        if(layout==null){paint.setColor(AppColors.MUTED);paint.setTextSize(dp(14));paint.setTextAlign(Paint.Align.CENTER);c.drawText(documentStatus,getWidth()/2f,getHeight()/2f,paint);return;}
        int[] range=visible();float w=pageWidth(),x=left();
        if(horizontalPaging){c.save();c.clipRect(slideOffset,0,slideOffset+getWidth(),getHeight());}
        for(int page=range[0];page<=range[1];page++)drawPage(c,page,x+(horizontalPaging?slideOffset:0),top(page),w);
        if(horizontalPaging)c.restore();
        if(horizontalPaging&&slideTarget>=0&&slideTarget!=selectedPage){
            float targetWidth=fittedWidth(slideTarget),slot=slideOffset+(slideTarget>selectedPage?getWidth():-getWidth()),targetX=(getWidth()-targetWidth)/2f+slot;
            c.save();c.clipRect(slot,0,slot+getWidth(),getHeight());
            drawPage(c,slideTarget,targetX,(getHeight()-(float)layout.height(slideTarget)*targetWidth)/2,targetWidth);
            c.restore();
        }
        if(cursorVisible&&erased!=null){float radius=dp(eraserDiameter)/2;paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(3));paint.setColor(Color.WHITE);c.drawCircle(cursorX,cursorY,radius,paint);paint.setStrokeWidth(dp(1.3f));paint.setColor(AppColors.ACCENT_DARK);c.drawCircle(cursorX,cursorY,radius,paint);paint.setStyle(Paint.Style.FILL);}
        if(picking&&sampleValid){
            float r=dp(25),cx=Math.max(r+dp(6),Math.min(getWidth()-r-dp(6),sampleX)),cy=sampleY-dp(64);
            if(cy<r+dp(8))cy=sampleY+dp(70);cy=Math.max(r+dp(8),Math.min(getHeight()-r-dp(26),cy));
            paint.setColor(Color.WHITE);c.drawCircle(cx,cy,r+dp(4),paint);paint.setColor(sampledColor);c.drawCircle(cx,cy,r,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(AppColors.INK);c.drawCircle(cx,cy,r,paint);paint.setStyle(Paint.Style.FILL);
            paint.setColor(AppColors.INK);c.drawRoundRect(cx-dp(40),cy+r+dp(6),cx+dp(40),cy+r+dp(27),dp(6),dp(6),paint);
            paint.setColor(Color.WHITE);paint.setTextSize(dp(11));paint.setTextAlign(Paint.Align.CENTER);c.drawText(String.format(Locale.ROOT,"#%06X",sampledColor&0xffffff),cx,cy+r+dp(21),paint);
            paint.setColor(Color.WHITE);paint.setStrokeWidth(dp(3));c.drawLine(sampleX-dp(8),sampleY,sampleX+dp(8),sampleY,paint);c.drawLine(sampleX,sampleY-dp(8),sampleX,sampleY+dp(8),paint);
            paint.setColor(AppColors.INK);paint.setStrokeWidth(dp(1));c.drawLine(sampleX-dp(8),sampleY,sampleX+dp(8),sampleY,paint);c.drawLine(sampleX,sampleY-dp(8),sampleX,sampleY+dp(8),paint);
        }
    }
    private void sampleColor(float screenX,float screenY){
        sampleX=screenX;sampleY=screenY;sampleValid=false;if(layout==null)return;
        double documentY=scroll+screenY/pageWidth();int page=horizontalPaging?selectedPage:layout.pageAt(documentY);PageState p=cache.get(page);
        float x=inkX(screenX),y=horizontalPaging?(screenY-top(page))/pageWidth():(float)(documentY-layout.top(page));
        if(p==null||p.bitmap==null||p.failed||x<0||x>1||y<0||y>layout.height(page)){invalidate();return;}
        // Sample the same rendered PDF + committed ink composition as the page canvas.
        Bitmap pixel=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);
        try{Canvas c=new Canvas(pixel);c.drawColor(Color.WHITE);float w=pageWidth();c.translate(0.5f-x*w,0.5f-y*w);
            Paint bitmapPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);c.drawBitmap(p.bitmap,null,new RectF(0,0,w,(float)layout.height(page)*w),bitmapPaint);c.scale(w,w);
            for(InkPage.Stroke s:p.ink.strokes())drawStroke(c,s.color,s.width,s.points);
            sampledColor=pixel.getPixel(0,0)|0xff000000;sampleValid=true;
        }finally{pixel.recycle();}invalidate();
    }
    private boolean pickingTouch(MotionEvent e){
        int action=e.getActionMasked(),changed=e.getActionIndex();
        int index=e.findPointerIndex(sampleInput.activeId);
        int route=sampleInput.route(action,e.getPointerId(changed),e.getToolType(changed),index>=0);
        if(route==InkInput.BEGIN){sampleColor(e.getX(changed),e.getY(changed));return true;}
        if(route==InkInput.CANCEL){sampleValid=false;invalidate();return true;}
        if(route==InkInput.MOVE)sampleColor(e.getX(index),e.getY(index));
        if(route==InkInput.END){
            sampleColor(e.getX(index),e.getY(index));
            if(sampleValid){int picked=sampledColor;cancelColorPick();host.colorPicked(picked);}else host.error("请在已加载的 PDF 页面内取色");
        }return true;
    }
    private float inkX(float x){return (x-left())/pageWidth();}
    private float inkY(float y){return (y-top(writing.number))/pageWidth();}
    private boolean startInk(float x,float y,boolean eraser){
        if(layout==null||mode==0)return false;stopFling();int page=horizontalPaging?selectedPage:layout.pageAt(scroll+y/pageWidth());PageState p=cache.get(page);
        if(p==null||!p.readable||p.bitmap==null||p.failed)return false;
        double localY=(y-top(page))/pageWidth();float localX=inkX(x);if(localX<0||localX>1||localY<0||localY>layout.height(page))return false;
        writing=p;selectedPage=page;cursorX=x;cursorY=y;cursorVisible=eraser||mode==3;notifyHost();
        if(eraser||mode==3){erased=new ArrayList<>(p.ink.strokes());eraseX=localX;eraseY=(float)localY;erase(localX,(float)localY);}
        else{draft=new ArrayList<>();draftColor=(color&0xffffff)|(mode==2?0x55000000:0xff000000);draftWidth=width;point(localX,(float)localY);}invalidate();return true;
    }
    private void point(float x,float y){
        if(draft==null||writing==null||draft.size()/2>=InkPage.MAX_POINTS)return;x=Math.max(0,Math.min(1,x));y=Math.max(0,Math.min((float)layout.height(writing.number),y));int n=draft.size();
        if(n>=2&&Math.hypot(x-draft.get(n-2),y-draft.get(n-1))<0.0005)return;draft.add(x);draft.add(y);
    }
    private void erase(float x,float y){if(erased==null)return;float radius=dp(eraserDiameter)/2/pageWidth();for(int i=erased.size()-1;i>=0;i--)if(InkPage.hit(erased.get(i),eraseX,eraseY,x,y,radius))erased.remove(i);eraseX=x;eraseY=y;}
    private void inkEvent(MotionEvent e,int index){if(writing==null)return;cursorX=e.getX(index);cursorY=e.getY(index);for(int h=0;h<e.getHistorySize();h++){float x=inkX(e.getHistoricalX(index,h)),y=inkY(e.getHistoricalY(index,h));point(x,y);erase(x,y);}float x=inkX(e.getX(index)),y=inkY(e.getY(index));point(x,y);erase(x,y);invalidate();}
    public void finishGesture(){
        discardPendingFingerInk();twoFingerTap.reset();navigationActive=false;horizontalHidePending=false;
        if(writing!=null)try{if(draft!=null&&!draft.isEmpty()){float[] points=new float[draft.size()];for(int i=0;i<points.length;i++)points[i]=draft.get(i);writing.ink.add(new InkPage.Stroke(draftColor,draftWidth,points));persist(writing);}else if(erased!=null&&erased.size()!=writing.ink.strokes().size()){writing.ink.replace(erased);persist(writing);}}
        catch(IllegalArgumentException e){host.error("本页批注已达到容量上限");}
        cancelInk();
    }
    private void cancelInk(){writing=null;draft=null;erased=null;touchWriting=false;cursorVisible=false;penRouter.reset();invalidate();}
    private void stopFling(){flingVelocity=0;removeCallbacks(flingFrame);}
    private void advanceFling(){
        if(disposed||writing!=null||layout==null||horizontalPaging)return;long now=android.os.SystemClock.uptimeMillis();float dt=Math.min(0.04f,(now-lastFrame)/1000f);lastFrame=now;
        double before=scroll;scroll-=flingVelocity*dt/pageWidth();updateViewport();flingVelocity*=Math.exp(-4.8*dt);
        if(Math.abs(flingVelocity)>dp(25)&&Math.abs(scroll-before)>0.000001)postOnAnimation(flingFrame);else stopFling();
    }
    private void resetVelocity(){if(velocity!=null){velocity.recycle();velocity=null;}}
    private void addVelocity(MotionEvent e){
        if(velocity==null)return;MotionEvent raw=MotionEvent.obtain(e);
        raw.offsetLocation(e.getRawX()-e.getX(),e.getRawY()-e.getY());velocity.addMovement(raw);raw.recycle();
    }
    private void discardPendingFingerInk(){for(MotionEvent event:pendingFingerInk)event.recycle();pendingFingerInk.clear();}
    private void reportDeferredScroll(){
        if(!horizontalHidePending)return;horizontalHidePending=false;
        if(!disposed)host.readingScrolled();
    }
    private void flushPendingFingerInk(){
        if(pendingFingerInk.isEmpty())return;MotionEvent first=pendingFingerInk.get(0);
        touchWriting=startInk(first.getX(),first.getY(),false);
        if(touchWriting)for(int i=1;i<pendingFingerInk.size();i++)inkEvent(pendingFingerInk.get(i),0);
        discardPendingFingerInk();
    }
    private boolean trackTwoFingerTap(MotionEvent e){
        int n=e.getPointerCount(),changed=e.getPointerId(e.getActionIndex());
        int[] ids=new int[n],tools=new int[n];float[] xs=new float[n],ys=new float[n];
        float ox=e.getRawX()-e.getX(),oy=e.getRawY()-e.getY();
        for(int i=0;i<n;i++){ids[i]=e.getPointerId(i);tools[i]=e.getToolType(i);}
        // Historical excursions also reject a tap even if fingers return to their start points.
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE)for(int h=0;h<e.getHistorySize();h++){
            for(int i=0;i<n;i++){xs[i]=e.getHistoricalX(i,h)+ox;ys[i]=e.getHistoricalY(i,h)+oy;}
            twoFingerTap.event(MotionEvent.ACTION_MOVE,changed,e.getHistoricalEventTime(h),ids,tools,xs,ys);
        }
        for(int i=0;i<n;i++){xs[i]=e.getX(i)+ox;ys[i]=e.getY(i)+oy;}
        return twoFingerTap.event(e.getActionMasked(),changed,e.getEventTime(),ids,tools,xs,ys);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(layout==null||disposed)return true;int action=e.getActionMasked(),changed=e.getActionIndex();
        if(pageAnimator!=null){twoFingerTap.reset();blockUntilDown=true;return true;}
        if(picking){twoFingerTap.reset();discardPendingFingerInk();return pickingTouch(e);}
        boolean mayRecognize=twoFingerUndoEnabled&&penRouter.activeId<0&&(!blockUntilDown||action==MotionEvent.ACTION_DOWN||twoFingerTap.pairPending());
        if(mayRecognize&&trackTwoFingerTap(e)){
            discardPendingFingerInk();cancelInk();stopFling();resetVelocity();navigationActive=false;blockUntilDown=true;
            if(canUndo())undo();else host.error("当前页面没有可撤销的笔画");return true;
        }
        if(!mayRecognize)twoFingerTap.reset();
        int penIndex=e.findPointerIndex(penRouter.activeId);int route=penRouter.route(action,e.getPointerId(changed),e.getToolType(changed),mode!=0,penIndex>=0);
        if(route!=InkInput.TOUCH){
            twoFingerTap.reject();discardPendingFingerInk();navigationActive=false;horizontalHidePending=false;stopFling();resetVelocity();
            if(route==InkInput.BEGIN){cancelPageAnimation();cancelTouchDraft();startInk(e.getX(changed),e.getY(changed),e.getToolType(changed)==MotionEvent.TOOL_TYPE_ERASER);}
            else if(route==InkInput.MOVE)inkEvent(e,penIndex);
            else if(route==InkInput.END){inkEvent(e,penIndex);finishGesture();blockUntilDown=true;}
            else if(route==InkInput.CANCEL){cancelInk();blockUntilDown=true;}return true;
        }
        if(action==MotionEvent.ACTION_DOWN){
            discardPendingFingerInk();blockUntilDown=false;multi=false;axis=0;scrollReported=false;horizontalHidePending=false;scrolledPixels=0;navigationActive=true;
            stopFling();resetVelocity();velocity=VelocityTracker.obtain();addVelocity(e);
            downX=lastX=e.getRawX();downY=lastY=e.getRawY();gestureViewY=e.getRawY()-e.getY();downPage=selectedPage;initialPan=panX;initialMaxPan=maxPan();
            if(!penOnly&&mode!=0){
                if(twoFingerUndoEnabled&&twoFingerTap.firstPending())pendingFingerInk.add(MotionEvent.obtain(e));
                else touchWriting=startInk(e.getX(),e.getY(),false);
            }return true;
        }
        if(blockUntilDown){if(action==MotionEvent.ACTION_UP){navigationActive=false;reportDeferredScroll();}else if(action==MotionEvent.ACTION_CANCEL)horizontalHidePending=false;return true;}
        if(action==MotionEvent.ACTION_CANCEL){discardPendingFingerInk();twoFingerTap.reset();navigationActive=false;horizontalHidePending=false;cancelPageAnimation();cancelInk();resetVelocity();return true;}
        if(action==MotionEvent.ACTION_POINTER_DOWN){
            discardPendingFingerInk();cancelPageAnimation();cancelInk();multi=true;resetVelocity();
            focusX=(e.getX(0)+e.getX(1))/2+e.getRawX()-e.getX();focusY=(e.getY(0)+e.getY(1))/2+e.getRawY()-e.getY();
            distance=(float)Math.hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0));return true;
        }
        if(action==MotionEvent.ACTION_POINTER_UP){blockUntilDown=true;navigationActive=false;return true;}
        if(e.getPointerCount()>=2&&action==MotionEvent.ACTION_MOVE){
            // Do not make tiny zoom/position changes while this can still be an undo tap.
            if(twoFingerTap.pairPending())return true;
            float ox=e.getRawX()-e.getX(),oy=e.getRawY()-e.getY();
            float fx=(e.getX(0)+e.getX(1))/2+ox,fy=(e.getY(0)+e.getY(1))/2+oy,dist=(float)Math.hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0));
            double anchorY=horizontalPaging?(focusY-oy-top(selectedPage))/pageWidth():scroll+(focusY-oy)/pageWidth();float anchorX=(focusX-ox-left())/pageWidth();zoom=Math.max(1,Math.min(5,zoom*(distance>0?dist/distance:1)));
            panX=fx-ox-getWidth()/2f+(0.5f-anchorX)*pageWidth();
            if(horizontalPaging)panY=fy-oy-(getHeight()-(float)layout.height(selectedPage)*pageWidth())/2-(float)anchorY*pageWidth();
            else scroll=anchorY-(fy-oy)/pageWidth();
            focusX=fx;focusY=fy;distance=dist;gestureViewY=oy;updateViewport();return true;
        }
        addVelocity(e);
        if(action==MotionEvent.ACTION_MOVE){
            if(!pendingFingerInk.isEmpty()){
                pendingFingerInk.add(MotionEvent.obtain(e));
                if(twoFingerTap.firstPending())return true;
                flushPendingFingerInk();if(touchWriting)return true;
            }
            if(touchWriting){inkEvent(e,0);return true;}
            float deltaX=e.getRawX()-lastX,deltaY=e.getRawY()-lastY,totalX=e.getRawX()-downX,totalY=e.getRawY()-downY;
            if(axis==0&&Math.hypot(totalX,totalY)>dp(7))axis=Math.abs(totalX)>Math.abs(totalY)*1.2?1:2;
            if(axis!=0){
                double before=scroll;float beforePanY=panY;
                if(horizontalPaging){
                    int direction=ReaderNavigation.horizontalPage(totalX,totalY,dp(7),initialPan,initialMaxPan);
                    if(axis==1&&direction!=0){
                        int target=HorizontalPaging.target(downPage,count,totalX,totalY,dp(7),initialPan,initialMaxPan);
                        slideTarget=target==selectedPage?-1:target;slideOffset=Math.max(-getWidth(),Math.min(getWidth(),totalX))*(target==selectedPage?0.22f:1);
                        requestPage();invalidate();
                    }else{panX+=deltaX;panY+=deltaY;updateViewport();}
                }else{if(zoom>1.001f)panX+=deltaX;if(axis==2||zoom>1.001f)scroll-=deltaY/pageWidth();updateViewport();}
                if(axis==2&&e.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER&&!scrollReported){
                    scrolledPixels+=horizontalPaging?Math.abs(panY-beforePanY):Math.abs((float)(scroll-before)*pageWidth());
                    if(scrolledPixels>=dp(24)){
                        scrollReported=true;
                        // Single-page fitting depends on the available height; resizing during
                        // a zoomed drag would change the page scale under the fingers.
                        if(horizontalPaging)horizontalHidePending=true;else host.readingScrolled();
                    }
                }
            }
            lastX=e.getRawX();lastY=e.getRawY();gestureViewY=e.getRawY()-e.getY();return true;
        }
        if(action==MotionEvent.ACTION_UP){
            navigationActive=false;flushPendingFingerInk();
            if(touchWriting){inkEvent(e,0);finishGesture();resetVelocity();return true;}
            float x=e.getRawX()-downX,y=e.getRawY()-downY;
            if(horizontalPaging&&axis==1&&!multi&&slideOffset!=0){
                float threshold=Math.min(dp(64),getWidth()*0.18f);
                if(velocity!=null){velocity.computeCurrentVelocity(1000,dp(5000));float vx=velocity.getXVelocity();if(Math.abs(vx)>dp(650)&&vx*x>0)threshold=dp(20);}
                int target=HorizontalPaging.target(downPage,count,x,y,threshold,initialPan,initialMaxPan);animatePage(target);
            }
            else if(!horizontalPaging&&axis==2&&velocity!=null&&!multi){velocity.computeCurrentVelocity(1000,dp(5000));flingVelocity=velocity.getYVelocity();if(Math.abs(flingVelocity)>dp(100)){lastFrame=android.os.SystemClock.uptimeMillis();postOnAnimation(flingFrame);}}
            resetVelocity();performClick();reportDeferredScroll();return true;
        }return true;
    }
    // Keep the active pen router while discarding any interrupted finger preview.
    private void cancelTouchDraft(){writing=null;draft=null;erased=null;touchWriting=false;}
    @Override public boolean performClick(){super.performClick();return true;}
    public void pause(){finishGesture();stopFling();cancelPageAnimation();cancelColorPick();}
    public void dispose(){pause();disposed=true;resetVelocity();for(PageState p:cache.values())if(p.bitmap!=null)p.bitmap.recycle();cache.clear();}
}
