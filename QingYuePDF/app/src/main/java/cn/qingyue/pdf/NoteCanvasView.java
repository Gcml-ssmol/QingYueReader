package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.*;
import android.util.LruCache;
import android.view.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** A viewport over vector paper, including an unbounded two-dimensional canvas. */
public final class NoteCanvasView extends View {
    public interface Host {void changed();void error(String message);default void readingScrolled(){}}
    private static final ExecutorService imageWorker=Executors.newSingleThreadExecutor();
    private final NoteDocument document;
    private final String noteId;
    private final Host host;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final TwoFingerTap twoFinger;
    private final Set<String> loading=new HashSet<>(),failedImages=new HashSet<>();
    private final LruCache<String,Bitmap> bitmaps=new LruCache<String,Bitmap>(20*1024*1024){@Override protected int sizeOf(String k,Bitmap b){return b.getAllocationByteCount();}};
    private String tool="pen";
    private int color=0xff36302b;
    private float width=2;
    private boolean stylusOnly=true,twoFingerEnabled=true,disposed,initialized;
    private double cx,cy,zoom=1;
    private long selected;
    private int active=-1,actionMode; // 1 ink/shape, 2 eraser, 3 pan, 4 image drag, 5 image resize
    private boolean activePen,multi;
    private double startX,startY,lastWorldX,lastWorldY;
    private float lastX,lastY,pinchX,pinchY,pinchSpan;
    private float panTravel;
    private boolean scrollNotified;
    private int resizeCorner;
    private NoteDocument.Item imageOriginal,imagePreview;
    private final ArrayList<double[]> vertices=new ArrayList<>();
    private final Set<Long> erased=new HashSet<>();
    private NoteDocument.Item preview;

