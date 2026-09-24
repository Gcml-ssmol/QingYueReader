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
    public static final int LASSO_HANDWRITING=NoteEditorOps.HANDWRITING,LASSO_GRAPHICS=NoteEditorOps.GRAPHICS,
            LASSO_TEXT=NoteEditorOps.TEXT,LASSO_IMAGE=NoteEditorOps.IMAGE,LASSO_ALL=NoteEditorOps.ALL;
    public interface Host {void changed();void error(String message);default void readingScrolled(){} default void pageAdded(){} default void pageChanged(){} default void viewportPageChanged(int page){} default void viewportSettled(){}}
    private static final ExecutorService imageWorker=Executors.newSingleThreadExecutor();
    private final NoteDocument document;
    private final String noteId;
    private final Host host;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final InkStrokeRenderer.Cache strokeCache=new InkStrokeRenderer.Cache();
    private final TwoFingerTap twoFinger;
    private final Set<String> loading=new HashSet<>(),failedImages=new HashSet<>();
    private final LruCache<String,Bitmap> bitmaps=new LruCache<String,Bitmap>(20*1024*1024){@Override protected int sizeOf(String k,Bitmap b){return b.getAllocationByteCount();}};
    private String tool="pen";
    private int color=0xff36302b;
    private float width=2;
    private boolean stylusOnly=true,twoFingerEnabled=true,disposed,initialized,readOnly;
    private double cx,cy,zoom=1,baseZoom=1;
    private final ZoomBadge zoomBadge=new ZoomBadge();
    private long selected;
    private int active=-1,actionMode; // 1 ink/shape, 2 eraser, 3 pan, 4 image drag, 5 image resize, 6 lasso, 7 group move, 8 group scale
    private boolean activePen,multi,panStarted;
    private final TouchNavigation.Guard navigationGuard=new TouchNavigation.Guard();
    private final TouchNavigation.Pinch pinch;
    private final TouchNavigation.PageDrag pageDrag=new TouchNavigation.PageDrag();
    private double startX,startY,lastWorldX,lastWorldY;
    private float lastX,lastY;
    private float panTravel;
    private boolean scrollNotified;
    private boolean viewportPageDirty;
    private boolean horizontalPages;
    private final NotePageLayout pageLayout;
    private int renderPage, gesturePage=-1;
    private float gestureStartX,gestureStartY;
    private long gestureStartedAt;
    private int resizeCorner;
    private NoteDocument.Item imageOriginal,imagePreview;
    private final ArrayList<double[]> vertices=new ArrayList<>();
    private List<NoteDocument.Item> erasePreview;
    private boolean eraseFailed;
    private NoteDocument.Item preview;
    private final LinkedHashSet<Long> selection=new LinkedHashSet<>();
    private final ArrayList<double[]> lassoPath=new ArrayList<>();
    private final LinkedHashMap<Long,NoteDocument.Item> groupOriginal=new LinkedHashMap<>(),groupPreview=new LinkedHashMap<>();
    private final ArrayList<NoteDocument.Item> clipboard=new ArrayList<>();
    private boolean lassoFreeform=true,wholeStrokeEraser;
    private int lassoFilter=LASSO_ALL;
    private double[] groupBounds;

    public NoteCanvasView(Context context,NoteDocument document,String noteId,Host host){super(context);this.document=document;this.noteId=noteId;this.host=host;pageLayout=document.infinite()?null:new NotePageLayout(document.width,document.height,24);twoFinger=new TwoFingerTap(dp(7));pinch=new TouchNavigation.Pinch(getResources().getDisplayMetrics().density);setFocusable(true);setContentDescription("笔记画布，单指拖动画布，双指缩放；选择图片后可拖动与缩放");setLayerType(View.LAYER_TYPE_HARDWARE,null);}
    public void setTool(String tool,int color,float widthPt,boolean stylusOnly){finishGesture();this.tool=tool;this.color=color;this.width=Math.max(.2f,Math.min(100,widthPt));this.stylusOnly=stylusOnly;if(!"select".equals(tool)){selected=0;imagePreview=null;}if(!"lasso".equals(tool))selection.clear();invalidate();}
    public void setLassoFreeform(boolean freeform){finishGesture();lassoFreeform=freeform;invalidate();}
    public void setLassoFilter(int filter){finishGesture();lassoFilter=filter&LASSO_ALL;selection.clear();invalidate();}
    public void setWholeStrokeEraser(boolean whole){finishGesture();wholeStrokeEraser=whole;}
    public int selectionCount(){return selection.size();}
    public boolean hasClipboard(){return !clipboard.isEmpty();}
    public void setTwoFingerUndoEnabled(boolean enabled){twoFingerEnabled=enabled;twoFinger.reset();}
    public void setReadOnly(boolean enabled){if(readOnly==enabled)return;cancelGesture();readOnly=enabled;selected=0;selection.clear();invalidate();}
    private boolean continuous(){return pageLayout!=null&&!horizontalPages;}
    public void setHorizontalPages(boolean horizontal){if(horizontalPages==horizontal)return;finishGesture();syncViewport();if(initialized&&pageLayout!=null)cy+=horizontal?-pageLayout.top(document.currentPage()):pageLayout.top(document.currentPage());horizontalPages=horizontal;baseZoom=fittedScale();constrainViewport();syncViewport();invalidate();}
    public int visiblePage(){return continuous()&&initialized?pageLayout.nearest(cy,document.pageCount()):document.currentPage();}
    public void selectPage(int index){finishGesture();syncViewport();document.selectPage(index);selected=0;selection.clear();if(continuous()&&initialized){cy=pageLayout.top(index)+Math.min(document.height/2,getHeight()/(2*zoom));constrainViewport();syncViewport();}else{initialized=false;onSizeChanged(getWidth(),getHeight(),getWidth(),getHeight());}persistPageChange();}
    public void addPage(){if(readOnly)return;finishGesture();syncViewport();try{document.addPage();selected=0;selection.clear();focusNewPage();persistPageChange();host.pageAdded();}catch(IllegalStateException error){host.error(error.getMessage());}}
    public void insertPage(int index){if(disposed||readOnly)return;finishGesture();syncViewport();try{document.insertPage(index);selected=0;selection.clear();focusNewPage();persistPageChange();host.pageAdded();}catch(IllegalStateException error){host.error(error.getMessage());}}
    private void focusNewPage(){if(continuous()&&initialized){cy=pageLayout.top(document.currentPage())+Math.min(document.height/2,getHeight()/(2*zoom));constrainViewport();syncViewport();invalidate();}else{initialized=false;onSizeChanged(getWidth(),getHeight(),getWidth(),getHeight());}}
    public void movePage(int from,int to){if(readOnly)return;finishGesture();syncViewport();int before=document.currentPage();document.movePage(from,to);if(continuous()&&initialized)cy+=(document.currentPage()-before)*pageLayout.stride();persistPageChange();}
    private void persistPageChange(){if(save())host.pageChanged();host.changed();invalidate();}
    public float previewDiameter(float widthPt){return (float)(widthPt*zoom);}
    public boolean canUndo(){return !readOnly&&document.canUndo();}
    public boolean canRedo(){return !readOnly&&document.canRedo();}
    public boolean hasSelection(){return selected!=0&&document.find(selected)!=null||!selection.isEmpty();}
    public NoteDocument.Item selectedText(){NoteDocument.Item item=document.find(selected);return item!=null&&item.type==NoteDocument.TEXT?item:null;}
    public void putText(String value,float size,int ink){
        if(readOnly)return;finishGesture();if(value==null||value.trim().isEmpty()){host.error("请输入文字");return;}
        NoteDocument.Item old=selectedText();double w=old==null?Math.min(300,Math.max(120,getWidth()*.55/zoom)):old.right()-old.left();
        double h=NoteTextLayout.height(value,size,ink,w),x=old==null?cx-w/2:old.left(),y=old==null?localCenterY()-h/2:old.top();
        if(!document.infinite()){x=clamp(x,0,Math.max(0,document.width-w));y=clamp(y,0,Math.max(0,document.height-h));}
        try{NoteDocument.Item item=new NoteDocument.Item(old==null?document.newId():old.id,NoteDocument.TEXT,ink,size,new double[]{x,y,x+w,y+h},old==null?"":old.image,value);if(old==null)document.add(item);else document.replace(item);selected=item.id;tool="select";changed();}catch(IllegalArgumentException e){host.error(e.getMessage());}
    }
    public long insertSource(ReaderSourceLink source){
        if(readOnly)return 0;finishGesture();if(disposed)return 0;
        String text=NoteSourceActions.body(source);double w=document.infinite()?360:Math.min(360,document.width-48);
        double h=NoteTextLayout.height(text,17,0xff36302b,w),x=cx-w/2,y=localCenterY()-h/2;
        if(!document.infinite()&&h>document.height-48){host.error("摘录超出当前纸张，请改用无限画布笔记");return 0;}
        if(!document.infinite()){x=clamp(x,0,document.width-w);y=clamp(y,0,document.height-h);}
        try{NoteDocument.Item item=new NoteDocument.Item(document.newId(),NoteDocument.TEXT,0xff36302b,17,new double[]{x,y,x+w,y+h},source.toUri(),text);document.add(item);selected=item.id;tool="select";changed();return item.id;}
        catch(IllegalArgumentException failure){host.error(failure.getMessage());return 0;}
    }
    public int zoomPercent(){return (int)Math.round(zoom/baseZoom*100);}
    public void setZoomPercent(int percent){finishGesture();int max=document.infinite()?1000:300;double target=baseZoom*Math.max(50,Math.min(max,percent))/100.0;zoom=document.infinite()?ZoomLimits.noteInfiniteScale(target,baseZoom):ZoomLimits.noteScale(target,baseZoom);constrainViewport();syncViewport();invalidate();}
    public void deleteSelected(){if(readOnly)return;finishGesture();if(!selection.isEmpty()){deleteSelection();return;}if(hasSelection()){document.remove(selected);selected=0;changed();}}
    public void copySelection(){finishGesture();Set<Long> ids=selectedIds();if(ids.isEmpty())return;clipboard.clear();for(NoteDocument.Item item:document.items())if(ids.contains(item.id))clipboard.add(item);}
    public void cutSelection(){if(readOnly)return;copySelection();deleteSelection();}
    public void pasteSelection(){
        if(readOnly)return;finishGesture();if(clipboard.isEmpty())return;
        for(NoteDocument.Item item:clipboard)if(item.type==NoteDocument.IMAGE&&!NoteStore.imageExists(getContext(),noteId,item.image)){host.error("原图片已丢失，无法粘贴");return;}
        double l=Double.POSITIVE_INFINITY,t=l,r=Double.NEGATIVE_INFINITY,b=r;
        for(NoteDocument.Item item:clipboard){l=Math.min(l,item.left());t=Math.min(t,item.top());r=Math.max(r,item.right());b=Math.max(b,item.bottom());}
        double dx=cx-(l+r)/2,dy=localCenterY()-(t+b)/2;
        if(!document.infinite()){dx=clamp(dx,-l,document.width-r);dy=clamp(dy,-t,document.height-b);}
        ArrayList<NoteDocument.Item> next=new ArrayList<>(document.items());LinkedHashSet<Long> pasted=new LinkedHashSet<>();
        try{for(NoteDocument.Item item:clipboard){NoteDocument.Item moved=NoteEditorOps.translated(item,dx,dy);long id=document.newId();next.add(new NoteDocument.Item(id,moved.type,moved.color,moved.width,moved.points,moved.image,moved.text));pasted.add(id);}document.applyItems(next);selection.clear();selection.addAll(pasted);selected=0;tool="lasso";changed();}
        catch(IllegalArgumentException error){host.error(error.getMessage());}
    }
    public void deleteSelection(){if(readOnly)return;finishGesture();Set<Long> ids=selectedIds();if(ids.isEmpty())return;ArrayList<NoteDocument.Item> next=new ArrayList<>();for(NoteDocument.Item item:document.items())if(!ids.contains(item.id))next.add(item);document.applyItems(next);selection.clear();selected=0;changed();}
    public void recolorSelection(int rgb){if(readOnly)return;finishGesture();Set<Long> ids=selectedIds();if(ids.isEmpty())return;ArrayList<NoteDocument.Item> next=new ArrayList<>();for(NoteDocument.Item item:document.items())next.add(ids.contains(item.id)?NoteEditorOps.recolored(item,rgb):item);if(!next.equals(document.items())){document.applyItems(next);changed();}}
    /** Moves selected objects to the top of the page's drawing order. */
    public void bringSelectionForward(){reorderSelection(true);}
    /** Moves selected objects to the bottom of the page's drawing order. */
    public void sendSelectionBackward(){reorderSelection(false);}
    private void reorderSelection(boolean top){if(readOnly)return;finishGesture();Set<Long> ids=selectedIds();if(ids.isEmpty())return;ArrayList<NoteDocument.Item> chosen=new ArrayList<>(),other=new ArrayList<>();for(NoteDocument.Item item:document.items())(ids.contains(item.id)?chosen:other).add(item);ArrayList<NoteDocument.Item> next=new ArrayList<>(document.items().size());next.addAll(top?other:chosen);next.addAll(top?chosen:other);if(!next.equals(document.items())){document.applyItems(next);changed();}}
    public void scaleSelection(float factor){if(readOnly)return;finishGesture();Set<Long> ids=selectedIds();if(ids.isEmpty()||Float.isNaN(factor)||Float.isInfinite(factor)||factor<=0)return;double[] bounds=NoteEditorOps.bounds(document.items(),ids);if(bounds==null)return;double x=(bounds[0]+bounds[2])/2,y=(bounds[1]+bounds[3])/2;applySelectionScale(ids,x,y,Math.max(.25,Math.min(4,factor)));}
    private void applySelectionScale(Set<Long> ids,double x,double y,double factor){
        if(!document.infinite()){double[] box=NoteEditorOps.bounds(document.items(),ids);if(box==null)return;double max=Double.POSITIVE_INFINITY;for(double v:new double[]{box[0],document.width-box[2],box[1],document.height-box[3]})if(v<0)max=Math.min(max,1);if(factor>1){double halfW=Math.max(x-box[0],box[2]-x),halfH=Math.max(y-box[1],box[3]-y);max=Math.min((Math.min(x,document.width-x))/Math.max(.001,halfW),(Math.min(y,document.height-y))/Math.max(.001,halfH));factor=Math.min(factor,max);} }
        if(factor<=0||Math.abs(factor-1)<.000001)return;ArrayList<NoteDocument.Item> next=new ArrayList<>();try{for(NoteDocument.Item item:document.items())next.add(ids.contains(item.id)?NoteEditorOps.scaled(item,x,y,factor):item);document.applyItems(next);changed();}catch(IllegalArgumentException error){host.error(error.getMessage());}
    }
    private Set<Long> selectedIds(){if(!selection.isEmpty())return new LinkedHashSet<>(selection);if(selected!=0&&document.find(selected)!=null)return Collections.singleton(selected);return Collections.emptySet();}
    public void duplicatePage(){if(readOnly)return;finishGesture();syncViewport();try{document.duplicatePage();selected=0;selection.clear();focusNewPage();persistPageChange();host.pageAdded();}catch(IllegalStateException|IllegalArgumentException error){host.error(error.getMessage());}}
    public void clearPage(){if(readOnly)return;finishGesture();if(document.items().isEmpty())return;document.clearPage();selected=0;selection.clear();changed();}
    public void deletePage(){if(readOnly)return;finishGesture();syncViewport();try{document.deletePage();selected=0;selection.clear();focusNewPage();persistPageChange();}catch(IllegalStateException error){host.error(error.getMessage());}}
    public void resetOrigin(){finishGesture();if(!document.infinite()){fit();return;}cx=cy=0;syncViewport();invalidate();}
    public void undo(){if(readOnly)return;cancelGesture();if(document.undo()){selected=0;selection.clear();changed();}}
    public void redo(){if(readOnly)return;cancelGesture();if(document.redo()){selected=0;selection.clear();changed();}}
    public NoteDocument snapshot(){syncViewport();return document.copy();}
    public NoteDocument document(){return document;}
    public boolean save(){if(disposed)return false;finishGesture();syncViewport();try{NoteStore.save(getContext(),noteId,document);return true;}catch(IOException|RuntimeException e){host.error("笔记保存失败："+e.getMessage());return false;}}
    public void dispose(){if(disposed)return;save();disposed=true;bitmaps.evictAll();loading.clear();strokeCache.clear();}
    private void changed(){if(disposed)return;syncViewport();NoteStore.saveAsync(getContext(),noteId,document,message->post(()->{if(!disposed)host.error(message);}));host.changed();invalidate();}
    private double localCenterY(){return cy-(continuous()?pageLayout.top(document.currentPage()):0);}
    private void syncViewport(){if(initialized){document.centerX=cx;document.centerY=continuous()?clamp(localCenterY(),0,document.height):cy;document.zoom=zoom;document.viewportSaved=true;}}
    private double fittedScale(){return ZoomLimits.noteBase(document.infinite()?getWidth()/700.0:continuous()?Math.max(1,getWidth()-dp(24))/document.width:Math.min(Math.max(1,getWidth()-dp(32))/document.width,Math.max(1,getHeight()-dp(32))/document.height));}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        super.onSizeChanged(w,h,oldw,oldh);if(w<=0||h<=0)return;
        baseZoom=fittedScale();
        if(!initialized){initialized=true;if(document.viewportSaved){cx=document.centerX;cy=(continuous()?clamp(document.centerY,0,document.height)+pageLayout.top(document.currentPage()):document.centerY);zoom=clamp(document.zoom,.025,32);}else fit();}
        // A sidebar resize retains the absolute ink scale and the viewport anchor.
        constrainViewport();syncViewport();invalidate();
    }
    public void fit(){finishGesture();if(getWidth()==0||getHeight()==0){document.viewportSaved=false;initialized=false;return;}baseZoom=fittedScale();zoom=baseZoom;cx=document.infinite()?0:document.width/2;cy=document.infinite()?0:(continuous()?pageLayout.top(document.currentPage())+Math.min(document.height/2,getHeight()/(2*zoom)):document.height/2);constrainViewport();syncViewport();invalidate();}
    public boolean insertImage(String name,int pixelWidth,int pixelHeight){if(readOnly)return false;finishGesture();if(disposed||getWidth()<=0||getHeight()<=0||pixelWidth<=0||pixelHeight<=0){host.error("画布尚未准备好，请稍后再插入图片");return false;}double w=Math.min(360,Math.max(80,getWidth()*.65/zoom)),h=w*pixelHeight/(double)pixelWidth;if(h>getHeight()*.7/zoom){h=getHeight()*.7/zoom;w=h*pixelWidth/(double)pixelHeight;}if(!document.infinite()){double scale=Math.min(1,Math.min(document.width*.85/w,document.height*.85/h));w*=scale;h*=scale;}double x=cx-w/2,y=localCenterY()-h/2;if(!document.infinite()){x=clamp(x,0,document.width-w);y=clamp(y,0,document.height-h);}try{NoteDocument.Item item=new NoteDocument.Item(document.newId(),NoteDocument.IMAGE,Color.WHITE,1,new double[]{x,y,x+w,y+h},name);document.add(item);selected=item.id;tool="select";changed();return true;}catch(IllegalArgumentException e){host.error(e.getMessage());return false;}}

    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);strokeCache.beginFrame();canvas.drawColor(AppColors.CANVAS);if(!initialized)return;
        if(continuous()){
            int first=pageLayout.firstVisible(wyGlobal(0),document.pageCount());
            int last=pageLayout.lastVisible(wyGlobal(getHeight()),document.pageCount());
            for(int page=first;page<=last;page++)drawPage(canvas,page);
        }else drawPage(canvas,document.currentPage());
        renderPage=document.currentPage();
        NoteDocument.Item selection=imagePreview!=null?imagePreview:document.find(selected);if("select".equals(tool)&&selection!=null&&(selection.type==NoteDocument.IMAGE||selection.type==NoteDocument.TEXT))drawSelection(canvas,selection);
        if("lasso".equals(tool)){drawGroupSelection(canvas);drawLassoPath(canvas);}
        if(actionMode==2){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.5f));paint.setColor(AppColors.ACCENT);canvas.drawCircle(sx(lastWorldX),sy(lastWorldY),(float)(width*zoom*.5),paint);}
        zoomBadge.draw(canvas,getWidth(),getResources().getDisplayMetrics().density,zoom/baseZoom);
    }
    private void drawPage(Canvas canvas,int page){
        renderPage=page;
        int clip=canvas.save();
        if(document.infinite())canvas.drawColor(AppColors.PAPER);
        else{float l=sx(0),t=sy(0),r=sx(document.width),b=sy(document.height);paint.setColor(0x17000000);paint.setStyle(Paint.Style.FILL);canvas.drawRect(l-dp(2),t+dp(3),r+dp(2),b+dp(5),paint);paint.setColor(AppColors.PAPER);canvas.drawRect(l,t,r,b,paint);canvas.clipRect(l,t,r,b);}
        drawPaper(canvas);
        double vl=wx(0),vt=wyGlobal(0)-pageOffset(page),vr=wx(getWidth()),vb=wyGlobal(getHeight())-pageOffset(page);
        List<NoteDocument.Item> items=page==document.currentPage()&&erasePreview!=null?erasePreview:document.pageItems(page);
        for(NoteDocument.Item item:items){NoteDocument.Item shown=page==document.currentPage()?groupPreview.get(item.id):null;if(shown==null)shown=page==document.currentPage()&&imagePreview!=null&&item.id==imagePreview.id?imagePreview:item;double extra=shown.width*2+20;if(shown.right()+extra<vl||shown.left()-extra>vr||shown.bottom()+extra<vt||shown.top()-extra>vb)continue;if(shown.type==NoteDocument.TEXT)drawText(canvas,shown);else drawItem(canvas,shown);}
        if(page==document.currentPage()&&preview!=null)drawItem(canvas,preview);
        canvas.restoreToCount(clip);
    }
    private void drawPaper(Canvas canvas){if("blank".equals(document.template))return;double spacing="grid".equals(document.template)?20:28;while(spacing*zoom<dp(8))spacing*=2;double left=wx(0),top=wyGlobal(0)-pageOffset(renderPage),right=wx(getWidth()),bottom=wyGlobal(getHeight())-pageOffset(renderPage);if(!document.infinite()){left=Math.max(0,left);top=Math.max(0,top);right=Math.min(document.width,right);bottom=Math.min(document.height,bottom);}paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(.7f));paint.setColor("grid".equals(document.template)?0xffe8ded1:0xffe2d5c5);double y=Math.ceil(top/spacing)*spacing;for(int n=0;n<1500&&y<=bottom;n++,y+=spacing)canvas.drawLine(sx(left),sy(y),sx(right),sy(y),paint);if("grid".equals(document.template)){double x=Math.ceil(left/spacing)*spacing;for(int n=0;n<1500&&x<=right;n++,x+=spacing)canvas.drawLine(sx(x),sy(top),sx(x),sy(bottom),paint);}}
    private void drawItem(Canvas canvas,NoteDocument.Item item){
        double[] p=item.points;if(item.type==NoteDocument.IMAGE){drawImage(canvas,item);return;}
        if(item.type==NoteDocument.STROKE){
            if(p.length==2){paint.setColor(item.color);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(sx(p[0]),sy(p[1]),Math.max(.3f,(float)(item.width*zoom/2)),paint);return;}
            // Huge spans cannot safely cancel float translations: preserve the original screen-space path.
            if(Math.max(Math.abs(p[0]-cx),Math.abs(p[1]-renderCenterY()))*zoom>1048576||Math.max(item.right()-item.left(),item.bottom()-item.top())*zoom>1048576){
                Path path=new Path();path.moveTo(sx(p[0]),sy(p[1]));for(int i=2;i<p.length;i+=2)path.lineTo(sx(p[i]),sy(p[i+1]));paint.setColor(item.color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth((float)(item.width*zoom));paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);canvas.drawPath(path,paint);return;}
            int saved=canvas.save();canvas.translate(sx(p[0]),sy(p[1]));canvas.scale((float)zoom,(float)zoom);
            strokeCache.drawNote(canvas,paint,item==preview?null:item,p,item.color,item.width);canvas.restoreToCount(saved);return;}
        paint.setStyle(Paint.Style.STROKE);paint.setColor(item.color);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeWidth((float)(item.width*zoom));
        if(item.type==NoteDocument.RECTANGLE)canvas.drawRect(sx(item.left()),sy(item.top()),sx(item.right()),sy(item.bottom()),paint);
        else if(item.type==NoteDocument.CIRCLE)canvas.drawOval(sx(item.left()),sy(item.top()),sx(item.right()),sy(item.bottom()),paint);
        else{canvas.drawLine(sx(p[0]),sy(p[1]),sx(p[2]),sy(p[3]),paint);if(item.type==NoteDocument.ARROW){double[] a=NoteDocument.arrowHeads(p,item.width);canvas.drawLine(sx(p[2]),sy(p[3]),sx(a[0]),sy(a[1]),paint);canvas.drawLine(sx(p[2]),sy(p[3]),sx(a[2]),sy(a[3]),paint);}}
    }
    private void drawImage(Canvas canvas,NoteDocument.Item item){RectF rect=new RectF(sx(item.left()),sy(item.top()),sx(item.right()),sy(item.bottom()));Bitmap bitmap=bitmaps.get(item.image);paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);paint.setAlpha(255);if(bitmap!=null&&!bitmap.isRecycled()){canvas.drawBitmap(bitmap,null,rect,paint);return;}paint.setColor(AppColors.SOFT);canvas.drawRect(rect,paint);paint.setColor(AppColors.MUTED);paint.setTextSize(dp(13));canvas.drawText(failedImages.contains(item.image)?"图片无法读取":"加载图片…",rect.left+dp(10),rect.top+dp(24),paint);if(!loading.contains(item.image)&&!failedImages.contains(item.image))loadImage(item.image);}
    private void drawText(Canvas canvas,NoteDocument.Item item){int save=canvas.save();canvas.translate(sx(item.left()),sy(item.top()));canvas.scale((float)zoom,(float)zoom);canvas.translate((float)-item.left(),(float)-item.top());NoteTextLayout.draw(canvas,item);canvas.restoreToCount(save);}
    private void loadImage(String name){loading.add(name);final Context app=getContext().getApplicationContext();imageWorker.execute(()->{Bitmap result=null;try{File file=new File(NoteStore.imagesDirectory(app,noteId),name);BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),options);if(options.outWidth>0&&options.outHeight>0){options.inSampleSize=1;while(options.outWidth/options.inSampleSize>2048||options.outHeight/options.inSampleSize>2048)options.inSampleSize*=2;options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;result=BitmapFactory.decodeFile(file.getAbsolutePath(),options);}}catch(IOException|RuntimeException|OutOfMemoryError ignored){}final Bitmap bitmap=result;post(()->{loading.remove(name);if(disposed){if(bitmap!=null)bitmap.recycle();return;}if(bitmap==null)failedImages.add(name);else bitmaps.put(name,bitmap);invalidate();});});}
    private void drawSelection(Canvas canvas,NoteDocument.Item item){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.5f));paint.setColor(AppColors.ACCENT);canvas.drawRect(sx(item.left()),sy(item.top()),sx(item.right()),sy(item.bottom()),paint);for(int i=0;i<4;i++){float x=sx(i%2==0?item.left():item.right()),y=sy(i<2?item.top():item.bottom());paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);canvas.drawCircle(x,y,dp(6),paint);paint.setStyle(Paint.Style.STROKE);paint.setColor(AppColors.ACCENT);canvas.drawCircle(x,y,dp(6),paint);}}
    private void drawGroupSelection(Canvas canvas){double[] box=groupPreview.isEmpty()?NoteEditorOps.bounds(document.items(),selection):NoteEditorOps.bounds(new ArrayList<>(groupPreview.values()),selection);if(box==null)return;paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.5f));paint.setColor(AppColors.ACCENT);canvas.drawRect(sx(box[0]),sy(box[1]),sx(box[2]),sy(box[3]),paint);paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);canvas.drawCircle(sx(box[2]),sy(box[3]),dp(7),paint);paint.setStyle(Paint.Style.STROKE);paint.setColor(AppColors.ACCENT);canvas.drawCircle(sx(box[2]),sy(box[3]),dp(7),paint);}
    private void drawLassoPath(Canvas canvas){if(actionMode!=6||lassoPath.isEmpty())return;paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.5f));paint.setColor(AppColors.ACCENT);Path path=new Path();double[] first=lassoPath.get(0);path.moveTo(sx(first[0]),sy(first[1]));if(lassoFreeform){for(int i=1;i<lassoPath.size();i++){double[] p=lassoPath.get(i);path.lineTo(sx(p[0]),sy(p[1]));}}else{double[] last=lassoPath.get(lassoPath.size()-1);path.addRect(Math.min(sx(first[0]),sx(last[0])),Math.min(sy(first[1]),sy(last[1])),Math.max(sx(first[0]),sx(last[0])),Math.max(sy(first[1]),sy(last[1])),Path.Direction.CW);}canvas.drawPath(path,paint);}

    @Override public boolean onTouchEvent(MotionEvent event){
        if(disposed)return false;
        int action=event.getActionMasked(),changed=event.getPointerId(event.getActionIndex());
        if(action==MotionEvent.ACTION_DOWN&&getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
        boolean hasPen=false,safe=event.getPointerCount()<=2;
        for(int i=0;i<event.getPointerCount();i++){
            boolean pen=isPen(event.getToolType(i));hasPen|=pen;
            if(!pen&&!TouchNavigation.safeContact(event.getToolType(i),event.getTouchMajor(i),event.getSize(i),getResources().getDisplayMetrics().density))safe=false;
        }
        boolean touchAllowed=navigationGuard.allow(action,event.getEventTime(),hasPen,safe);
        if(!touchAllowed){twoFinger.reject();pinch.reset();if(!hasPen&&!activePen){cancelGesture();multi=false;return true;}}
        boolean undoTap=false;
        if(touchAllowed&&twoFingerEnabled){int n=event.getPointerCount();int[] ids=new int[n],tools=new int[n];float[] xs=new float[n],ys=new float[n];for(int i=0;i<n;i++){ids[i]=event.getPointerId(i);tools[i]=event.getToolType(i);xs[i]=event.getX(i);ys[i]=event.getY(i);}undoTap=twoFinger.event(action,changed,event.getEventTime(),ids,tools,xs,ys);}
        if(undoTap){cancelGesture();undo();return true;}
        if(action==MotionEvent.ACTION_CANCEL){cancelGesture();pinch.reset();multi=false;return true;}
        if(action==MotionEvent.ACTION_DOWN){multi=false;pinch.reset();pageDrag.reset();gestureStartedAt=event.getEventTime();gestureStartX=event.getX();gestureStartY=event.getY();int i=event.getActionIndex();active=changed;activePen=isPen(event.getToolType(i));begin(event.getX(i),event.getY(i),event.getToolType(i));return true;}
        // A pen owns every event in its sequence. Its palm never becomes a pinch pointer.
        if(activePen&&active>=0){int i=event.findPointerIndex(active);if(i<0){cancelGesture();return true;}if(action==MotionEvent.ACTION_MOVE)moveSamples(event,i);else if((action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP)&&changed==active){move(event.getX(i),event.getY(i));finishGesture();multi=true;}return true;}
        if(action==MotionEvent.ACTION_POINTER_DOWN){
            int stylus=-1;for(int i=0;i<event.getPointerCount();i++)if(isPen(event.getToolType(i))){stylus=i;break;}
            cancelGesture();pageDrag.reset();
            if(stylus>=0){active=event.getPointerId(stylus);activePen=true;gestureStartX=event.getX(stylus);gestureStartY=event.getY(stylus);gestureStartedAt=event.getEventTime();begin(event.getX(stylus),event.getY(stylus),event.getToolType(stylus));return true;}
            multi=true;setPinch(event);return true;
        }
        if(multi){
            if(action==MotionEvent.ACTION_MOVE&&event.getPointerCount()==2){
                if(pinch.move(event.getPointerId(0),event.getPointerId(1),event.getX(0),event.getY(0),event.getX(1),event.getY(1),twoFinger.pairPending())){
                    double anchorX=wx(pinch.oldX),anchorY=wyGlobal(pinch.oldY);zoom=document.infinite()?ZoomLimits.noteInfiniteScale(zoom*pinch.scale,baseZoom):ZoomLimits.noteScale(zoom*pinch.scale,baseZoom);
                    cx=anchorX-(pinch.x-getWidth()/2.0)/zoom;cy=anchorY-(pinch.y-getHeight()/2.0)/zoom;constrainViewport();updateCenteredPage();notifyScroll();invalidate();
                }
            }else if(action==MotionEvent.ACTION_POINTER_UP)pinch.reset();
            else if(action==MotionEvent.ACTION_UP){multi=false;syncViewport();settleViewport();if(scrollNotified){scrollNotified=false;host.readingScrolled();}}
            return true;
        }
        if(active<0)return true;int i=event.findPointerIndex(active);if(i<0){cancelGesture();return true;}
        if(action==MotionEvent.ACTION_MOVE)moveSamples(event,i);
        else if(action==MotionEvent.ACTION_UP){
            move(event.getX(i),event.getY(i));
            int turn=actionMode==3&&!document.infinite()&&!continuous()?pageTurn(event.getX(i),event.getY(i)):0;
            float dx=event.getX(i)-gestureStartX,dy=event.getY(i)-gestureStartY;
            boolean add=!readOnly&&!continuous()&&actionMode==3&&pageDrag.canAddPage("hand".equals(tool),activePen,zoom/baseZoom,event.getEventTime()-gestureStartedAt,horizontalPages?dx:dy,horizontalPages?dy:dx,dp(90));
            boolean scrolled=scrollNotified;finishGesture();
            int target=document.currentPage()+turn;
            if(turn!=0&&target>=0&&target<document.pageCount())selectPage(target);
            else if(turn>0&&target==document.pageCount()&&add)addPage();
            // Page creation requires the hand tool, fit scale, and a deliberate extended edge pull.
            if(scrolled){scrollNotified=false;host.readingScrolled();}performClick();
        }
        return true;
    }
    @Override public boolean onHoverEvent(MotionEvent event){
        if(isPen(event.getToolType(0))){navigationGuard.pen(event.getEventTime());pinch.reset();twoFinger.reject();if(!activePen){cancelGesture();multi=false;}}
        return super.onHoverEvent(event);
    }
    @Override public boolean performClick(){super.performClick();return true;}
    private void setPinch(MotionEvent event){if(event.getPointerCount()!=2){pinch.reset();return;}pinch.begin(event.getPointerId(0),event.getPointerId(1),event.getX(0),event.getY(0),event.getX(1),event.getY(1));}
    private void begin(float x,float y,int eventTool){
        if(readOnly){gesturePage=document.currentPage();lastX=x;lastY=y;startX=lastWorldX=wx(x);startY=lastWorldY=wy(y);panTravel=0;scrollNotified=false;panStarted=false;actionMode=3;return;}
        boolean editing="select".equals(tool)||"lasso".equals(tool)||(!"hand".equals(tool)&&(!stylusOnly||isPen(eventTool)));
        gesturePage=continuous()&&editing?pageLayout.hit(wx(x),wyGlobal(y),document.pageCount()):document.currentPage();
        if(editing&&gesturePage>=0&&gesturePage!=document.currentPage()){syncViewport();document.selectPage(gesturePage);selected=0;selection.clear();viewportPageDirty=true;host.viewportPageChanged(gesturePage);}
        renderPage=document.currentPage();
        lastX=x;lastY=y;startX=lastWorldX=wx(x);startY=lastWorldY=wy(y);panTravel=0;scrollNotified=false;panStarted=false;vertices.clear();erasePreview=null;eraseFailed=false;preview=null;imageOriginal=null;imagePreview=null;lassoPath.clear();groupOriginal.clear();groupPreview.clear();
        if("lasso".equals(tool)){
            if(gesturePage<0||!onPaper(startX,startY)){actionMode=0;return;}
            groupBounds=NoteEditorOps.bounds(document.items(),selection);
            if(groupBounds!=null){
                if(Math.hypot(x-sx(groupBounds[2]),y-sy(groupBounds[3]))<=dp(20))actionMode=8;
                else if(startX>=groupBounds[0]&&startX<=groupBounds[2]&&startY>=groupBounds[1]&&startY<=groupBounds[3])actionMode=7;
                if(actionMode==7||actionMode==8){for(NoteDocument.Item item:document.items())if(selection.contains(item.id))groupOriginal.put(item.id,item);return;}
            }
            selection.clear();selected=0;actionMode=6;lassoPath.add(new double[]{startX,startY});invalidate();return;
        }
        if("select".equals(tool)){NoteDocument.Item chosen=document.find(selected);int handle=chosen==null?-1:handleAt(chosen,x,y);if(handle>=0){actionMode=5;resizeCorner=handle;imageOriginal=chosen;imagePreview=chosen;return;}chosen=imageAt(wx(x),wy(y));selected=chosen==null?0:chosen.id;host.changed();if(chosen!=null){actionMode=4;imageOriginal=chosen;imagePreview=chosen;}else actionMode=3;invalidate();return;}
        boolean draw=(!stylusOnly||isPen(eventTool))&&!"hand".equals(tool);if(!draw){actionMode=3;return;}if(gesturePage<0||!onPaper(startX,startY)){actionMode=0;return;}if("eraser".equals(tool)||eventTool==MotionEvent.TOOL_TYPE_ERASER){actionMode=2;eraseAt(startX,startY);invalidate();return;}actionMode=1;vertices.add(new double[]{startX,startY});updatePreview(startX,startY);invalidate();}
    private void moveSamples(MotionEvent event,int index){for(int h=0;h<event.getHistorySize();h++)move(event.getHistoricalX(index,h),event.getHistoricalY(index,h));move(event.getX(index),event.getY(index));}
    private void move(float x,float y){double worldX=wx(x),worldY=wy(y);if(actionMode==3){
            if(!panStarted){if(Math.hypot(x-gestureStartX,y-gestureStartY)>dp(7))panStarted=true;lastX=x;lastY=y;lastWorldX=worldX;lastWorldY=worldY;return;}
            double oldX=cx,oldY=cy;float dx=x-lastX,dy=y-lastY;cx-=dx/zoom;cy-=dy/zoom;constrainViewport();
            if(!continuous())pageDrag.add(horizontalPages?dx:dy,(float)(-(horizontalPages?cx-oldX:cy-oldY)*zoom));
            updateCenteredPage();
            panTravel+=Math.hypot(dx,dy);if(panTravel>dp(12))notifyScroll();}else if(actionMode==1){if(!document.infinite()){worldX=clamp(worldX,0,document.width);worldY=clamp(worldY,0,document.height);}if(vertices.size()<50000&&Math.hypot(worldX-lastWorldX,worldY-lastWorldY)*zoom>=.7)vertices.add(new double[]{worldX,worldY});updatePreview(worldX,worldY);}else if(actionMode==2){eraseSweep(lastWorldX,lastWorldY,worldX,worldY);}else if(actionMode==4&&imageOriginal!=null){double w=imageOriginal.right()-imageOriginal.left(),h=imageOriginal.bottom()-imageOriginal.top(),nx=imageOriginal.left()+worldX-startX,ny=imageOriginal.top()+worldY-startY;if(!document.infinite()){nx=clamp(nx,0,Math.max(0,document.width-w));ny=clamp(ny,0,Math.max(0,document.height-h));}imagePreview=imageOriginal.bounds(nx,ny,w,h);}else if(actionMode==5&&imageOriginal!=null){resizeImage(worldX,worldY);}else if(actionMode==6){if(Math.hypot(worldX-lastWorldX,worldY-lastWorldY)*zoom>=2){if(lassoPath.size()>=256){ArrayList<double[]> fewer=new ArrayList<>();for(int p=0;p<lassoPath.size();p+=2)fewer.add(lassoPath.get(p));lassoPath.clear();lassoPath.addAll(fewer);}lassoPath.add(new double[]{worldX,worldY});}}else if(actionMode==7||actionMode==8){previewGroup(worldX,worldY);}lastX=x;lastY=y;lastWorldX=worldX;lastWorldY=worldY;invalidate();}
    private void notifyScroll(){scrollNotified=true;}
    private int pageTurn(float x,float y){float dx=x-gestureStartX,dy=y-gestureStartY;return pageDrag.turn(horizontalPages?dx:dy,horizontalPages?dy:dx,dp(90));}
    private void updatePreview(double x,double y){int type=NoteDocument.STROKE;switch(tool){case "line":type=NoteDocument.LINE;break;case "circle":type=NoteDocument.CIRCLE;break;case "rectangle":type=NoteDocument.RECTANGLE;break;case "arrow":type=NoteDocument.ARROW;break;default:break;}double[] points;if(type==NoteDocument.STROKE){points=new double[vertices.size()*2];for(int i=0;i<vertices.size();i++){points[i*2]=vertices.get(i)[0];points[i*2+1]=vertices.get(i)[1];}}else{if(type==NoteDocument.CIRCLE){double side=Math.min(Math.abs(x-startX),Math.abs(y-startY));x=startX+Math.copySign(side,x-startX);y=startY+Math.copySign(side,y-startY);}points=new double[]{startX,startY,x,y};}int ink="marker".equals(tool)?InkInput.markerColor(color):"pencil".equals(tool)?(color&0x00ffffff)|0xa0000000:color;preview=new NoteDocument.Item(1,type,ink,width,points,"");}
    private void eraseAt(double x,double y){eraseSweep(x,y,x,y);}
    private void eraseSweep(double ax,double ay,double bx,double by){if(eraseFailed)return;try{List<NoteDocument.Item> source=erasePreview==null?document.items():erasePreview;erasePreview=wholeStrokeEraser?NoteEditorOps.wholeStrokeErase(source,ax,ay,bx,by,width/2):NoteDocument.eraseSweep(source,ax,ay,bx,by,width/2);}catch(IllegalArgumentException problem){erasePreview=null;eraseFailed=true;host.error("这次橡皮轨迹产生的笔迹过多，未修改笔记");}}
    private void previewGroup(double x,double y){
        if(groupBounds==null||groupOriginal.isEmpty())return;
        double dx=0,dy=0,factor=1;
        if(actionMode==7){dx=x-startX;dy=y-startY;if(!document.infinite()){dx=clamp(dx,-groupBounds[0],document.width-groupBounds[2]);dy=clamp(dy,-groupBounds[1],document.height-groupBounds[3]);}}
        else{
            double ax=groupBounds[0],ay=groupBounds[1];double original=Math.hypot(groupBounds[2]-ax,groupBounds[3]-ay);
            factor=original<.001?1:clamp(Math.hypot(x-ax,y-ay)/original,.25,4);
            if(!document.infinite()){factor=Math.min(factor,Math.min((document.width-ax)/Math.max(.001,groupBounds[2]-ax),(document.height-ay)/Math.max(.001,groupBounds[3]-ay)));}
        }
        LinkedHashMap<Long,NoteDocument.Item> next=new LinkedHashMap<>();
        try{for(NoteDocument.Item item:groupOriginal.values())next.put(item.id,actionMode==7?NoteEditorOps.translated(item,dx,dy):NoteEditorOps.scaled(item,groupBounds[0],groupBounds[1],factor));groupPreview.clear();groupPreview.putAll(next);}catch(IllegalArgumentException ignored){}
    }
    private void resizeImage(double x,double y){if(imageOriginal.type==NoteDocument.TEXT){
        double ax=resizeCorner%2==0?imageOriginal.right():imageOriginal.left(),signX=resizeCorner%2==0?-1:1;
        double w=Math.max(24,(x-ax)*signX),nx=signX<0?ax-w:ax;
        if(!document.infinite()){nx=clamp(nx,0,document.width-24);w=Math.min(w,document.width-nx);}
        boolean topHandle=resizeCorner<2;double ay=topHandle?imageOriginal.bottom():imageOriginal.top();
        double requestedHeight=Math.max(1,topHandle?ay-y:y-ay);
        float size=(float)clamp(imageOriginal.width*requestedHeight/Math.max(1,imageOriginal.bottom()-imageOriginal.top()),8,72);
        double h=NoteTextLayout.height(imageOriginal.text,size,imageOriginal.color,w);
        double ny=topHandle?ay-h:ay;if(!document.infinite())ny=clamp(ny,0,Math.max(0,document.height-h));
        imagePreview=new NoteDocument.Item(imageOriginal.id,NoteDocument.TEXT,imageOriginal.color,size,new double[]{nx,ny,nx+w,ny+h},imageOriginal.image,imageOriginal.text);return;}
        double ratio=(imageOriginal.right()-imageOriginal.left())/(imageOriginal.bottom()-imageOriginal.top());double ax=resizeCorner%2==0?imageOriginal.right():imageOriginal.left(),ay=resizeCorner<2?imageOriginal.bottom():imageOriginal.top();double signX=resizeCorner%2==0?-1:1,signY=resizeCorner<2?-1:1;double w=Math.max(dp(20)/zoom,Math.max((x-ax)*signX,(y-ay)*signY*ratio)),h=w/ratio;if(!document.infinite()){double maxW=signX<0?ax:document.width-ax,maxH=signY<0?ay:document.height-ay;double s=Math.min(1,Math.min(maxW/w,maxH/h));w*=s;h*=s;}if(w>.1&&h>.1)imagePreview=imageOriginal.bounds(signX<0?ax-w:ax,signY<0?ay-h:ay,w,h);}
    private int handleAt(NoteDocument.Item item,float x,float y){for(int i=0;i<4;i++)if(Math.hypot(x-sx(i%2==0?item.left():item.right()),y-sy(i<2?item.top():item.bottom()))<=dp(20))return i;return -1;}
    private NoteDocument.Item imageAt(double x,double y){List<NoteDocument.Item> items=document.items();for(int i=items.size()-1;i>=0;i--){NoteDocument.Item item=items.get(i);if((item.type==NoteDocument.IMAGE||item.type==NoteDocument.TEXT)&&item.near(x,y,0))return item;}return null;}
    public void finishGesture(){if(disposed)return;boolean didChange=false,selectionChanged=false;try{
        if(actionMode==1&&preview!=null){NoteDocument.Item p=preview;document.add(new NoteDocument.Item(document.newId(),p.type,p.color,p.width,p.points,p.image));didChange=true;}
        else if(actionMode==2&&erasePreview!=null){didChange=document.applyErasure(erasePreview);}
        else if((actionMode==4||actionMode==5)&&imagePreview!=null&&imageOriginal!=null&&(!Arrays.equals(imagePreview.points,imageOriginal.points)||imagePreview.width!=imageOriginal.width)){document.replace(imagePreview);didChange=true;}
        else if(actionMode==6){List<double[]> outline=lassoPath;if(!lassoFreeform&&lassoPath.size()>1)outline=Arrays.asList(lassoPath.get(0),lassoPath.get(lassoPath.size()-1));selection.clear();selection.addAll(NoteEditorOps.select(document.items(),outline,lassoFreeform,lassoFilter));selectionChanged=true;}
        else if((actionMode==7||actionMode==8)&&!groupPreview.isEmpty()){
            ArrayList<NoteDocument.Item> next=new ArrayList<>();
            for(NoteDocument.Item item:document.items()){NoteDocument.Item replacement=groupPreview.get(item.id);if(replacement!=null&&(!Arrays.equals(replacement.points,item.points)||replacement.width!=item.width)){next.add(replacement);didChange=true;}else next.add(item);}
            if(didChange)document.applyItems(next);
        }
    }catch(IllegalArgumentException e){host.error(e.getMessage());didChange=false;}clearGesture();if(didChange)changed();else{syncViewport();if(selectionChanged)host.changed();invalidate();}settleViewport();}
    private void cancelGesture(){clearGesture();syncViewport();settleViewport();invalidate();}
    private void clearGesture(){actionMode=0;active=-1;activePen=false;gesturePage=-1;preview=null;vertices.clear();erasePreview=null;eraseFailed=false;imageOriginal=null;imagePreview=null;lassoPath.clear();groupOriginal.clear();groupPreview.clear();groupBounds=null;}
    private void constrainViewport(){if(document.infinite()){if(!NoteDocument.finite(cx)||!NoteDocument.finite(cy)){cx=0;cy=0;}return;}double halfW=getWidth()/(2*zoom),halfH=getHeight()/(2*zoom);cx=document.width<halfW*2?document.width/2:clamp(cx,halfW,document.width-halfW);double total=continuous()?pageLayout.totalHeight(document.pageCount()):document.height;cy=total<halfH*2?total/2:clamp(cy,halfH,total-halfH);}
    private void updateCenteredPage(){if(!continuous()||!initialized||(actionMode!=3&&!multi))return;int page=pageLayout.nearest(cy,document.pageCount());if(page==document.currentPage())return;syncViewport();document.selectPage(page);selected=0;selection.clear();renderPage=page;viewportPageDirty=true;host.viewportPageChanged(page);}
    private void settleViewport(){if(viewportPageDirty){viewportPageDirty=false;host.viewportSettled();}}
    private boolean onPaper(double x,double y){return document.infinite()||(x>=0&&y>=0&&x<=document.width&&y<=document.height);}
    private static boolean isPen(int tool){return tool==MotionEvent.TOOL_TYPE_STYLUS||tool==MotionEvent.TOOL_TYPE_ERASER;}
    private float sx(double x){return (float)(getWidth()/2.0+(x-cx)*zoom);}
    private float sy(double y){return (float)(getHeight()/2.0+(y+pageOffset(renderPage)-cy)*zoom);}
    private double wx(float x){return cx+(x-getWidth()/2.0)/zoom;}
    private double wy(float y){return wyGlobal(y)-pageOffset(gesturePage>=0?gesturePage:document.currentPage());}
    private double wyGlobal(float y){return cy+(y-getHeight()/2.0)/zoom;}
    private double pageOffset(int page){return continuous()?pageLayout.top(page):0;}
    private double renderCenterY(){return continuous()?pageLayout.localY(renderPage,cy):cy;}
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
    private static double clamp(double value,double min,double max){return Math.max(min,Math.min(max,value));}
}
