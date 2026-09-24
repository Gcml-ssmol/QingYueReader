package cn.qingyue.pdf;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.view.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Continuous scrolling or animated single-page canvas; ink always uses page coordinates. */
public final class PdfReaderView extends View {
    public static final int TOOL_BROWSE=0,TOOL_PEN=1,TOOL_MARKER=2,TOOL_ERASER=3,
            TOOL_LOCAL_ERASER=4,TOOL_LASSO=5,TOOL_LINE=6,TOOL_RECTANGLE=7,
            TOOL_ELLIPSE=8,TOOL_ARROW=9,TOOL_BALLPOINT=10,TOOL_PENCIL=11;
    public interface Host {
        void changed(int page);
        void error(String message);
        void colorPicked(int color);
        void readingScrolled();
        /** Runs on the reader's serial worker only after the ink snapshot is durable. */
        default void inkSaved(String documentId,long modifiedAtMillis) { }
    }
    public interface TextSelectionListener {void selected(TextSelection selection);}
    public interface TextBoxListener {void selected(ReaderDocument.TextBox box);void moved(ReaderDocument.TextBox box,float x,float y);void placed(int page,float x,float y);}
    public static final class TextSelection {
        public final int page;public final String text;public final List<ReaderDocument.Box> boxes;
        TextSelection(int page,String text,List<ReaderDocument.Box> boxes){this.page=page;this.text=text;this.boxes=Collections.unmodifiableList(new ArrayList<>(boxes));}
    }
    private final Host host;
    private final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    private final ExecutorService worker;
    /** Detail rendering never queues behind a large annotation save. */
    private final ThreadPoolExecutor renderWorker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>(),r->{Thread t=new Thread(r,"qingyue-pdf-detail");t.setPriority(Thread.NORM_PRIORITY-1);return t;});
    private final File pdf,directory;
    private final String docId;
    private final int count;
    private final HashMap<Integer,PageState> cache=new HashMap<>();
    private PageLayout layout;
    private volatile boolean disposed;
    private boolean loading,metadataFailed,preparing;
    private int initialPage,selectedPage;
    private boolean positionPending=true;
    private float pendingY=Float.NaN;
    private double scroll;
    private float zoom=1,panX,panY;
    private final ZoomBadge zoomBadge=new ZoomBadge();
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
    private final InkStrokeRenderer.Cache strokeCache=new InkStrokeRenderer.Cache();
    private final RectF bitmapDestination=new RectF();
    private final InkInput.Router penRouter=new InkInput.Router();
    private final TouchNavigation.Guard navigationGuard=new TouchNavigation.Guard();
    private final TouchNavigation.Pinch pinch;
    private boolean penNavigation;
    private final InkInput.PressureSamples pressureSamples=new InkInput.PressureSamples();
    private PageState writing;
    private ArrayList<Float> draft;
    private ArrayList<Float> draftPressure;
    private ArrayList<InkPage.Stroke> erased;
    private InkPage localEraseDraft;
    private boolean wholeErasing;
    private List<Integer> selectedInk=Collections.emptyList();private PageState selectedInkPage;
    private List<InkPage.Stroke> moveOriginal;private boolean movingSelection;private float moveStartX,moveStartY;
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
    private final LinkedHashMap<PdfRenderPlan.Key,Bitmap> detailCache=new LinkedHashMap<>(32,.75f,true);
    private final ArrayList<Map.Entry<PdfRenderPlan.Key,Bitmap>> detailDrawList=new ArrayList<>();
    private boolean detailDrawListDirty=true;
    private final HashSet<PdfRenderPlan.Key> detailPending=new HashSet<>();
    private final HashSet<PdfRenderPlan.Key> detailFailed=new HashSet<>();
    private long detailBytes;private volatile int detailGeneration;
    private final Runnable detailRequest=this::requestDetail;
    private List<PdfTextSearch.Hit> searchHits=Collections.emptyList();private int activeSearchHit=-1;
    private ReaderDocument readerDocument=new ReaderDocument();
    private Map<Integer,List<PdfTextSearch.Hit>> searchByPage=Collections.emptyMap();
    private Map<Integer,List<ReaderDocument.Markup>> markupByPage=Collections.emptyMap();
    private TextBoxListener textBoxListener;private ReaderDocument.TextBox touchedTextBox;private float textDownX,textDownY;private boolean placingText;
    private PdfTextSearch.PageText pageText;private TextSelection textSelection;private TextSelectionListener textSelectionListener;
    private boolean textSelecting,textSelectionDragging,textDraggingHandle;private float textStartX,textStartY,textEndX,textEndY;
    private float textHandleDownX,textHandleDownY;
    private ReaderDocument.Box textHandleGlyph;
    private int textAnchorGlyph=-1,textFocusGlyph=-1;
    private static final class PageState {
        final int number;final String key;
        Bitmap bitmap;InkPage ink=new InkPage(Collections.emptyList());
        final InkStrokeRenderer.Page inkDrawing=new InkStrokeRenderer.Page();
        boolean readable,failed,previewLoading;long revision;String status="已保存";
        PageState(int page,String key){number=page;this.key=key;}
    }

    public PdfReaderView(Context context,File pdf,File directory,String id,int count,int page,ExecutorService worker,Host host){
        super(context);this.pdf=pdf;this.directory=directory;this.docId=id;this.count=count;this.initialPage=page;this.selectedPage=page;this.worker=worker;this.host=host;
        twoFingerTap=new TwoFingerTap(dp(7));
        pinch=new TouchNavigation.Pinch(getResources().getDisplayMetrics().density);
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
                if(count<=0||renderer.getPageCount()<=0)throw new IOException("PDF has no pages");
                float[] ratios=new float[count];Arrays.fill(ratios,1.4142f);int first=Math.max(0,Math.min(Math.min(count,renderer.getPageCount())-1,initialPage));
                try(PdfRenderer.Page page=renderer.openPage(first)){ratios[first]=page.getHeight()/(float)page.getWidth();}
                PageLayout ready=new PageLayout(ratios);main.post(()->{preparing=false;if(disposed)return;layout=ready;documentStatus="";applyPendingPosition();});
            }catch(Exception|OutOfMemoryError e){main.post(()->{preparing=false;if(!disposed){metadataFailed=true;documentStatus="文档加载失败，点击顶部状态重试";notifyHost();invalidate();}});}
        });
    }
    public void setTool(int mode,int color,float width,float eraserDiameter,boolean penOnly){
        if(mode<TOOL_BROWSE||mode>TOOL_PENCIL)throw new IllegalArgumentException("Unknown reader tool");finishGesture();stopFling();cancelPageAnimation();cancelColorPick();
        cancelTextPlacement();
        if(mode!=TOOL_BROWSE)cancelTextSelection();
        if(this.mode==TOOL_LASSO&&mode!=TOOL_LASSO)clearInkSelection();this.mode=mode;this.color=color;this.width=width;this.eraserDiameter=eraserDiameter;this.penOnly=penOnly;invalidate();
    }
    public void setAdvancedTool(int mode){setTool(mode,color,width,eraserDiameter,penOnly);}
    public void setTextBoxListener(TextBoxListener listener){textBoxListener=listener;}
    public void beginTextPlacement(){finishGesture();stopFling();cancelTextSelection();placingText=true;invalidate();}
    public void cancelTextPlacement(){placingText=false;touchedTextBox=null;invalidate();}
    public boolean isPlacingText(){return placingText;}
    public boolean hasInkSelection(){return selectedInkPage!=null&&!selectedInk.isEmpty();}
    public void clearInkSelection(){selectedInk=Collections.emptyList();selectedInkPage=null;moveOriginal=null;movingSelection=false;invalidate();}
    public boolean copyInkSelection(){if(!hasInkSelection())return false;List<Integer> copied=selectedInkPage.ink.copy(selectedInk,.018f,.018f);selectedInk=copied;persist(selectedInkPage);invalidate();return !copied.isEmpty();}
    public boolean deleteInkSelection(){if(!hasInkSelection())return false;PageState page=selectedInkPage;boolean changed=page.ink.delete(selectedInk);clearInkSelection();if(changed)persist(page);return changed;}
    public float previewDiameter(float normalizedWidth){return normalizedWidth*pageWidth();}
    public void setTwoFingerUndoEnabled(boolean enabled){twoFingerUndoEnabled=enabled;twoFingerTap.reset();discardPendingFingerInk();}
    public void setHorizontalPaging(boolean enabled){
        if(horizontalPaging==enabled)return;finishGesture();stopFling();cancelPageAnimation();cancelDetailRequests();horizontalPaging=enabled;zoom=1;panX=panY=0;
        if(layout!=null){scroll=layout.jumpScroll(selectedPage,viewport());updateViewport();}blockUntilDown=true;
    }
    public boolean isPickingColor(){return picking;}
    public void beginColorPick(){finishGesture();stopFling();cancelPageAnimation();resetVelocity();picking=true;sampleInput.reset();sampleValid=false;notifyHost();invalidate();}
    public void cancelColorPick(){picking=false;sampleInput.reset();sampleValid=false;blockUntilDown=true;notifyHost();invalidate();}
    public int page(){return selectedPage;}
    public float pageHeight(int page){return layout==null||page<0||page>=count?1.4142f:(float)layout.height(page);}
    public boolean ready(){PageState p=cache.get(selectedPage);return p!=null&&p.readable&&p.bitmap!=null&&!p.failed;}
    public boolean canUndo(){PageState p=cache.get(selectedPage);return p!=null&&p.readable&&p.ink.canUndo();}
    public boolean canRedo(){PageState p=cache.get(selectedPage);return p!=null&&p.readable&&p.ink.canRedo();}
    public String status(){if(picking)return "拖动取色，松手应用 · 点此取消";if(layout==null)return documentStatus;PageState p=cache.get(selectedPage);return p==null?"加载页面…":p.failed?"页面加载失败，点此重试":!p.readable?"批注读取失败，点此重试":p.status;}
    public void undo(){finishGesture();cancelPageAnimation();if(canUndo()&&cache.get(selectedPage).ink.undo()){InkHistoryRegistry.touch(cache.get(selectedPage).key);persist(cache.get(selectedPage));}}
    public void redo(){finishGesture();cancelPageAnimation();if(canRedo()&&cache.get(selectedPage).ink.redo()){InkHistoryRegistry.touch(cache.get(selectedPage).key);persist(cache.get(selectedPage));}}
    public void clearPage(int page){finishGesture();PageState p=cache.get(page);if(p!=null&&p.readable&&!p.ink.strokes().isEmpty()){InkStore.prepareClear(p.key,p.ink.strokes());p.ink.clear();persist(p);}}
    public boolean canRestoreLastClear(int page){PageState p=cache.get(page);String key=p==null?docId+"-"+page:p.key;return page>=0&&page<count&&InkStore.hasClearRecovery(directory,key);}
    public void restoreLastClear(int page){
        if(page<0||page>=count||disposed)return;finishGesture();final PageState state=session(page);state.status="正在恢复清空前批注";notifyHost();
        worker.execute(()->{try{final List<InkPage.Stroke> restored=InkStore.loadClearRecovery(directory,state.key);main.post(()->{
                    if(disposed)return;List<InkPage.Stroke> current=state.ink.strokes();ArrayList<InkPage.Stroke> merged=new ArrayList<>(InkPage.sameContent(restored,current)?current:restored);
                    // Never discard strokes written after clear while the recovery read was in flight.
                    if(!InkPage.sameContent(restored,current))merged.addAll(current);try{state.ink.replace(merged);}catch(IllegalArgumentException tooLarge){state.status="恢复失败：合并后批注过多";notifyHost();host.error("清空前后的批注总量过大，未覆盖当前内容");return;}
                    state.readable=true;InkHistoryRegistry.put(state.key,state.ink);List<InkPage.Stroke> snapshot=state.ink.strokes();long version=++state.revision;long storageRevision=InkStore.stage(state.key,snapshot);state.status="正在保存恢复结果";notifyHost();invalidate();
                    worker.execute(()->{boolean ok=true;try{ok=InkStore.consumeClearRecovery(directory,state.key,storageRevision);if(ok)notifyInkSaved();}catch(IOException|RuntimeException problem){ok=false;}final boolean saved=ok;main.post(()->{if(disposed)return;if(state.revision==version)state.status=saved?"已恢复清空前批注":"恢复内容尚未保存，点此重试";notifyHost();if(!saved)host.error("恢复内容未能保存，请保留页面并重试");});});
                });}
            catch(IOException|RuntimeException problem){main.post(()->{if(!disposed){state.status="恢复失败，点此重试";notifyHost();host.error("无法恢复清空前批注，请检查存储空间");}});}});
    }
    public void setReaderDocument(ReaderDocument document){
        readerDocument=document==null?new ReaderDocument():document;HashMap<Integer,List<ReaderDocument.Markup>> grouped=new HashMap<>();
        for(ReaderDocument.Markup markup:readerDocument.markups())grouped.computeIfAbsent(markup.page,k->new ArrayList<>()).add(markup);
        HashMap<Integer,List<ReaderDocument.Markup>> ready=new HashMap<>();for(Map.Entry<Integer,List<ReaderDocument.Markup>> entry:grouped.entrySet())ready.put(entry.getKey(),Collections.unmodifiableList(entry.getValue()));markupByPage=Collections.unmodifiableMap(ready);invalidate();
    }
    public void setSearchResults(List<PdfTextSearch.Result> results,PdfTextSearch.Result selected,int hitIndex){
        ArrayList<PdfTextSearch.Hit> flattened=new ArrayList<>();int target=-1;
        if(results!=null)for(PdfTextSearch.Result result:results){for(PdfTextSearch.Hit hit:result.hits){if(result==selected&&hit.ordinal==hitIndex)target=flattened.size();flattened.add(hit);}}
        searchHits=Collections.unmodifiableList(flattened);HashMap<Integer,List<PdfTextSearch.Hit>> grouped=new HashMap<>();for(PdfTextSearch.Hit hit:searchHits)grouped.computeIfAbsent(hit.page,k->new ArrayList<>()).add(hit);
        HashMap<Integer,List<PdfTextSearch.Hit>> ready=new HashMap<>();for(Map.Entry<Integer,List<PdfTextSearch.Hit>> entry:grouped.entrySet())ready.put(entry.getKey(),Collections.unmodifiableList(entry.getValue()));searchByPage=Collections.unmodifiableMap(ready);
        activeSearchHit=target<0&&selected!=null?findFirstHit(selected.page):target;
        if(selected!=null){PdfTextSearch.Hit hit=activeSearchHit>=0?searchHits.get(activeSearchHit):null;jumpTo(selected.page,hit==null||hit.boxes.isEmpty()?0:hit.boxes.get(0).top);invalidate();}
    }
    public boolean nextSearchHit(){return moveSearchHit(1);}
    public boolean previousSearchHit(){return moveSearchHit(-1);}
    public void clearSearchHighlights(){searchHits=Collections.emptyList();searchByPage=Collections.emptyMap();activeSearchHit=-1;invalidate();}
    private int findFirstHit(int page){for(int i=0;i<searchHits.size();i++)if(searchHits.get(i).page==page)return i;return -1;}
    private boolean moveSearchHit(int direction){if(searchHits.isEmpty())return false;activeSearchHit=(activeSearchHit<0?(direction>0?0:searchHits.size()-1):(activeSearchHit+direction+searchHits.size())%searchHits.size());PdfTextSearch.Hit hit=searchHits.get(activeSearchHit);jumpTo(hit.page,hit.boxes.isEmpty()?0:hit.boxes.get(0).top);invalidate();return true;}
    public void beginTextSelection(PdfTextSearch.PageText text,TextSelectionListener listener){
        finishGesture();clearInkSelection();pageText=text;textSelection=null;textSelectionListener=listener;textSelecting=text!=null;textSelectionDragging=false;textAnchorGlyph=textFocusGlyph=-1;
        if(text!=null)jumpTo(text.page);invalidate();
    }
    public boolean isSelectingText(){return textSelecting;}
    public TextSelection textSelection(){return textSelection;}
    public void cancelTextSelection(){textSelecting=false;textSelectionDragging=false;pageText=null;textSelection=null;textSelectionListener=null;textAnchorGlyph=textFocusGlyph=-1;invalidate();}
    public boolean copySelectedText(){
        if(textSelection==null||textSelection.text.length()==0)return false;ClipboardManager clipboard=(ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if(clipboard==null)return false;clipboard.setPrimaryClip(ClipData.newPlainText("PDF 摘录",textSelection.text));return true;
    }
    public ReaderDocument.Markup highlightSelectedText(ReaderDocument document,int color){
        if(textSelection==null||document==null)return null;ReaderDocument.Markup markup=document.addMarkup(textSelection.page,color,textSelection.text,textSelection.boxes);setReaderDocument(document);return markup;
    }
    public void retry(){
        if(disposed)return;if(metadataFailed){prepare();invalidate();return;}
        PageState p=cache.get(selectedPage);if(p!=null){if(p.failed||!p.readable){p.bitmap=null;p.failed=false;p.previewLoading=false;}else if(InkStore.pending.containsKey(p.key))persist(p);}
        requestPage();
    }
    public void fitWidth(){finishGesture();stopFling();cancelPageAnimation();cancelDetailRequests();double anchor=scroll;zoom=1;panX=panY=0;scroll=anchor;updateViewport();}
    public int zoomPercent(){return ZoomLimits.percent(zoom);}
    public void setZoomPercent(int percent){
        if(layout==null||disposed||getWidth()<=0||getHeight()<=0)return;
        finishGesture();stopFling();cancelPageAnimation();cancelDetailRequests();
        float focusX=getWidth()/2f,focusY=getHeight()/2f;
        double anchorY=horizontalPaging?(focusY-top(selectedPage))/pageWidth():scroll+focusY/pageWidth();
        float anchorX=(focusX-left())/pageWidth();
        zoom=(float)ZoomLimits.clamp(percent/100.0);
        panX=focusX-getWidth()/2f+(0.5f-anchorX)*pageWidth();
        if(horizontalPaging)panY=focusY-(getHeight()-(float)layout.height(selectedPage)*pageWidth())/2-(float)anchorY*pageWidth();
        else scroll=anchorY-focusY/pageWidth();
        updateViewport();
    }
    public void jumpTo(int page){
        if(page<0||page>=count||disposed)return;
        finishGesture();stopFling();cancelPageAnimation();cancelDetailRequests();initialPage=page;selectedPage=page;pendingY=Float.NaN;positionPending=true;
        applyPendingPosition();
    }
    /** Immediately positions a page-coordinate y near the upper third of the viewport. */
    public void jumpTo(int page,float normalizedY){
        if(page<0||page>=count||disposed||Float.isNaN(normalizedY)||Float.isInfinite(normalizedY))return;
        if(page!=selectedPage)cancelTextPlacement();
        finishGesture();stopFling();cancelPageAnimation();cancelDetailRequests();initialPage=page;selectedPage=page;pendingY=normalizedY;positionPending=true;
        applyPendingPosition();
    }
    private void applyPendingPosition(){
        if(!positionPending||layout==null||getWidth()<=0||getHeight()<=0)return;
        positionPending=false;int page=initialPage;selectedPage=page;
        if(Float.isNaN(pendingY)){scroll=layout.jumpScroll(page,viewport());panX=panY=0;if(horizontalPaging)zoom=1;}
        else{float y=Math.max(0,Math.min((float)layout.height(page),pendingY));panX=0;
            if(horizontalPaging){float centered=(getHeight()-(float)layout.height(page)*pageWidth())/2;panY=getHeight()*.35f-centered-y*pageWidth();panY=HorizontalPaging.clampPan(panY,(float)layout.height(page)*pageWidth(),getHeight(),dp(10));}
            else{scroll=layout.clampScroll(layout.top(page)+y-viewport()*.35,viewport());panY=0;}
        }
        notifyHost();invalidate();requestPage();requestDetailSoon();
    }
    private void cancelPageAnimation(){
        if(pageAnimator!=null){pageAnimator.removeAllListeners();pageAnimator.removeAllUpdateListeners();pageAnimator.cancel();pageAnimator=null;}
        slideOffset=0;slideTarget=-1;invalidate();
    }
    private void animatePage(int target){
        if(layout==null||disposed)return;finishGesture();stopFling();cancelDetailRequests();resetVelocity();blockUntilDown=true;
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
            if(positionPending)applyPendingPosition();
            else if(oldw>0&&oldh>0&&w>0&&h>0){
                float oldBase=horizontalPaging?HorizontalPaging.fitWidth(oldw,oldh,layout.height(selectedPage),dp(10)):Math.max(1,oldw-dp(20));
                float oldScale=oldBase*zoom,newBase=horizontalPaging?fittedWidth(selectedPage):Math.max(1,w-dp(20));
                zoom=(float)ZoomLimits.clamp(oldScale/newBase);
                if(horizontalPaging){float ratio=pageWidth()/oldScale;panX*=ratio;panY*=ratio;}
                else scroll=TouchNavigation.resizedScroll(scroll,oldh,oldScale,h,pageWidth());
                // Sidebar and toolbar layout changes do not select a different page.
                updateViewport(false);
            }
            else{
                // Collapsing the title row moves this view while a finger is still down.
                // Keep document content under that finger; navigation deltas use raw coordinates.
                if(navigationActive&&w==oldw&&!horizontalPaging){int[] position=new int[2];getLocationOnScreen(position);scroll+=(position[1]-gestureViewY)/pageWidth();gestureViewY=position[1];}
                updateViewport();
            }
        }
    }
    private void updateViewport(){
        updateViewport(true);
    }
    private void updateViewport(boolean updateSelection){
        if(layout==null||disposed)return;
        if(horizontalPaging){panX=HorizontalPaging.clampPan(panX,pageWidth(),getWidth(),dp(10));panY=HorizontalPaging.clampPan(panY,(float)layout.height(selectedPage)*pageWidth(),getHeight(),dp(10));invalidate();requestPage();return;}
        scroll=layout.clampScroll(scroll,viewport());float limit=maxPan();panX=Math.max(-limit,Math.min(limit,panX));
        if(updateSelection&&writing==null){int next=layout.pageAt(scroll+Math.min(viewport()*0.35,0.5));if(next!=selectedPage){cancelDetailRequests();selectedPage=next;notifyHost();}}
        invalidate();requestPage();
    }
    private void notifyHost(){if(!disposed)host.changed(selectedPage);}
    private int[] visible(){return horizontalPaging?new int[]{selectedPage,selectedPage}:layout.visibleRange(scroll,viewport());}
    private PageState session(int page){PageState state=cache.get(page);if(state==null){state=new PageState(page,docId+"-"+page);InkPage retained=InkHistoryRegistry.get(state.key);if(retained!=null){state.ink=retained;state.readable=true;state.status=InkStore.pending.containsKey(state.key)?"未保存，点此重试":"已保存";}cache.put(page,state);}return state;}
    private boolean needsPreview(int page){PageState state=cache.get(page);return state==null||state.bitmap==null&&!state.previewLoading&&!state.failed;}
    private void requestPage(){
        if(layout==null||disposed||loading||getWidth()<1||getHeight()<1)return;
        trimCache();int[] range=visible();int wanted=-1;
        if(needsPreview(selectedPage))wanted=selectedPage;
        else if(slideTarget>=0&&needsPreview(slideTarget))wanted=slideTarget;
        else for(int p=range[0];p<=range[1];p++)if(needsPreview(p)){wanted=p;break;}
        long bytes=0;for(PageState p:cache.values())if(p.bitmap!=null)bytes+=p.bitmap.getAllocationByteCount();
        long budget=Math.min(48L*1024*1024,Runtime.getRuntime().maxMemory()/5);
        int bitmapCount=0;for(PageState p:cache.values())if(p.bitmap!=null)bitmapCount++;
        if(wanted<0&&bitmapCount<5&&bytes+10L*1024*1024<=budget)for(int p:new int[]{selectedPage+1,selectedPage-1})if(p>=0&&p<count&&needsPreview(p)){wanted=p;break;}
        if(wanted<0){requestDetailSoon();return;}
        final int page=wanted;final PageState state=session(page);final boolean loadInk=!state.readable;
        final int pixels=Math.max(800,Math.min(1800,(int)(getWidth()*1.6f)));loading=true;state.previewLoading=true;
        worker.execute(()->{
            if(disposed){return;}Bitmap rendered=null;List<InkPage.Stroke> loadedInk=null;boolean inkReadable=!loadInk,failed=false;float measuredRatio=0;
            try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd);PdfRenderer.Page source=renderer.openPage(page)){
                measuredRatio=source.getHeight()/(float)source.getWidth();
                int[] d=PageMath.dimensions(source.getWidth(),source.getHeight(),pixels);
                double shrink=Math.min(1,Math.sqrt(2500000.0/((double)d[0]*d[1])));d[0]=Math.max(1,(int)(d[0]*shrink));d[1]=Math.max(1,(int)(d[1]*shrink));
                rendered=Bitmap.createBitmap(d[0],d[1],Bitmap.Config.ARGB_8888);rendered.eraseColor(Color.WHITE);source.render(rendered,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                if(loadInk)try{loadedInk=InkStore.load(directory,state.key);inkReadable=true;}catch(IOException e){inkReadable=false;}
            }catch(Exception|OutOfMemoryError e){if(rendered!=null){rendered.recycle();rendered=null;}failed=true;}
            final Bitmap readyBitmap=rendered;final List<InkPage.Stroke> readyInk=loadedInk;final boolean readyReadable=inkReadable,renderFailed=failed;final float readyRatio=measuredRatio;
            main.post(()->{
                loading=false;state.previewLoading=false;if(disposed){if(readyBitmap!=null)readyBitmap.recycle();return;}
                if(readyRatio>0)recalibratePage(page,readyRatio);
                if(readyBitmap!=null){state.bitmap=readyBitmap;state.failed=false;}else if(state.bitmap==null)state.failed=renderFailed;
                if(loadInk&&!state.readable){if(readyReadable){state.ink=new InkPage(readyInk==null?Collections.emptyList():readyInk);InkHistoryRegistry.put(state.key,state.ink);state.readable=true;state.status=InkStore.pending.containsKey(state.key)?"未保存，点此重试":"已保存";}else state.readable=false;}
                trimCache();notifyHost();invalidate();requestPage();requestDetailSoon();
            });
        });
    }
    private void recalibratePage(int page,float ratio){
        if(layout==null||page<0||page>=layout.size()||Math.abs(layout.height(page)-ratio)<.0001)return;
        float renderedWidth=pageWidth();double anchorOffset=scroll-layout.top(selectedPage);
        float centerY=horizontalPaging?(getHeight()/2f-top(selectedPage))/renderedWidth:0;
        layout=layout.withHeight(page,ratio);
        if(horizontalPaging&&page==selectedPage){
            zoom=(float)ZoomLimits.clamp(renderedWidth/fittedWidth(selectedPage));
            panY=getHeight()/2f-(getHeight()-ratio*pageWidth())/2f-centerY*pageWidth();
        }
        if(!horizontalPaging)scroll=layout.clampScroll(layout.top(selectedPage)+anchorOffset,viewport());
    }
    private void trimCache(){
        if(layout==null)return;int[] visible=visible();long bytes=0;for(PageState p:cache.values())if(p.bitmap!=null)bytes+=p.bitmap.getAllocationByteCount();
        long budget=Math.min(48L*1024*1024,Runtime.getRuntime().maxMemory()/5);
        ArrayList<PageState> candidates=new ArrayList<>(cache.values());Collections.sort(candidates,(a,b)->Integer.compare(Math.abs(b.number-selectedPage),Math.abs(a.number-selectedPage)));
        int bitmapCount=0;for(PageState item:cache.values())if(item.bitmap!=null)bitmapCount++;
        for(PageState p:candidates){
            if(p==writing||p.number==selectedPage||p.number==slideTarget||p.number>=visible[0]&&p.number<=visible[1])continue;
            if(bitmapCount<=5&&bytes<=budget&&Math.abs(p.number-selectedPage)<=2)continue;
            if(p.bitmap!=null){bytes-=p.bitmap.getAllocationByteCount();p.bitmap=null;bitmapCount--;}
        }
        if(cache.size()>20)for(PageState p:candidates){if(cache.size()<=20)break;if(p!=writing&&p.number!=selectedPage&&p.number!=slideTarget&&p.bitmap==null&&!p.previewLoading)cache.remove(p.number);}
    }
    private static final class RenderedTile {final PdfRenderPlan.Tile tile;final Bitmap bitmap;RenderedTile(PdfRenderPlan.Tile tile,Bitmap bitmap){this.tile=tile;this.bitmap=bitmap;}}
    private void requestDetailSoon(){if(disposed)return;removeCallbacks(detailRequest);postDelayed(detailRequest,150);}
    private void cancelDetailRequests(){detailGeneration++;detailPending.clear();detailFailed.clear();renderWorker.getQueue().clear();removeCallbacks(detailRequest);}
    private void requestDetail(){
        if(disposed||layout==null||getWidth()<1||getHeight()<1||!detailPending.isEmpty())return;
        int[] range=visible();ArrayList<PdfRenderPlan.Tile> wanted=new ArrayList<>();
        for(int page=range[0];page<=range[1]&&wanted.size()<4;page++){
            PageState state=cache.get(page);if(state==null||state.bitmap==null||state.failed)continue;
            float w=pageWidth(),x=left(),y=top(page),ratio=(float)layout.height(page);
            int renderWidth=PdfRenderPlan.renderWidth(state.bitmap.getWidth(),w);if(renderWidth==0)continue;
            float visibleLeft=(0-x)/w,visibleTop=(0-y)/w,visibleRight=(getWidth()-x)/w,visibleBottom=(getHeight()-y)/w;
            for(PdfRenderPlan.Tile tile:PdfRenderPlan.visible(page,renderWidth,ratio,visibleLeft,visibleTop,visibleRight,visibleBottom)){
                if(!detailCache.containsKey(tile.key)&&!detailPending.contains(tile.key)&&!detailFailed.contains(tile.key)){wanted.add(tile);if(wanted.size()>=4)break;}
            }
        }
        if(wanted.isEmpty()){trimDetailCache();return;}
        final int generation=++detailGeneration;for(PdfRenderPlan.Tile tile:wanted)detailPending.add(tile.key);
        try{renderWorker.execute(()->renderDetails(generation,wanted));}catch(RejectedExecutionException ignored){detailPending.clear();}
    }
    private void renderDetails(int generation,List<PdfRenderPlan.Tile> wanted){
        if(disposed||generation!=detailGeneration||Thread.currentThread().isInterrupted())return;
        ArrayList<RenderedTile> rendered=new ArrayList<>();
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd)){
            int opened=-1;PdfRenderer.Page source=null;
            try{
                for(PdfRenderPlan.Tile tile:wanted){
                    if(disposed||generation!=detailGeneration||Thread.currentThread().isInterrupted())break;
                    if(opened!=tile.key.page){if(source!=null)source.close();source=renderer.openPage(tile.key.page);opened=tile.key.page;}
                    Bitmap bitmap=null;try{
                        bitmap=Bitmap.createBitmap(tile.width,tile.height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);
                        float scale=tile.key.renderWidth/(float)source.getWidth();Matrix matrix=new Matrix();
                        matrix.setValues(new float[]{scale,0,-tile.left,0,scale,-tile.top,0,0,1});
                        source.render(bitmap,null,matrix,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);rendered.add(new RenderedTile(tile,bitmap));bitmap=null;
                    }catch(RuntimeException|OutOfMemoryError problem){if(bitmap!=null)bitmap.recycle();break;}
                }
            }finally{if(source!=null)source.close();}
        }catch(Exception|OutOfMemoryError ignored){}
        main.post(()->{
            if(disposed||generation!=detailGeneration){for(RenderedTile item:rendered)if(!item.bitmap.isRecycled())item.bitmap.recycle();return;}
            for(PdfRenderPlan.Tile tile:wanted)detailPending.remove(tile.key);
            HashSet<PdfRenderPlan.Key> completed=new HashSet<>();for(RenderedTile item:rendered)completed.add(item.tile.key);for(PdfRenderPlan.Tile tile:wanted)if(!completed.contains(tile.key))detailFailed.add(tile.key);
            for(RenderedTile item:rendered){Bitmap previous=detailCache.put(item.tile.key,item.bitmap);if(previous!=null)detailBytes-=previous.getAllocationByteCount();detailBytes+=item.bitmap.getAllocationByteCount();}
            detailDrawList.clear();detailDrawListDirty=true;trimDetailCache();invalidate();requestDetailSoon();
        });
    }
    private void trimDetailCache(){
        long budget=Math.min(32L*1024*1024,Math.max(8L*1024*1024,Runtime.getRuntime().maxMemory()/8));
        Iterator<Map.Entry<PdfRenderPlan.Key,Bitmap>> iterator=detailCache.entrySet().iterator();
        while(detailBytes>budget&&iterator.hasNext()){Map.Entry<PdfRenderPlan.Key,Bitmap> entry=iterator.next();Bitmap bitmap=entry.getValue();detailBytes-=bitmap.getAllocationByteCount();iterator.remove();detailDrawList.clear();detailDrawListDirty=true;}
    }
    // Once drawn, a bitmap can still belong to the render thread's display list.
    // Eviction drops our reference; GC releases pixels after the last renderer is done.
    private void clearDetailCache(){detailCache.clear();detailBytes=0;detailDrawList.clear();detailDrawListDirty=true;}
    private void persist(PageState p){
        if(disposed)return;
        InkHistoryRegistry.put(p.key,p.ink);List<InkPage.Stroke> snapshot=p.ink.strokes();long version=++p.revision;long storageRevision=InkStore.stage(p.key,snapshot);p.status="保存中";notifyHost();invalidate();
        worker.execute(()->{boolean ok=true;try{ok=InkStore.saveStaged(directory,p.key,storageRevision);if(ok)notifyInkSaved();}catch(IOException|RuntimeException e){ok=false;}final boolean saved=ok;
            main.post(()->{if(p.revision==version){p.status=saved?"已保存":"未保存，点此重试";notifyHost();}if(!saved)host.error("批注保存失败，请检查空间并返回对应页点击状态重试");});
        });
    }
    private void notifyInkSaved(){
        try{host.inkSaved(docId,System.currentTimeMillis());}catch(RuntimeException ignored){}
    }
    private void drawStroke(Canvas c,int color,float width,float[] points){
        pen.setColor(color);pen.setStrokeWidth(width);if(points.length==2){pen.setStyle(Paint.Style.FILL);c.drawCircle(points[0],points[1],width/2,pen);return;}
        pen.setStyle(Paint.Style.STROKE);path.reset();path.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);c.drawPath(path,pen);
    }
    private void drawStroke(Canvas canvas,InkPage.Stroke stroke){
        InkStrokeRenderer.draw(canvas,pen,stroke);
    }
    private void drawPage(Canvas c,int page,float x,float y,float w){
        float h=(float)(layout.height(page)*w);PageState p=cache.get(page);paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x1636291f);c.drawRect(x-dp(1),y+dp(2),x+w+dp(1),y+h+dp(3),paint);
        paint.setColor(Color.WHITE);c.drawRect(x,y,x+w,y+h,paint);
        if(p==null||p.bitmap==null){paint.setColor(AppColors.MUTED);paint.setTextSize(dp(13));paint.setTextAlign(Paint.Align.CENTER);c.drawText(p!=null&&p.failed?"此页加载失败":"正在加载第 "+(page+1)+" 页",x+w/2,Math.max(dp(24),Math.min(getHeight()-dp(28),y+h/2)),paint);return;}
        drawPdfLayer(c,p,x,y,w,h);c.save();c.translate(x,y);c.scale(w,w);c.clipRect(0,0,1,(float)layout.height(page));
        List<ReaderDocument.Markup> markups=markupByPage.get(page);if(markups!=null)for(ReaderDocument.Markup markup:markups)drawBoxes(c,markup.boxes,(markup.color&0x00ffffff)|0x55000000,false);
        for(ReaderDocument.TextBox box:readerDocument.textBoxes(page))PdfTextBoxes.draw(c,paint,box);
        PdfTextSearch.Hit active=activeSearchHit>=0&&activeSearchHit<searchHits.size()?searchHits.get(activeSearchHit):null;List<PdfTextSearch.Hit> pageHits=searchByPage.get(page);
        if(pageHits!=null)for(PdfTextSearch.Hit hit:pageHits)drawBoxes(c,hit.boxes,hit==active?0x88f28c28:0x55f6c343,hit==active);
        if(textSelection!=null&&textSelection.page==page)drawBoxes(c,textSelection.boxes,0x663b82f6,true);
        p.inkDrawing.draw(c,pen,strokeCache,p==writing&&erased!=null?erased:p.ink.strokes(),-x/w,-y/w,(getWidth()-x)/w,(getHeight()-y)/w);
        if(p==writing&&draft!=null&&!draft.isEmpty()){
            float[] points=array(draft);if(isShapeMode()&&points.length>=4){int kind=mode==TOOL_LINE?InkPage.KIND_LINE:mode==TOOL_RECTANGLE?InkPage.KIND_RECTANGLE:mode==TOOL_ELLIPSE?InkPage.KIND_ELLIPSE:InkPage.KIND_ARROW;drawStroke(c,InkPage.shape(kind,draftColor,draftWidth,points[0],points[1],points[2],points[3]));}
            else if(!isShapeMode())drawStroke(c,draftStroke());
        }
        if(selectedInkPage==p&&!selectedInk.isEmpty())drawInkSelection(c,p==writing&&erased!=null?erased:p.ink.strokes());c.restore();
    }
    private void drawInkSelection(Canvas canvas,List<InkPage.Stroke> strokes){
        float left=Float.MAX_VALUE,top=Float.MAX_VALUE,right=-Float.MAX_VALUE,bottom=-Float.MAX_VALUE;for(int index:selectedInk)if(index>=0&&index<strokes.size())for(int i=0;i<strokes.get(index).points.length;i+=2){float x=strokes.get(index).points[i],y=strokes.get(index).points[i+1];left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);}
        if(left==Float.MAX_VALUE)return;paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(.002f);paint.setColor(0xffd97718);paint.setPathEffect(new DashPathEffect(new float[]{.012f,.008f},0));canvas.drawRoundRect(left-.008f,top-.008f,right+.008f,bottom+.008f,.008f,.008f,paint);paint.setPathEffect(null);paint.setStyle(Paint.Style.FILL);
    }
    private void drawPdfLayer(Canvas c,PageState state,float x,float y,float w,float h){
        bitmapDestination.set(x,y,x+w,y+h);c.drawBitmap(state.bitmap,null,bitmapDestination,paint);
        if(detailDrawListDirty){detailDrawList.clear();detailDrawList.addAll(detailCache.entrySet());Collections.sort(detailDrawList,(a,b)->Integer.compare(a.getKey().renderWidth,b.getKey().renderWidth));detailDrawListDirty=false;}
        c.save();c.clipRect(x,y,x+w,y+h);
        for(Map.Entry<PdfRenderPlan.Key,Bitmap> entry:detailDrawList){PdfRenderPlan.Key key=entry.getKey();Bitmap bitmap=entry.getValue();
            if(key.page!=state.number||bitmap.isRecycled())continue;
            float left=x+key.column*PdfRenderPlan.TILE_SIZE*w/key.renderWidth,top=y+key.row*PdfRenderPlan.TILE_SIZE*w/key.renderWidth;
            float right=left+bitmap.getWidth()*w/key.renderWidth,bottom=top+bitmap.getHeight()*w/key.renderWidth;
            if(right<0||bottom<0||left>getWidth()||top>getHeight())continue;
            bitmapDestination.set(left,top,right,bottom);c.drawBitmap(bitmap,null,bitmapDestination,paint);detailCache.get(key);
        }c.restore();
    }
    private void drawBoxes(Canvas canvas,List<ReaderDocument.Box> boxes,int color,boolean outline){
        paint.setStyle(Paint.Style.FILL);paint.setColor(color);for(ReaderDocument.Box box:boxes)canvas.drawRoundRect(box.left,box.top,box.right,box.bottom,.003f,.003f,paint);
        if(outline){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(.0015f);paint.setColor(0xffd97718);for(ReaderDocument.Box box:boxes)canvas.drawRoundRect(box.left,box.top,box.right,box.bottom,.003f,.003f,paint);paint.setStyle(Paint.Style.FILL);}
    }
    @Override protected void onDraw(Canvas c){
        strokeCache.beginFrame();
        super.onDraw(c);paint.setStyle(Paint.Style.FILL);
        if(layout==null){paint.setColor(AppColors.MUTED);paint.setTextSize(dp(14));paint.setTextAlign(Paint.Align.CENTER);c.drawText(documentStatus,getWidth()/2f,getHeight()/2f,paint);return;}
        int[] range=visible();float w=pageWidth(),x=left();
        if(horizontalPaging){c.save();c.clipRect(slideOffset,0,slideOffset+getWidth(),getHeight());}
        for(int page=range[0];page<=range[1];page++)drawPage(c,page,x+(horizontalPaging?slideOffset:0),top(page),w);
        if(horizontalPaging)c.restore();
        if(textSelection!=null&&pageText!=null&&textAnchorGlyph>=0&&textFocusGlyph>=0)drawTextSelectionHandles(c);
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
        zoomBadge.draw(c,getWidth(),getResources().getDisplayMetrics().density,zoom);
    }
    private float[] selectionHandle(int glyph,boolean leading){
        ReaderDocument.Box b=pageText.tokens.get(glyph).box;
        return new float[]{left()+(leading?b.left:b.right)*pageWidth(),top(pageText.page)+b.bottom*pageWidth()};
    }
    private void drawTextSelectionHandles(Canvas canvas){
        int first=Math.min(textAnchorGlyph,textFocusGlyph),last=Math.max(textAnchorGlyph,textFocusGlyph);
        drawTextSelectionHandle(canvas,selectionHandle(first,true),true);
        drawTextSelectionHandle(canvas,selectionHandle(last,false),false);
    }
    private void drawTextSelectionHandle(Canvas canvas,float[] point,boolean leading){
        float direction=leading?-1:1;
        paint.setColor(0xff2475d6);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));
        canvas.drawLine(point[0],point[1]-dp(17),point[0],point[1]+dp(5),paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(point[0]+direction*dp(6),point[1]+dp(9),dp(6),paint);
    }
    private void sampleColor(float screenX,float screenY){
        sampleX=screenX;sampleY=screenY;sampleValid=false;if(layout==null)return;
        double documentY=scroll+screenY/pageWidth();int page=horizontalPaging?selectedPage:layout.pageAt(documentY);PageState p=cache.get(page);
        float x=inkX(screenX),y=horizontalPaging?(screenY-top(page))/pageWidth():(float)(documentY-layout.top(page));
        if(p==null||p.bitmap==null||p.failed||x<0||x>1||y<0||y>layout.height(page)){invalidate();return;}
        // Sample the same rendered PDF + committed ink composition as the page canvas.
        Bitmap pixel=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);
        try{Canvas c=new Canvas(pixel);c.drawColor(Color.WHITE);float w=pageWidth();c.translate(0.5f-x*w,0.5f-y*w);
            drawPdfLayer(c,p,0,0,w,(float)layout.height(page)*w);c.scale(w,w);
            for(InkPage.Stroke s:p.ink.strokes())drawStroke(c,s);
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
    private boolean startInk(float x,float y,boolean eraser,float pressure){
        if(layout==null||mode==0)return false;stopFling();int page=horizontalPaging?selectedPage:layout.pageAt(scroll+y/pageWidth());PageState p=cache.get(page);
        if(p==null||!p.readable||p.bitmap==null||p.failed)return false;
        double localY=(y-top(page))/pageWidth();float localX=inkX(x);if(localX<0||localX>1||localY<0||localY>layout.height(page))return false;
        writing=p;pressureSamples.begin(pressure);selectedPage=page;cursorX=x;cursorY=y;cursorVisible=eraser||mode==3;notifyHost();
        if(eraser||mode==TOOL_LOCAL_ERASER){localEraseDraft=new InkPage(p.ink.strokes());erased=new ArrayList<>(p.ink.strokes());eraseX=localX;eraseY=(float)localY;cursorVisible=true;localErase(localX,(float)localY);}
        else if(mode==TOOL_ERASER){wholeErasing=true;erased=new ArrayList<>(p.ink.strokes());eraseX=localX;eraseY=(float)localY;erase(localX,(float)localY);}
        else if(mode==TOOL_LASSO){
            if(selectedInkPage==p&&!selectedInk.isEmpty()){movingSelection=true;moveStartX=localX;moveStartY=(float)localY;moveOriginal=p.ink.strokes();erased=new ArrayList<>(moveOriginal);}
            else{clearInkSelection();writing=p;draft=new ArrayList<>();draftColor=0xffd97718;draftWidth=Math.max(.0015f,width);point(localX,(float)localY,1);}
        }else{draft=new ArrayList<>();draftPressure=new ArrayList<>();draftColor=mode==TOOL_MARKER?InkInput.markerColor(color):(color&0xffffff)|(mode==TOOL_PENCIL?0xb0000000:0xff000000);draftWidth=width;point(localX,(float)localY,pressure);}invalidate();return true;
    }
    private void point(float x,float y,float pressure){
        if(draft==null||writing==null||draft.size()/2>=InkPage.MAX_POINTS)return;x=Math.max(0,Math.min(1,x));y=Math.max(0,Math.min((float)layout.height(writing.number),y));int n=draft.size();
        if(n>=2&&Math.hypot(x-draft.get(n-2),y-draft.get(n-1))<0.0005){if(draftPressure!=null&&!draftPressure.isEmpty())draftPressure.set(draftPressure.size()-1,mode==TOOL_BALLPOINT||mode==TOOL_MARKER?1:pressureValue(pressure));return;}draft.add(x);draft.add(y);if(draftPressure!=null)draftPressure.add(mode==TOOL_BALLPOINT||mode==TOOL_MARKER?1:pressureValue(pressure));
    }
    private float pressureValue(float pressure){if(Float.isNaN(pressure)||Float.isInfinite(pressure))return 1;return Math.max(.15f,Math.min(1.5f,.2f+pressure*.9f));}
    private void erase(float x,float y){if(erased==null)return;float radius=dp(eraserDiameter)/2/pageWidth();for(int i=erased.size()-1;i>=0;i--)if(InkPage.hit(erased.get(i),eraseX,eraseY,x,y,radius))erased.remove(i);eraseX=x;eraseY=y;}
    private void localErase(float x,float y){if(localEraseDraft!=null){
        float radius=dp(eraserDiameter)/2/pageWidth();
        try{localEraseDraft.erasePartial(eraseX,eraseY,x,y,radius);erased=new ArrayList<>(localEraseDraft.strokes());}
        catch(IllegalArgumentException limit){host.error("本页笔迹较多，请缩小擦除范围或分次擦除");}
        eraseX=x;eraseY=y;
    }}
    private void moveSelection(float x,float y){if(!movingSelection||moveOriginal==null||writing==null)return;InkPage preview=new InkPage(moveOriginal);preview.transform(selectedInk,0,0,1,x-moveStartX,y-moveStartY);erased=new ArrayList<>(preview.strokes());}
    private void inkPoint(float x,float y,float pressure){
        if(localEraseDraft!=null)localErase(x,y);
        else if(wholeErasing)erase(x,y);
        else if(mode==TOOL_LASSO){if(movingSelection)moveSelection(x,y);else point(x,y,1);}
        else if(isShapeMode()){if(draft!=null){x=Math.max(0,Math.min(1,x));y=Math.max(0,Math.min((float)layout.height(writing.number),y));if(draft.size()>2){draft.set(2,x);draft.set(3,y);}else{draft.add(x);draft.add(y);}}}
        else point(x,y,pressure);
    }
    private void inkEvent(MotionEvent e,int index,boolean ending){if(writing==null||index<0)return;cursorX=e.getX(index);cursorY=e.getY(index);for(int h=0;h<e.getHistorySize();h++){float x=inkX(e.getHistoricalX(index,h)),y=inkY(e.getHistoricalY(index,h));inkPoint(x,y,pressureSamples.move(e.getHistoricalPressure(index,h)));}float x=inkX(e.getX(index)),y=inkY(e.getY(index));float pressure=e.getPressure(index);inkPoint(x,y,ending?pressureSamples.release(pressure):pressureSamples.move(pressure));invalidate();}
    public void finishGesture(){
        if(disposed)return;
        discardPendingFingerInk();twoFingerTap.reset();navigationActive=false;horizontalHidePending=false;
        if(writing!=null)try{
            if(mode==TOOL_LASSO&&!movingSelection&&draft!=null&&draft.size()>=6){float[] polygon=array(draft);selectedInk=writing.ink.select(polygon);selectedInkPage=selectedInk.isEmpty()?null:writing;}
            else if(isShapeMode()&&draft!=null&&draft.size()>=4){int kind=mode==TOOL_LINE?InkPage.KIND_LINE:mode==TOOL_RECTANGLE?InkPage.KIND_RECTANGLE:mode==TOOL_ELLIPSE?InkPage.KIND_ELLIPSE:InkPage.KIND_ARROW;writing.ink.add(InkPage.shape(kind,draftColor,draftWidth,draft.get(0),draft.get(1),draft.get(2),draft.get(3)));persist(writing);}
            else if(!isShapeMode()&&draft!=null&&!draft.isEmpty()){writing.ink.add(draftStroke());persist(writing);}
            else if(erased!=null&&!sameStrokeObjects(erased,writing.ink.strokes())){writing.ink.replace(erased);persist(writing);}
        }
        catch(IllegalArgumentException e){host.error("本页批注已达到容量上限");}
        cancelInk();
    }
    private static float[] array(List<Float> values){float[] result=new float[values.size()];for(int i=0;i<result.length;i++)result[i]=values.get(i);return result;}
    private boolean isShapeMode(){return mode>=TOOL_LINE&&mode<=TOOL_ARROW;}
    private InkPage.Stroke draftStroke(){float[] points=array(draft);float[] pressures=draftPressure!=null&&draftPressure.size()==points.length/2?array(draftPressure):null;return new InkPage.Stroke(draftColor,draftWidth,points,InkPage.KIND_FREEHAND,pressures);}
    private static boolean sameStrokeObjects(List<InkPage.Stroke> a,List<InkPage.Stroke> b){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(a.get(i)!=b.get(i))return false;return true;}
    private void cancelInk(){writing=null;draft=null;draftPressure=null;erased=null;localEraseDraft=null;wholeErasing=false;moveOriginal=null;movingSelection=false;touchWriting=false;cursorVisible=false;penRouter.reset();invalidate();}
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
        touchWriting=startInk(first.getX(),first.getY(),false,first.getPressure());
        if(touchWriting)for(int i=1;i<pendingFingerInk.size();i++)inkEvent(pendingFingerInk.get(i),0,false);
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
    private boolean textSelectionTouch(MotionEvent event){
        int action=event.getActionMasked();if(pageText==null)return true;
        if(action==MotionEvent.ACTION_DOWN){stopFling();cancelPageAnimation();float x=(event.getX()-left())/pageWidth(),y=(event.getY()-top(pageText.page))/pageWidth();
            if(pageText.tokens.isEmpty())return true;
            float handle=dp(24);
            boolean pickedHandle=false;
            if(textSelection!=null&&textAnchorGlyph>=0&&textFocusGlyph>=0){
                int first=Math.min(textAnchorGlyph,textFocusGlyph),last=Math.max(textAnchorGlyph,textFocusGlyph);
                float[] start=selectionHandle(first,true),end=selectionHandle(last,false);
                float startDistance=(float)Math.hypot(event.getX()-start[0],event.getY()-start[1]);
                float endDistance=(float)Math.hypot(event.getX()-end[0],event.getY()-end[1]);
                if(startDistance<handle||endDistance<handle){pickedHandle=true;textFocusGlyph=startDistance<=endDistance?first:last;textAnchorGlyph=startDistance<=endDistance?last:first;}
            }
            if(!pickedHandle){
                if(x<0||x>1||y<0||layout==null||y>layout.height(pageText.page))return true;
                textAnchorGlyph=PdfTextGeometry.nearestGlyph(pageText.tokens,x,y);
            }
            textStartX=textEndX=x;textStartY=textEndY=y;textSelectionDragging=true;textDraggingHandle=pickedHandle;
            if(pickedHandle){textHandleDownX=x;textHandleDownY=y;textHandleGlyph=pageText.tokens.get(textFocusGlyph).box;}
            else{ textHandleGlyph=null;textFocusGlyph=textAnchorGlyph;updateTextSelection();}return true;}
        if(action==MotionEvent.ACTION_POINTER_DOWN||action==MotionEvent.ACTION_CANCEL){textSelectionDragging=false;textDraggingHandle=false;textHandleGlyph=null;return true;}
        if(!textSelectionDragging)return true;
        if(action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP){float x=(event.getX()-left())/pageWidth(),y=(event.getY()-top(pageText.page))/pageWidth();
            if(!textDraggingHandle||Math.hypot((x-textStartX)*pageWidth(),(y-textStartY)*pageWidth())>dp(3)){
                if(textDraggingHandle&&textHandleGlyph!=null){float[] adjusted=PdfTextGeometry.dragPoint(x,y,textHandleDownX,textHandleDownY,textHandleGlyph);textEndX=adjusted[0];textEndY=adjusted[1];}
                else {textEndX=x;textEndY=y;}
                updateTextSelection();
            }
            if(action==MotionEvent.ACTION_UP){textSelectionDragging=false;textDraggingHandle=false;textHandleGlyph=null;if(textSelection!=null&&textSelectionListener!=null)textSelectionListener.selected(textSelection);performClick();}return true;}
        return true;
    }
    private void updateTextSelection(){
        if(textAnchorGlyph<0||pageText.tokens.isEmpty()){textSelection=null;invalidate();return;}
        textFocusGlyph=PdfTextGeometry.nearestGlyph(pageText.tokens,textEndX,textEndY);
        int first=Math.min(textAnchorGlyph,textFocusGlyph),last=Math.max(textAnchorGlyph,textFocusGlyph);
        int start=pageText.tokens.get(first).start,end=pageText.tokens.get(last).end;
        textSelection=new TextSelection(pageText.page,pageText.text.substring(start,end),PdfTextGeometry.boxesForRange(pageText.tokens,first,last));invalidate();
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(layout==null||disposed)return true;int action=e.getActionMasked(),changed=e.getActionIndex();
        boolean hasPen=false,safe=e.getPointerCount()<=2;
        for(int i=0;i<e.getPointerCount();i++){
            boolean pen=InkInput.isPen(e.getToolType(i));hasPen|=pen;
            if(!pen&&!TouchNavigation.safeContact(e.getToolType(i),e.getTouchMajor(i),e.getSize(i),getResources().getDisplayMetrics().density))safe=false;
        }
        boolean touchAllowed=navigationGuard.allow(action,e.getEventTime(),hasPen,safe);
        if(!touchAllowed){twoFingerTap.reject();pinch.reset();stopFling();resetVelocity();navigationActive=false;horizontalHidePending=false;
            if(!hasPen&&penRouter.activeId<0){discardPendingFingerInk();cancelTouchDraft();cancelPageAnimation();blockUntilDown=true;return true;}}
        if(placingText){
            if(action==MotionEvent.ACTION_POINTER_DOWN){
                // Release the placement gesture to the normal pinch path. Its final UP must not place text.
                cancelTextPlacement();twoFingerTap.reset();blockUntilDown=false;
            }else{
                if(action==MotionEvent.ACTION_CANCEL)cancelTextPlacement();
                else if(action==MotionEvent.ACTION_UP){int page=horizontalPaging?selectedPage:layout.pageAt(scroll+e.getY()/pageWidth());float x=(e.getX()-left())/pageWidth(),y=(e.getY()-top(page))/pageWidth();if(x>=0&&x<=1&&y>=0&&y<=layout.height(page)&&textBoxListener!=null){placingText=false;textBoxListener.placed(page,x,y);}else host.error("请点击页面内放置文字");}
                return true;
            }
        }
        if(touchedTextBox!=null&&action==MotionEvent.ACTION_POINTER_DOWN)touchedTextBox=null;
        if(mode==TOOL_BROWSE&&textBoxListener!=null&&e.getPointerCount()==1){
            if(action==MotionEvent.ACTION_DOWN){int page=horizontalPaging?selectedPage:layout.pageAt(scroll+e.getY()/pageWidth());float x=(e.getX()-left())/pageWidth(),y=(e.getY()-top(page))/pageWidth();List<ReaderDocument.TextBox> boxes=readerDocument.textBoxes(page);for(int i=boxes.size()-1;i>=0;i--)if(PdfTextBoxes.hit(boxes.get(i),x,y)){touchedTextBox=boxes.get(i);textDownX=e.getX();textDownY=e.getY();return true;}}
            else if(touchedTextBox!=null&&action==MotionEvent.ACTION_UP){ReaderDocument.TextBox box=touchedTextBox;touchedTextBox=null;float dx=(e.getX()-textDownX)/pageWidth(),dy=(e.getY()-textDownY)/pageWidth();if(Math.hypot(e.getX()-textDownX,e.getY()-textDownY)>dp(7)){float maxX=Math.max(0,1-PdfTextBoxes.estimatedWidth(box)),maxY=Math.max(0,pageHeight(box.page)-PdfTextBoxes.lines(box.text).size()*PdfTextBoxes.lineHeight(box));textBoxListener.moved(box,Math.max(0,Math.min(maxX,box.x+dx)),Math.max(0,Math.min(maxY,box.y+dy)));}else textBoxListener.selected(box);return true;}
            else if(touchedTextBox!=null&&action==MotionEvent.ACTION_CANCEL){touchedTextBox=null;return true;}
            else if(touchedTextBox!=null)return true;
        }
        if(pageAnimator!=null){twoFingerTap.reset();blockUntilDown=true;return true;}
        if(picking){twoFingerTap.reset();discardPendingFingerInk();return pickingTouch(e);}
        if(textSelecting){twoFingerTap.reset();discardPendingFingerInk();return textSelectionTouch(e);}
        boolean mayRecognize=touchAllowed&&twoFingerUndoEnabled&&penRouter.activeId<0&&(!blockUntilDown||action==MotionEvent.ACTION_DOWN||twoFingerTap.pairPending());
        if(mayRecognize&&trackTwoFingerTap(e)){
            discardPendingFingerInk();cancelInk();stopFling();resetVelocity();navigationActive=false;blockUntilDown=true;
            if(canUndo())undo();else host.error("当前页面没有可撤销的笔画");return true;
        }
        if(!mayRecognize)twoFingerTap.reset();
        int penIndex=e.findPointerIndex(penRouter.activeId);int route=penRouter.route(action,e.getPointerId(changed),e.getToolType(changed),true,penIndex>=0);
        if(route!=InkInput.TOUCH){
            twoFingerTap.reject();discardPendingFingerInk();navigationActive=false;horizontalHidePending=false;stopFling();resetVelocity();
            if(route==InkInput.BEGIN){cancelPageAnimation();cancelTouchDraft();penNavigation=mode==TOOL_BROWSE;
                downX=lastX=e.getX(changed);downY=lastY=e.getY(changed);axis=0;
                if(!penNavigation)startInk(e.getX(changed),e.getY(changed),e.getToolType(changed)==MotionEvent.TOOL_TYPE_ERASER,e.getPressure(changed));}
            else if(route==InkInput.MOVE){if(penNavigation)panPen(e,penIndex);else inkEvent(e,penIndex,false);}
            else if(route==InkInput.END){if(!penNavigation)inkEvent(e,penIndex,true);penNavigation=false;finishGesture();blockUntilDown=true;}
            else if(route==InkInput.CANCEL){cancelInk();blockUntilDown=true;}return true;
        }
        if(action==MotionEvent.ACTION_DOWN){
            pinch.reset();
            discardPendingFingerInk();blockUntilDown=false;multi=false;axis=0;scrollReported=false;horizontalHidePending=false;scrolledPixels=0;navigationActive=true;
            stopFling();resetVelocity();velocity=VelocityTracker.obtain();addVelocity(e);
            downX=lastX=e.getRawX();downY=lastY=e.getRawY();gestureViewY=e.getRawY()-e.getY();downPage=selectedPage;initialPan=panX;initialMaxPan=maxPan();
            if(!penOnly&&mode!=0){
                if(twoFingerUndoEnabled&&twoFingerTap.firstPending())pendingFingerInk.add(MotionEvent.obtain(e));
                else touchWriting=startInk(e.getX(),e.getY(),false,e.getPressure());
            }return true;
        }
        if(blockUntilDown){if(action==MotionEvent.ACTION_UP){navigationActive=false;reportDeferredScroll();}else if(action==MotionEvent.ACTION_CANCEL)horizontalHidePending=false;return true;}
        if(action==MotionEvent.ACTION_CANCEL){discardPendingFingerInk();twoFingerTap.reset();navigationActive=false;horizontalHidePending=false;cancelPageAnimation();cancelInk();resetVelocity();return true;}
        if(action==MotionEvent.ACTION_POINTER_DOWN){
            discardPendingFingerInk();cancelPageAnimation();cancelDetailRequests();cancelInk();multi=true;resetVelocity();
            float ox=e.getRawX()-e.getX(),oy=e.getRawY()-e.getY();
            pinch.begin(e.getPointerId(0),e.getPointerId(1),e.getX(0)+ox,e.getY(0)+oy,e.getX(1)+ox,e.getY(1)+oy);return true;
        }
        if(action==MotionEvent.ACTION_POINTER_UP){pinch.reset();blockUntilDown=true;navigationActive=false;return true;}
        if(e.getPointerCount()>=2&&action==MotionEvent.ACTION_MOVE){
            // Do not make tiny zoom/position changes while this can still be an undo tap.
            float ox=e.getRawX()-e.getX(),oy=e.getRawY()-e.getY();
            if(!pinch.move(e.getPointerId(0),e.getPointerId(1),e.getX(0)+ox,e.getY(0)+oy,e.getX(1)+ox,e.getY(1)+oy,twoFingerTap.pairPending()))return true;
            float fx=pinch.x,fy=pinch.y;
            double anchorY=horizontalPaging?(pinch.oldY-oy-top(selectedPage))/pageWidth():scroll+(pinch.oldY-oy)/pageWidth();float anchorX=(pinch.oldX-ox-left())/pageWidth();zoom=(float)ZoomLimits.clamp(zoom*pinch.scale);
            panX=fx-ox-getWidth()/2f+(0.5f-anchorX)*pageWidth();
            if(horizontalPaging)panY=fy-oy-(getHeight()-(float)layout.height(selectedPage)*pageWidth())/2-(float)anchorY*pageWidth();
            else scroll=anchorY-(fy-oy)/pageWidth();
            gestureViewY=oy;updateViewport();return true;
        }
        addVelocity(e);
        if(action==MotionEvent.ACTION_MOVE){
            if(!pendingFingerInk.isEmpty()){
                pendingFingerInk.add(MotionEvent.obtain(e));
                if(twoFingerTap.firstPending())return true;
                flushPendingFingerInk();if(touchWriting)return true;
            }
            if(touchWriting){inkEvent(e,0,false);return true;}
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
                        horizontalHidePending=true;
                    }
                }
            }
            lastX=e.getRawX();lastY=e.getRawY();gestureViewY=e.getRawY()-e.getY();return true;
        }
        if(action==MotionEvent.ACTION_UP){
            navigationActive=false;flushPendingFingerInk();
            if(touchWriting){inkEvent(e,0,true);finishGesture();resetVelocity();return true;}
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
    private void panPen(MotionEvent event,int index){
        if(index<0)return;float x=event.getX(index),y=event.getY(index);
        if(axis==0&&Math.hypot(x-downX,y-downY)>dp(7)){axis=2;lastX=x;lastY=y;}
        if(axis!=0){if(horizontalPaging){panX+=x-lastX;panY+=y-lastY;}else{if(zoom>1.001f)panX+=x-lastX;scroll-=(y-lastY)/pageWidth();}updateViewport();}
        lastX=x;lastY=y;
    }
    @Override public boolean onHoverEvent(MotionEvent event){
        if(InkInput.isPen(event.getToolType(0))){navigationGuard.pen(event.getEventTime());stopFling();pinch.reset();
            if(penRouter.activeId<0){discardPendingFingerInk();cancelTouchDraft();cancelPageAnimation();blockUntilDown=true;navigationActive=false;horizontalHidePending=false;}}
        return super.onHoverEvent(event);
    }
    private void cancelTouchDraft(){writing=null;draft=null;draftPressure=null;erased=null;localEraseDraft=null;wholeErasing=false;moveOriginal=null;movingSelection=false;touchWriting=false;}
    @Override public boolean performClick(){super.performClick();return true;}
    public void pause(){finishGesture();stopFling();cancelPageAnimation();cancelColorPick();}
    public void dispose(){pause();disposed=true;cancelDetailRequests();renderWorker.shutdownNow();resetVelocity();cache.clear();clearDetailCache();strokeCache.clear();}
}