    public NoteCanvasView(Context context,NoteDocument document,String noteId,Host host){super(context);this.document=document;this.noteId=noteId;this.host=host;twoFinger=new TwoFingerTap(dp(7));setFocusable(true);setContentDescription("笔记画布，单指拖动画布，双指缩放；选择图片后可拖动与缩放");setLayerType(View.LAYER_TYPE_HARDWARE,null);}
    public void setTool(String tool,int color,float widthPt,boolean stylusOnly){finishGesture();this.tool=tool;this.color=color;this.width=Math.max(.2f,Math.min(100,widthPt));this.stylusOnly=stylusOnly;if(!"select".equals(tool)){selected=0;imagePreview=null;}invalidate();}
    public void setTwoFingerUndoEnabled(boolean enabled){twoFingerEnabled=enabled;twoFinger.reset();}
    public float previewDiameter(float widthPt){return (float)(widthPt*zoom);}
    public boolean canUndo(){return document.canUndo();}
    public boolean canRedo(){return document.canRedo();}
    public boolean hasSelection(){return selected!=0&&document.find(selected)!=null;}
    public void deleteSelected(){finishGesture();if(hasSelection()){document.remove(selected);selected=0;changed();}}
    public void undo(){cancelGesture();if(document.undo()){selected=0;changed();}}
    public void redo(){cancelGesture();if(document.redo()){selected=0;changed();}}
    public NoteDocument snapshot(){syncViewport();return document.copy();}
    public NoteDocument document(){return document;}
    public void save(){if(disposed)return;finishGesture();syncViewport();try{NoteStore.save(getContext(),noteId,document);}catch(IOException|RuntimeException e){host.error("笔记保存失败："+e.getMessage());}}
    public void dispose(){if(disposed)return;save();disposed=true;bitmaps.evictAll();loading.clear();}
    private void changed(){syncViewport();NoteStore.saveAsync(getContext(),noteId,document,message->post(()->{if(!disposed)host.error(message);}));host.changed();invalidate();}
    private void syncViewport(){if(initialized){document.centerX=cx;document.centerY=cy;document.zoom=zoom;document.viewportSaved=true;}}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);if(w<=0||h<=0)return;if(!initialized){initialized=true;if(document.viewportSaved){cx=document.centerX;cy=document.centerY;zoom=document.zoom;}else fit();}invalidate();}
    public void fit(){finishGesture();if(getWidth()==0||getHeight()==0){document.viewportSaved=false;initialized=false;return;}if(document.infinite()){cx=0;cy=0;zoom=Math.max(.25,Math.min(2,getWidth()/700.0));}else{cx=document.width/2;cy=document.height/2;zoom=Math.min((getWidth()-dp(32))/document.width,(getHeight()-dp(32))/document.height);zoom=Math.max(.05,Math.min(12,zoom));}syncViewport();invalidate();}
    public boolean insertImage(String name,int pixelWidth,int pixelHeight){finishGesture();if(disposed||getWidth()<=0||getHeight()<=0||pixelWidth<=0||pixelHeight<=0){host.error("画布尚未准备好，请稍后再插入图片");return false;}double w=Math.min(360,Math.max(80,getWidth()*.65/zoom)),h=w*pixelHeight/(double)pixelWidth;if(h>getHeight()*.7/zoom){h=getHeight()*.7/zoom;w=h*pixelWidth/(double)pixelHeight;}if(!document.infinite()){double scale=Math.min(1,Math.min(document.width*.85/w,document.height*.85/h));w*=scale;h*=scale;}double x=cx-w/2,y=cy-h/2;if(!document.infinite()){x=clamp(x,0,document.width-w);y=clamp(y,0,document.height-h);}try{NoteDocument.Item item=new NoteDocument.Item(document.newId(),NoteDocument.IMAGE,Color.WHITE,1,new double[]{x,y,x+w,y+h},name);document.add(item);selected=item.id;tool="select";changed();return true;}catch(IllegalArgumentException e){host.error(e.getMessage());return false;}}

    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.drawColor(AppColors.CANVAS);if(!initialized)return;int clip=canvas.save();if(document.infinite())canvas.drawColor(AppColors.SURFACE);else{float l=sx(0),t=sy(0),r=sx(document.width),b=sy(document.height);paint.setColor(0x17000000);paint.setStyle(Paint.Style.FILL);canvas.drawRect(l-dp(2),t+dp(3),r+dp(2),b+dp(5),paint);paint.setColor(AppColors.SURFACE);canvas.drawRect(l,t,r,b,paint);canvas.clipRect(l,t,r,b);}drawPaper(canvas);
        double vl=wx(0),vt=wy(0),vr=wx(getWidth()),vb=wy(getHeight());
        for(NoteDocument.Item item:document.items()){if(erased.contains(item.id))continue;NoteDocument.Item shown=imagePreview!=null&&item.id==imagePreview.id?imagePreview:item;double extra=shown.width*2+20;if(shown.right()+extra<vl||shown.left()-extra>vr||shown.bottom()+extra<vt||shown.top()-extra>vb)continue;drawItem(canvas,shown);}
        if(preview!=null)drawItem(canvas,preview);canvas.restoreToCount(clip);
        NoteDocument.Item selection=imagePreview!=null?imagePreview:document.find(selected);if("select".equals(tool)&&selection!=null&&selection.type==NoteDocument.IMAGE)drawSelection(canvas,selection);
        if(actionMode==2){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.5f));paint.setColor(AppColors.ACCENT);canvas.drawCircle(sx(lastWorldX),sy(lastWorldY),(float)(width*zoom*.5),paint);}
    }
    private void drawPaper(Canvas canvas){if("blank".equals(document.template))return;double spacing="grid".equals(document.template)?20:28;while(spacing*zoom<dp(8))spacing*=2;double left=wx(0),top=wy(0),right=wx(getWidth()),bottom=wy(getHeight());if(!document.infinite()){left=Math.max(0,left);top=Math.max(0,top);right=Math.min(document.width,right);bottom=Math.min(document.height,bottom);}paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(.7f));paint.setColor("grid".equals(document.template)?0xffe8ded1:0xffe2d5c5);double y=Math.ceil(top/spacing)*spacing;for(int n=0;n<1500&&y<=bottom;n++,y+=spacing)canvas.drawLine(sx(left),sy(y),sx(right),sy(y),paint);if("grid".equals(document.template)){double x=Math.ceil(left/spacing)*spacing;for(int n=0;n<1500&&x<=right;n++,x+=spacing)canvas.drawLine(sx(x),sy(top),sx(x),sy(bottom),paint);}}
    private void drawItem(Canvas canvas,NoteDocument.Item item){double[] p=item.points;if(item.type==NoteDocument.IMAGE){drawImage(canvas,item);return;}paint.setStyle(Paint.Style.STROKE);paint.setColor(item.color);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeWidth((float)(item.width*zoom));if(item.type==NoteDocument.STROKE){if(p.length==2){paint.setStyle(Paint.Style.FILL);canvas.drawCircle(sx(p[0]),sy(p[1]),Math.max(.3f,(float)(item.width*zoom/2)),paint);}else{Path path=new Path();path.moveTo(sx(p[0]),sy(p[1]));for(int i=2;i<p.length;i+=2)path.lineTo(sx(p[i]),sy(p[i+1]));canvas.drawPath(path,paint);}}else if(item.type==NoteDocument.RECTANGLE)canvas.drawRect(sx(item.left()),sy(item.top()),sx(item.right()),sy(item.bottom()),paint);else if(item.type==NoteDocument.CIRCLE)canvas.drawOval(sx(item.left()),sy(item.top()),sx(item.right()),sy(item.bottom()),paint);else{canvas.drawLine(sx(p[0]),sy(p[1]),sx(p[2]),sy(p[3]),paint);if(item.type==NoteDocument.ARROW){double[] a=NoteDocument.arrowHeads(p,item.width);canvas.drawLine(sx(p[2]),sy(p[3]),sx(a[0]),sy(a[1]),paint);canvas.drawLine(sx(p[2]),sy(p[3]),sx(a[2]),sy(a[3]),paint);}}}
    private void drawImage(Canvas canvas,NoteDocument.Item item){RectF rect=new RectF(sx(item.left()),sy(item.top()),sx(item.right()),sy(item.bottom()));Bitmap bitmap=bitmaps.get(item.image);paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);paint.setAlpha(255);if(bitmap!=null&&!bitmap.isRecycled()){canvas.drawBitmap(bitmap,null,rect,paint);return;}paint.setColor(AppColors.SOFT);canvas.drawRect(rect,paint);paint.setColor(AppColors.MUTED);paint.setTextSize(dp(13));canvas.drawText(failedImages.contains(item.image)?"图片无法读取":"加载图片…",rect.left+dp(10),rect.top+dp(24),paint);if(!loading.contains(item.image)&&!failedImages.contains(item.image))loadImage(item.image);}
    private void loadImage(String name){loading.add(name);final Context app=getContext().getApplicationContext();imageWorker.execute(()->{Bitmap result=null;try{File file=new File(NoteStore.imagesDirectory(app,noteId),name);BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),options);if(options.outWidth>0&&options.outHeight>0){options.inSampleSize=1;while(options.outWidth/options.inSampleSize>2048||options.outHeight/options.inSampleSize>2048)options.inSampleSize*=2;options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;result=BitmapFactory.decodeFile(file.getAbsolutePath(),options);}}catch(IOException|RuntimeException|OutOfMemoryError ignored){}final Bitmap bitmap=result;post(()->{loading.remove(name);if(disposed){if(bitmap!=null)bitmap.recycle();return;}if(bitmap==null)failedImages.add(name);else bitmaps.put(name,bitmap);invalidate();});});}
    private void drawSelection(Canvas canvas,NoteDocument.Item item){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.5f));paint.setColor(AppColors.ACCENT);canvas.drawRect(sx(item.left()),sy(item.top()),sx(item.right()),sy(item.bottom()),paint);for(int i=0;i<4;i++){float x=sx(i%2==0?item.left():item.right()),y=sy(i<2?item.top():item.bottom());paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);canvas.drawCircle(x,y,dp(6),paint);paint.setStyle(Paint.Style.STROKE);paint.setColor(AppColors.ACCENT);canvas.drawCircle(x,y,dp(6),paint);}}

    @Override public boolean onTouchEvent(MotionEvent event){if(disposed)return false;int action=event.getActionMasked(),changed=event.getPointerId(event.getActionIndex());if(action==MotionEvent.ACTION_DOWN)getParent().requestDisallowInterceptTouchEvent(true);
        boolean undoTap=false;if(twoFingerEnabled){int n=event.getPointerCount();int[] ids=new int[n],tools=new int[n];float[] xs=new float[n],ys=new float[n];for(int i=0;i<n;i++){ids[i]=event.getPointerId(i);tools[i]=event.getToolType(i);xs[i]=event.getX(i);ys[i]=event.getY(i);}undoTap=twoFinger.event(action,changed,event.getEventTime(),ids,tools,xs,ys);}
        if(undoTap){cancelGesture();undo();return true;}
        if(action==MotionEvent.ACTION_CANCEL){cancelGesture();return true;}
        if(action==MotionEvent.ACTION_DOWN){multi=false;int i=event.getActionIndex();active=changed;activePen=isPen(event.getToolType(i));begin(event.getX(i),event.getY(i),event.getToolType(i));return true;}
        // A pen pointer owns its gesture even when a resting palm is reported alongside it.
        if(activePen&&active>=0){int i=event.findPointerIndex(active);if(i<0){cancelGesture();return true;}if(action==MotionEvent.ACTION_MOVE)moveSamples(event,i);else if((action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP)&&changed==active){move(event.getX(i),event.getY(i));finishGesture();multi=true;}return true;}
        if(action==MotionEvent.ACTION_POINTER_DOWN){int stylus=-1;for(int i=0;i<event.getPointerCount();i++)if(isPen(event.getToolType(i))){stylus=i;break;}cancelGesture();if(stylus>=0){active=event.getPointerId(stylus);activePen=true;begin(event.getX(stylus),event.getY(stylus),event.getToolType(stylus));return true;}multi=true;setPinch(event);return true;}
        if(multi){if(action==MotionEvent.ACTION_MOVE&&event.getPointerCount()>=2){float x=(event.getX(0)+event.getX(1))/2,y=(event.getY(0)+event.getY(1))/2,span=(float)Math.hypot(event.getX(0)-event.getX(1),event.getY(0)-event.getY(1));if(pinchSpan>0&&span>0){double anchorX=wx(pinchX),anchorY=wy(pinchY);zoom=clamp(zoom*span/pinchSpan,.05,16);cx=anchorX-(x-getWidth()/2.0)/zoom;cy=anchorY-(y-getHeight()/2.0)/zoom;constrainViewport();if(!twoFinger.pairPending())notifyScroll();invalidate();}pinchX=x;pinchY=y;pinchSpan=span;}else if(action==MotionEvent.ACTION_POINTER_UP){pinchSpan=0;}else if(action==MotionEvent.ACTION_UP){multi=false;syncViewport();}return true;}
        if(active<0)return true;int i=event.findPointerIndex(active);if(i<0){cancelGesture();return true;}if(action==MotionEvent.ACTION_MOVE)moveSamples(event,i);else if(action==MotionEvent.ACTION_UP){move(event.getX(i),event.getY(i));finishGesture();performClick();}return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    private void setPinch(MotionEvent event){if(event.getPointerCount()<2){pinchSpan=0;return;}pinchX=(event.getX(0)+event.getX(1))/2;pinchY=(event.getY(0)+event.getY(1))/2;pinchSpan=(float)Math.hypot(event.getX(0)-event.getX(1),event.getY(0)-event.getY(1));}
    private void begin(float x,float y,int eventTool){lastX=x;lastY=y;startX=lastWorldX=wx(x);startY=lastWorldY=wy(y);panTravel=0;scrollNotified=false;vertices.clear();erased.clear();preview=null;imageOriginal=null;imagePreview=null;
        if("select".equals(tool)){NoteDocument.Item chosen=document.find(selected);int handle=chosen==null?-1:handleAt(chosen,x,y);if(handle>=0){actionMode=5;resizeCorner=handle;imageOriginal=chosen;imagePreview=chosen;return;}chosen=imageAt(wx(x),wy(y));selected=chosen==null?0:chosen.id;host.changed();if(chosen!=null){actionMode=4;imageOriginal=chosen;imagePreview=chosen;}else actionMode=3;invalidate();return;}
        boolean draw=(!stylusOnly||isPen(eventTool))&&!"hand".equals(tool);if(!draw){actionMode=3;return;}if(!onPaper(startX,startY)){actionMode=0;return;}if("eraser".equals(tool)||eventTool==MotionEvent.TOOL_TYPE_ERASER){actionMode=2;eraseAt(startX,startY);invalidate();return;}actionMode=1;vertices.add(new double[]{startX,startY});updatePreview(startX,startY);invalidate();}
    private void moveSamples(MotionEvent event,int index){for(int h=0;h<event.getHistorySize();h++)move(event.getHistoricalX(index,h),event.getHistoricalY(index,h));move(event.getX(index),event.getY(index));}
    private void move(float x,float y){double worldX=wx(x),worldY=wy(y);if(actionMode==3){cx-=(x-lastX)/zoom;cy-=(y-lastY)/zoom;constrainViewport();panTravel+=Math.hypot(x-lastX,y-lastY);if(panTravel>dp(12))notifyScroll();}else if(actionMode==1){if(!document.infinite()){worldX=clamp(worldX,0,document.width);worldY=clamp(worldY,0,document.height);}if(vertices.size()<50000&&Math.hypot(worldX-lastWorldX,worldY-lastWorldY)*zoom>=.7)vertices.add(new double[]{worldX,worldY});updatePreview(worldX,worldY);}else if(actionMode==2){double distance=Math.hypot(worldX-lastWorldX,worldY-lastWorldY);int samples=Math.max(1,Math.min(128,(int)Math.ceil(distance/Math.max(1,width*.4))));for(int j=1;j<=samples;j++)eraseAt(lastWorldX+(worldX-lastWorldX)*j/samples,lastWorldY+(worldY-lastWorldY)*j/samples);}else if(actionMode==4&&imageOriginal!=null){double w=imageOriginal.right()-imageOriginal.left(),h=imageOriginal.bottom()-imageOriginal.top(),nx=imageOriginal.left()+worldX-startX,ny=imageOriginal.top()+worldY-startY;if(!document.infinite()){nx=clamp(nx,0,Math.max(0,document.width-w));ny=clamp(ny,0,Math.max(0,document.height-h));}imagePreview=imageOriginal.bounds(nx,ny,w,h);}else if(actionMode==5&&imageOriginal!=null){resizeImage(worldX,worldY);}lastX=x;lastY=y;lastWorldX=worldX;lastWorldY=worldY;invalidate();}
    private void notifyScroll(){if(!scrollNotified){scrollNotified=true;host.readingScrolled();}}
    private void updatePreview(double x,double y){int type=NoteDocument.STROKE;switch(tool){case "line":type=NoteDocument.LINE;break;case "circle":type=NoteDocument.CIRCLE;break;case "rectangle":type=NoteDocument.RECTANGLE;break;case "arrow":type=NoteDocument.ARROW;break;default:break;}double[] points;if(type==NoteDocument.STROKE){points=new double[vertices.size()*2];for(int i=0;i<vertices.size();i++){points[i*2]=vertices.get(i)[0];points[i*2+1]=vertices.get(i)[1];}}else{if(type==NoteDocument.CIRCLE){double side=Math.min(Math.abs(x-startX),Math.abs(y-startY));x=startX+Math.copySign(side,x-startX);y=startY+Math.copySign(side,y-startY);}points=new double[]{startX,startY,x,y};}int ink="marker".equals(tool)?(color&0x00ffffff)|0x60000000:color;preview=new NoteDocument.Item(1,type,ink,width,points,"");}
    private void eraseAt(double x,double y){for(NoteDocument.Item item:document.items())if(item.type!=NoteDocument.IMAGE&&!erased.contains(item.id)&&item.near(x,y,width/2))erased.add(item.id);}
    private void resizeImage(double x,double y){double ratio=(imageOriginal.right()-imageOriginal.left())/(imageOriginal.bottom()-imageOriginal.top());double ax=resizeCorner%2==0?imageOriginal.right():imageOriginal.left(),ay=resizeCorner<2?imageOriginal.bottom():imageOriginal.top();double signX=resizeCorner%2==0?-1:1,signY=resizeCorner<2?-1:1;double w=Math.max(dp(20)/zoom,Math.max((x-ax)*signX,(y-ay)*signY*ratio)),h=w/ratio;if(!document.infinite()){double maxW=signX<0?ax:document.width-ax,maxH=signY<0?ay:document.height-ay;double s=Math.min(1,Math.min(maxW/w,maxH/h));w*=s;h*=s;}if(w>.1&&h>.1)imagePreview=imageOriginal.bounds(signX<0?ax-w:ax,signY<0?ay-h:ay,w,h);}
    private int handleAt(NoteDocument.Item item,float x,float y){for(int i=0;i<4;i++)if(Math.hypot(x-sx(i%2==0?item.left():item.right()),y-sy(i<2?item.top():item.bottom()))<=dp(20))return i;return -1;}
    private NoteDocument.Item imageAt(double x,double y){List<NoteDocument.Item> items=document.items();for(int i=items.size()-1;i>=0;i--){NoteDocument.Item item=items.get(i);if(item.type==NoteDocument.IMAGE&&item.near(x,y,0))return item;}return null;}
    public void finishGesture(){boolean didChange=false;try{if(actionMode==1&&preview!=null){NoteDocument.Item p=preview;document.add(new NoteDocument.Item(document.newId(),p.type,p.color,p.width,p.points,p.image));didChange=true;}else if(actionMode==2&&!erased.isEmpty()){document.remove(erased);didChange=true;}else if((actionMode==4||actionMode==5)&&imagePreview!=null&&imageOriginal!=null&&!Arrays.equals(imagePreview.points,imageOriginal.points)){document.replace(imagePreview);didChange=true;}}catch(IllegalArgumentException e){host.error(e.getMessage());}clearGesture();if(didChange)changed();else{syncViewport();invalidate();}}
    private void cancelGesture(){clearGesture();invalidate();}
    private void clearGesture(){actionMode=0;active=-1;activePen=false;preview=null;vertices.clear();erased.clear();imageOriginal=null;imagePreview=null;}
    private void constrainViewport(){if(document.infinite()){if(!NoteDocument.finite(cx)||!NoteDocument.finite(cy)){cx=0;cy=0;}return;}double halfW=getWidth()/(2*zoom),halfH=getHeight()/(2*zoom);cx=document.width<halfW*2?document.width/2:clamp(cx,halfW,document.width-halfW);cy=document.height<halfH*2?document.height/2:clamp(cy,halfH,document.height-halfH);}
    private boolean onPaper(double x,double y){return document.infinite()||(x>=0&&y>=0&&x<=document.width&&y<=document.height);}
    private static boolean isPen(int tool){return tool==MotionEvent.TOOL_TYPE_STYLUS||tool==MotionEvent.TOOL_TYPE_ERASER;}
    private float sx(double x){return (float)(getWidth()/2.0+(x-cx)*zoom);}
    private float sy(double y){return (float)(getHeight()/2.0+(y-cy)*zoom);}
    private double wx(float x){return cx+(x-getWidth()/2.0)/zoom;}
    private double wy(float y){return cy+(y-getHeight()/2.0)/zoom;}
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
    private static double clamp(double value,double min,double max){return Math.max(min,Math.min(max,value));}
}
