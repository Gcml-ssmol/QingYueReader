package cn.qingyue.pdf;

import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends ThemedActivity {
    private int BG,INK,MUTED,ACCENT;
    private static final int PICK=10,CREATE_BACKUP=11,OPEN_BACKUP=12,EXPORT_PDF=13,EXPORT_PNG=14,PICK_PDF_TEXT=15;
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static final ConcurrentHashMap<String,PendingImportOffer> pendingImportOffers=new ConcurrentHashMap<>();
    private static final Set<String> deletingIds=ConcurrentHashMap.newKeySet();
    private final ArrayList<LibraryItem> docs=new ArrayList<>();
    private final ArrayList<LibraryFolder> libraryFolders=new ArrayList<>();
    private final ArrayList<LibraryItem> visibleDocs=new ArrayList<>();
    private final LinkedHashSet<String> selectedIds=new LinkedHashSet<>();
    private LinearLayout root, batchBar;
    private ListView list;
    private LibraryListAdapter libraryAdapter;
    private LinearLayout folderHost;
    private LinearLayout folderRows;
    private LibrarySidebar folderNavigation;
    private String folderStamp="";
    private LibraryThumbnailLoader thumbnails;
    private TextView counter, pageLabel, readerStatus;
    private IconButton prev, next;
    private final ArrayList<IconButton> inkButtons=new ArrayList<>();
    private IconButton undoButton,redoButton,stylusButton;
    private ColorSwatch colorButton;
    private View readerHeader;
    private IconButton headerToggle;
    private boolean headerCollapsed,twoFingerUndo=true,horizontalPaging;
    private PdfSearchDialog searchDialog;
    private ReaderActions readerActions;
    private DocumentEditLease editLease;
    private IconButton pdfSidebarToggle;
    private boolean pdfOpening;
    private PdfInsertJob inserting;
    private static final ConcurrentHashMap<String,PdfInsertJob> INSERTIONS=new ConcurrentHashMap<>();
    private static final class PdfInsertJob {
        final String documentId;final int oldPage,index,count;
        volatile boolean finished;volatile PdfPageInsertion.Result result;volatile Throwable failure;
        volatile java.lang.ref.WeakReference<MainActivity> owner=new java.lang.ref.WeakReference<>(null);
        PdfInsertJob(String id,int page,int index,int count){this.documentId=id;this.oldPage=page;this.index=index;this.count=count;}
    }
    private PdfPageSidebar pdfPageSidebar;
    private LinearLayout pdfReadingBody;
    private boolean pdfPagesExpanded;
    private Dialog exportProgress;
    private String exportDocumentId;
    private int exportFrom,exportTo;
    private boolean exportSigned;
    private Uri pendingPickerDestination;
    private int pendingPickerKind;
    private ReaderSourceLink pendingSource;
    private Dialog documentDialog,readerDialog;
    private DocumentDialogs.TaskDialog importDialog;
    private String readerQuery="";
    private PopupWindow toolPopup;
    private int inkMode=0,inkColor=ToolPreferences.BLACK;
    private float inkWidth=0.006f;
    private float markerWidth=0.03f,ballpointWidth=0.006f,pencilWidth=0.008f,eraserSize=28f,localEraserSize=28f;
    private final float[] shapeWidths=new float[10];
    private final ArrayList<Integer> recentColors=new ArrayList<>();
    private boolean stylusOnly=true;
    private PdfReaderView pdfView;
    private LibraryItem current;
    private int requestedPage, generation,visibleFolderCount;
    private boolean dead,initialized,librarySaveAllowed=true,batchMode,trashMode,reloadLibraryOnResume;
    private String filter="";
    private String selectedFolder="",selectedTag="",lastOpenedNoteId="",libraryWarning="",libraryRevision="";
    private String pendingImportOfferId="";
    private Bundle pendingStartupState;
    private boolean favoritesOnly;
    private LibraryQuery.Sort librarySort=LibraryQuery.Sort.MODIFIED;
    private ImportSession importSession;
    private final ImportSession.Listener importListener=this::importChanged;
    private DocumentDialogs.TaskDialog backupDialog;
    private BackupCoordinator.Session backupSession;
    private final BackupCoordinator.Listener backupListener=this::backupChanged;
    private static final class PendingImportOffer {
        final Uri uri;final String text,name;final boolean convertible;
        PendingImportOffer(Uri uri,String text,String name,boolean convertible){this.uri=uri;this.text=text;this.name=name;this.convertible=convertible;}
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        CrashDiagnostics.markStage("启动");
        pendingStartupState=state;
        if(state!=null){pendingPickerDestination=state.getParcelable("pendingPickerDestination");pendingPickerKind=state.getInt("pendingPickerKind");}
        BG=AppColors.BACKGROUND;INK=AppColors.INK;MUTED=AppColors.MUTED;ACCENT=AppColors.ACCENT_DARK;
        thumbnails=new LibraryThumbnailLoader(this);
        stylusOnly=getPreferences(0).getBoolean("stylusOnly",true);
        twoFingerUndo=getPreferences(0).getBoolean("twoFingerUndo",true);
        horizontalPaging=getPreferences(0).getBoolean("horizontalPaging",false);
        inkColor=ToolPreferences.color(getPreferences(0),"pen","inkColor",ToolPreferences.BLACK);
        inkWidth=ToolSize.clamp(1,getPreferences(0).getFloat("penSize",0.006f));
        markerWidth=ToolSize.clamp(2,getPreferences(0).getFloat("markerSize",0.03f));
        ballpointWidth=ToolSize.clamp(1,getPreferences(0).getFloat("ballpointSize",0.006f));
        pencilWidth=ToolSize.clamp(1,getPreferences(0).getFloat("pencilSize",0.008f));
        eraserSize=ToolSize.clamp(3,getPreferences(0).getFloat("eraserSize",28f));
        localEraserSize=ToolSize.clamp(3,getPreferences(0).getFloat("localEraserSize",28f));
        for(int mode=6;mode<=9;mode++)shapeWidths[mode]=ToolSize.clamp(1,getPreferences(0).getFloat(ToolPreferences.pdfSizeKey(mode),0.006f));
        try{JSONArray history=new JSONArray(getPreferences(0).getString("recentColors","[]"));for(int i=0;i<Math.min(6,history.length());i++)recentColors.add(history.getInt(i)|0xff000000);}catch(JSONException ignored){}
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(android.os.Build.VERSION.SDK_INT>=27?BG:AppColors.INK);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|(android.os.Build.VERSION.SDK_INT>=27?View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0));
        setupRoot();TextView starting=text("正在检查资料库…",15,INK);starting.setGravity(Gravity.CENTER);root.addView(starting,new LinearLayout.LayoutParams(-1,0,1));
        final Context app=getApplicationContext();
        worker.execute(()->{String notice="";try{PdfPageInsertion.awaitRecovery(app);}catch(IOException recoveryFailure){notice="插页事务恢复未完成："+safeMessage(recoveryFailure);}ImportCoordinator.cleanupStale(app);try{TrashManager.RecoveryResult recovered=TrashManager.recoverPending(app);if(recovered.rolledBack>0)notice="已恢复 "+recovered.rolledBack+" 个未完成的删除事务";if(recovered.retained>0)notice="有 "+recovered.retained+" 个删除事务需要手动检查，原文件已保留";}catch(IOException recoveryFailure){notice="删除事务自动恢复未完成，暂存文件已保留";}final String message=notice;runOnUiThread(()->{if(!dead)finishStartup(state,message);});});
    }
    private void finishStartup(Bundle state,String deleteRecoveryNotice){
        initialized=true;
        pendingStartupState=null;
        load(); showLibrary();if(!deleteRecoveryNotice.isEmpty())toast(deleteRecoveryNotice);boolean internalJump=handleInternalJump(getIntent());
        if(state!=null&&!internalJump) {
            filter=state.getString("libraryFilter","");selectedFolder=state.getString("libraryFolder","");selectedTag=state.getString("libraryTag","");
            if(!selectedFolder.isEmpty()&&!LibrarySidebar.UNFILED.equals(selectedFolder)&&libraryFolder(selectedFolder)==null){
                String oldName=selectedFolder;selectedFolder="";
                for(LibraryFolder folder:libraryFolders)if(folder.parentId.isEmpty()&&folder.name.equals(oldName)){selectedFolder=folder.id;break;}
            }
            favoritesOnly=state.getBoolean("libraryFavorites",false);trashMode=state.getBoolean("libraryTrash",false);batchMode=state.getBoolean("libraryBatch",false);
            try{librarySort=LibraryQuery.Sort.valueOf(state.getString("librarySort",LibraryQuery.Sort.MODIFIED.name()));}catch(Exception ignored){}
            ArrayList<String> selected=state.getStringArrayList("librarySelection");if(selected!=null)selectedIds.addAll(selected);
            String id=state.getString("doc");
            for(LibraryItem d:docs) if(d.id.equals(id)&&!d.isTrashed()) { open(d); break; }
            if(current!=null){setHeaderCollapsed(state.getBoolean("headerCollapsed",false));readerQuery=state.getString("readerQuery","");}
            exportDocumentId=state.getString("exportDocumentId");exportFrom=state.getInt("exportFrom");exportTo=state.getInt("exportTo");exportSigned=state.getBoolean("exportSigned");
            String importId=state.getString("importSession");if(importId!=null)attachImport(ImportCoordinator.find(importId));
            pendingImportOfferId=state.getString("pendingImportOffer","");
            if(current==null)showLibrary();
            if(importSession==null&&!pendingImportOfferId.isEmpty())showPendingImportOffer();
        } else if(!internalJump) {
            resumeNewestImport();if(!openSourceIntent(getIntent()))handleIncomingIntent(getIntent());
        }
        String backupId=state==null?null:state.getString("backupSession");
        BackupCoordinator.Session retained=BackupCoordinator.find(backupId);
        if(retained==null)retained=BackupCoordinator.latest();
        attachBackup(retained);
        if(state!=null&&!internalJump)openSourceIntent(getIntent());
        if(pendingPickerDestination!=null){Uri destination=pendingPickerDestination;int kind=pendingPickerKind;pendingPickerDestination=null;pendingPickerKind=0;dispatchPickerResult(kind,destination);}
        root.postDelayed(this::promptNewFaultReport, 1200);
        root.postDelayed(this::promptNewFaultReport, 4000);
    }
    private int dp(float n) {return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable shape(int color,int radius) {
        GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));
        if(!AppColors.isClassic()&&radius>0&&color==AppColors.SURFACE)g.setStroke(dp(1),AppColors.BORDER);
        return g;
    }
    private TextView text(String s,int sp,int color) {
        TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);return t;
    }
    private Button button(String s, boolean primary) {
        Button b=new Button(this);b.setText(s);b.setTextSize(14);b.setAllCaps(false);
        b.setTextColor(primary?AppColors.ON_ACCENT:ACCENT);b.setBackground(shape(primary?AppColors.ACCENT:AppColors.isClassic()?AppColors.SOFT:AppColors.SURFACE,14));
        b.setMinHeight(dp(48));b.setMinimumHeight(dp(48)); b.setPadding(dp(16),0,dp(16),0);return b;
    }
    private LinearLayout column() {LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row() {LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void space(LinearLayout l,int height) {View v=new View(this);l.addView(v,new LinearLayout.LayoutParams(1,dp(height)));}
    private void setupRoot() {
        root=column();root.setBackground(current==null?new WarmBackdrop(false):shape(AppColors.SURFACE,0));
        root.setFocusableInTouchMode(true);
        final int side=current==null?dp(20):0;final int vertical=current==null?dp(12):0;
        root.setPadding(side,vertical,side,0);
        root.setOnApplyWindowInsetsListener((v,i)->{
            v.setPadding(side+i.getSystemWindowInsetLeft(),vertical+i.getSystemWindowInsetTop(),
                    side+i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i.consumeSystemWindowInsets();
        });
        setContentView(root);root.requestApplyInsets();
    }
    private void showLibrary() { showLibrary(false); }
    private void showLibrary(boolean afterDelete) {
        if(inserting!=null&&!inserting.finished){toast("正在插入页面，请稍候");return;}
        CrashDiagnostics.markStage(afterDelete?"删除刷新":"资料库");
        if(libraryAdapter!=null){libraryAdapter.dispose();libraryAdapter=null;}
        if(current!=null)refreshModified(current);
        View focused=getCurrentFocus();if(focused!=null)focused.clearFocus();
        generation++;releaseView();pdfOpening=false;current=null;load();setupRoot();
        boolean wideLibrary=getResources().getConfiguration().smallestScreenWidthDp>=600;
        LinearLayout content=column();content.setPadding(0,dp(6),0,dp(12));
        boolean stackedHeader=getResources().getConfiguration().screenWidthDp-(wideLibrary?278:0)<360||getResources().getConfiguration().fontScale>1.3f;
        LinearLayout header=stackedHeader?column():row();LinearLayout titles=column();LinearLayout brand=row();
        TextView name=text("晴阅",31,AppColors.isClassic()?INK:ACCENT);name.setTypeface(AppColors.isClassic()?Typeface.DEFAULT:Typeface.SERIF,Typeface.BOLD);brand.addView(name);
        if(AppColors.isClassic()){TextView pdfTag=text("阅读 · 笔记",12,ACCENT);pdfTag.setTypeface(null,1);pdfTag.setPadding(dp(9),dp(4),dp(9),dp(4));pdfTag.setBackground(shape(AppColors.SOFT,8));LinearLayout.LayoutParams tag=new LinearLayout.LayoutParams(-2,-2);tag.leftMargin=dp(10);brand.addView(pdfTag,tag);}
        titles.addView(brand);if(!wideLibrary||!AppColors.isClassic()){space(titles,4);titles.addView(text("把阅读，留给自己",13,MUTED));}header.addView(titles,stackedHeader?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1));
        TextView local=text("本地 · 离线",11,ACCENT);local.setPadding(dp(13),dp(8),dp(13),dp(8));local.setBackground(shape(AppColors.SOFT,18));LinearLayout.LayoutParams localParams=new LinearLayout.LayoutParams(-2,-2);if(stackedHeader)localParams.topMargin=dp(10);header.addView(local,localParams);content.addView(header);space(content,wideLibrary?10:trashMode?12:18);
        if(!libraryWarning.isEmpty()){
            LinearLayout warning=row();warning.setPadding(dp(13),dp(10),dp(9),dp(10));warning.setBackground(shape(0xffffeadf,14));TextView message=text(libraryWarning,12,0xff9b463c);message.setMaxLines(3);warning.addView(message,new LinearLayout.LayoutParams(0,-2,1));
            Button repair=button("扫描修复",false);repair.setMinHeight(dp(40));repair.setMinimumHeight(dp(40));repair.setOnClickListener(v->rebuildLibrary());warning.addView(repair,new LinearLayout.LayoutParams(-2,-2));content.addView(warning);space(content,12);
        }
        int activeCount=0;LibraryItem recent=null;for(LibraryItem d:docs)if(!d.isTrashed()){activeCount++;if(recent==null||d.opened>recent.opened)recent=d;}
        if(trashMode){TextView title=text("最近删除",18,INK);title.setTypeface(null,1);content.addView(title);space(content,12);}
        else if(!AppColors.isClassic()){
            content.addView(readingHero(recent,wideLibrary));space(content,14);
        }else if(activeCount==0){
            LinearLayout hero=column();hero.setPadding(dp(18),dp(15),dp(18),dp(17));hero.setBackground(new WarmBackdrop(true));LinearLayout heroRow=row();FolderArtView art=new FolderArtView(this);heroRow.addView(art,new LinearLayout.LayoutParams(dp(76),dp(82)));
            LinearLayout copy=column();copy.setPadding(dp(14),0,0,0);TextView welcome=text("你的随身文档库",21,INK);welcome.setTypeface(null,1);copy.addView(welcome);space(copy,7);copy.addView(text("读一份文档，记下一点灵感。",13,MUTED));heroRow.addView(copy,new LinearLayout.LayoutParams(0,-2,1));hero.addView(heroRow);space(hero,8);hero.addView(text("文档、笔记与批注默认只保存在本机",11,MUTED));content.addView(hero);space(content,12);
        } else if(recent!=null&&!wideLibrary){
            final LibraryItem resume=recent;LinearLayout continueCard=row();continueCard.setPadding(dp(14),dp(10),dp(10),dp(10));continueCard.setBackground(shape(AppColors.SOFT,16));
            LinearLayout copy=column();TextView caption=text("继续上次",11,ACCENT);caption.setTypeface(null,1);copy.addView(caption);TextView resumeName=text(resume.name,15,INK);resumeName.setTypeface(null,1);resumeName.setSingleLine(true);resumeName.setEllipsize(TextUtils.TruncateAt.END);copy.addView(resumeName);continueCard.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
            IconButton go=icon("right","打开 "+resume.name,v->open(resume));continueCard.addView(go,new LinearLayout.LayoutParams(dp(42),dp(44)));continueCard.setOnClickListener(v->open(resume));content.addView(continueCard);space(content,11);
        }
        if(!trashMode&&AppColors.isClassic()){LinearLayout createRow=row();Button note=button("＋ 新建笔记",true);note.setOnClickListener(v->createNote());createRow.addView(note,new LinearLayout.LayoutParams(0,-2,1));
            Button add=button("导入",false);add.setBackground(shape(AppColors.SURFACE,15));add.setOnClickListener(v->pick());LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,-2,1);ap.leftMargin=dp(8);createRow.addView(add,ap);
            content.addView(createRow);space(content,11);}
        folderRows=column();
        LinearLayout searchBox=row();searchBox.setPadding(dp(9),0,dp(14),0);searchBox.setBackground(shape(AppColors.SURFACE,17));IconButton searchIcon=new IconButton(this,"search","搜索文档");searchIcon.setClickable(false);searchIcon.setFocusable(false);searchBox.addView(searchIcon,new LinearLayout.LayoutParams(dp(38),dp(46)));
        EditText search=new EditText(this);search.setSingleLine(true);search.setTextSize(14);search.setTextColor(INK);search.setHint(trashMode?"搜索最近删除":"搜索名称、文件夹或标签");search.setHintTextColor(MUTED);search.setBackgroundColor(Color.TRANSPARENT);search.setPadding(dp(3),0,0,0);search.setText(filter);search.setSelection(search.length());searchBox.addView(search,new LinearLayout.LayoutParams(0,dp(50),1));content.addView(searchBox);space(content,10);
        if(!trashMode){HorizontalScrollView chips=new HorizontalScrollView(this);chips.setHorizontalScrollBarEnabled(false);LinearLayout chipRow=row();chipRow.setPadding(0,0,dp(8),0);
            if(!AppColors.isClassic()){Button all=chip("全部资料",selectedFolder.isEmpty()&&!favoritesOnly&&selectedTag.isEmpty()&&filter.isEmpty(),v->{favoritesOnly=false;filter="";selectedTag="";selectedFolder="";showLibrary();});LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-2,dp(40));ap.rightMargin=dp(7);chipRow.addView(all,ap);}
            chipRow.addView(chip(favoritesOnly?"★ 已收藏":"☆ 收藏",favoritesOnly,v->{favoritesOnly=!favoritesOnly;showLibrary();}));
            LinearLayout.LayoutParams tg=new LinearLayout.LayoutParams(-2,dp(40));tg.leftMargin=dp(7);Button tagChip=chip(selectedTag.isEmpty()?"标签":"标签 · "+selectedTag,!selectedTag.isEmpty(),v->chooseTag());chipRow.addView(tagChip,tg);
            LinearLayout.LayoutParams searchp=new LinearLayout.LayoutParams(-2,dp(40));searchp.leftMargin=dp(7);chipRow.addView(chip("搜索正文",false,v->openGlobalSearch()),searchp);
            LinearLayout.LayoutParams clearp=new LinearLayout.LayoutParams(-2,dp(40));clearp.leftMargin=dp(7);Button clear=chip("清除筛选",false,v->{favoritesOnly=false;filter="";selectedTag="";showLibrary();});chipRow.addView(clear,clearp);chips.addView(chipRow);content.addView(chips);space(content,9);}
        LinearLayout listHeading=row();counter=text("",14,INK);counter.setTypeface(null,1);listHeading.addView(counter,new LinearLayout.LayoutParams(0,dp(42),1));
        if(!trashMode){Button sort=chip(sortLabel(),false,v->chooseSort());listHeading.addView(sort,new LinearLayout.LayoutParams(-2,dp(40)));Button batch=chip(batchMode?"完成":"批量",batchMode,v->{batchMode=!batchMode;if(!batchMode)selectedIds.clear();showLibrary();});LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,dp(40));bp.leftMargin=dp(6);listHeading.addView(batch,bp);}content.addView(listHeading);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){filter=s.toString();populate();}public void afterTextChanged(Editable e){}});
        batchBar=row();batchBar.setPadding(dp(10),dp(6),dp(8),dp(6));batchBar.setBackground(shape(AppColors.SOFT,14));Button batchAction=button("整理所选",true);batchAction.setOnClickListener(v->showBatchActions());batchBar.addView(batchAction,new LinearLayout.LayoutParams(0,-2,1));Button selectAll=button("全选当前",false);selectAll.setOnClickListener(v->{for(LibraryItem item:visibleDocs)selectedIds.add(item.id);populate();});LinearLayout.LayoutParams sap=new LinearLayout.LayoutParams(-2,-2);sap.leftMargin=dp(8);batchBar.addView(selectAll,sap);content.addView(batchBar);space(content,7);
        content.addView(folderRows);space(content,6);
        list=new ListView(this);list.setDivider(null);list.setDividerHeight(dp(10));list.setSelector(new ColorDrawable(Color.TRANSPARENT));list.setClipToPadding(false);list.setPadding(0,0,0,dp(24));list.addHeaderView(content,null,false);libraryAdapter=new LibraryListAdapter(list,trashMode);list.setAdapter(libraryAdapter);
        folderNavigation=new LibrarySidebar(this,wideLibrary,(folder,trash)->{selectedFolder=folder;filter="";trashMode=trash;if(trash){favoritesOnly=false;selectedTag="";}batchMode=false;selectedIds.clear();showLibrary();});
        folderHost=column();folderStamp="";View folders=folderHost;
        if(wideLibrary){LinearLayout body=row();body.setGravity(Gravity.TOP);LinearLayout.LayoutParams side=new LinearLayout.LayoutParams(dp(220),-1);side.rightMargin=dp(18);body.addView(folders,side);body.addView(list,new LinearLayout.LayoutParams(0,-1,1));root.addView(body,new LinearLayout.LayoutParams(-1,0,1));}
        else{root.addView(folders,new LinearLayout.LayoutParams(-1,dp(47)));root.addView(list,new LinearLayout.LayoutParams(-1,0,1));}
        if(AppColors.isClassic()){
        LinearLayout settingsDock=row();
        settingsDock.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        settingsDock.setPadding(0,dp(5),0,dp(5));
        IconButton settings=icon("gear","设置",v->showLibraryTools());
        settings.setBackground(shape(AppColors.SURFACE,18));
        settings.setElevation(dp(3));
        settingsDock.addView(settings,new LinearLayout.LayoutParams(dp(54),dp(54)));
        root.addView(settingsDock,new LinearLayout.LayoutParams(-1,dp(64)));
        }else root.addView(libraryDock(),new LinearLayout.LayoutParams(-1,dp(70)));
        populate();root.requestFocus();attachImport(importSession);
    }

    private View readingHero(LibraryItem recent,boolean wide){
        LinearLayout hero=column();hero.setPadding(dp(16),dp(16),dp(16),dp(13));hero.setBackground(new WarmBackdrop(true));
        LinearLayout top=row(),copy=column();TextView title=text("你的随身文档库",wide?24:21,INK);title.setTypeface(Typeface.SERIF,Typeface.BOLD);copy.addView(title);space(copy,6);
        copy.addView(text("读一份文档，记下一点灵感。",13,INK));space(copy,10);
        TextView detail=text("文档、笔记与批注保存在本机\n随时阅读，安心记录。",11,MUTED);detail.setLineSpacing(dp(3),1f);copy.addView(detail);
        top.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        int width=getResources().getConfiguration().screenWidthDp;boolean largeText=getResources().getConfiguration().fontScale>1.3f;
        if(width>=350&&!largeText){ReadingArtView art=new ReadingArtView(this);top.addView(art,new LinearLayout.LayoutParams(dp(wide?122:90),dp(wide?135:119)));}
        hero.addView(top);space(hero,12);LinearLayout actions=largeText?column():row();
        Button resume=button(recent==null?"导入第一份文档":"继续阅读  ›",true);resume.setTextSize(13);resume.setPadding(dp(8),0,dp(8),0);resume.setOnClickListener(v->{if(recent==null)pick();else open(recent);});
        if(recent!=null)resume.setContentDescription("继续打开 "+recent.name);
        actions.addView(resume,largeText?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1.3f));
        Button note=button("＋ 新建笔记",false);note.setTextSize(12);note.setPadding(dp(4),0,dp(4),0);note.setOnClickListener(v->createNote());LinearLayout.LayoutParams np=largeText?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1.1f);if(largeText)np.topMargin=dp(7);else np.leftMargin=dp(7);actions.addView(note,np);
        if(recent!=null){Button add=button("导入",false);add.setTextSize(12);add.setPadding(dp(4),0,dp(4),0);add.setOnClickListener(v->pick());LinearLayout.LayoutParams ap=largeText?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,.8f);if(largeText)ap.topMargin=dp(7);else ap.leftMargin=dp(7);actions.addView(add,ap);}
        hero.addView(actions);return hero;
    }
    private View libraryDock(){
        LinearLayout dock=row();dock.setPadding(dp(3),dp(5),dp(3),dp(3));dock.setBackground(shape(AppColors.SURFACE,22));
        dockItem(dock,"book","资料库",!trashMode,()->{trashMode=false;batchMode=false;selectedFolder="";selectedTag="";filter="";favoritesOnly=false;selectedIds.clear();showLibrary();});
        dockItem(dock,"search","全库搜索",false,this::openGlobalSearch);
        dockItem(dock,"trash","最近删除",trashMode,()->{trashMode=true;batchMode=false;favoritesOnly=false;filter="";selectedFolder="";selectedTag="";selectedIds.clear();showLibrary();});
        dockItem(dock,"gear","设置",false,this::showLibraryTools);return dock;
    }
    private void dockItem(LinearLayout parent,String kind,String label,boolean selected,Runnable action){
        LinearLayout cell=column();cell.setGravity(Gravity.CENTER);cell.setClickable(true);cell.setFocusable(true);cell.setContentDescription(label);cell.setSelected(selected);cell.setOnClickListener(v->action.run());
        IconButton glyph=new IconButton(this,kind,label);glyph.setTint(selected?AppColors.ACCENT:AppColors.MUTED);glyph.setClickable(false);glyph.setFocusable(false);glyph.setLongClickable(false);glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);cell.addView(glyph,new LinearLayout.LayoutParams(dp(36),dp(36)));
        TextView text=text(label,10,selected?AppColors.ACCENT_DARK:MUTED);text.setTypeface(null,selected?Typeface.BOLD:Typeface.NORMAL);text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);cell.addView(text);
        parent.addView(cell,new LinearLayout.LayoutParams(0,-1,1));
    }
    private void populate() {
        if(current!=null||list==null)return;ArrayList<LibraryItem> source=new ArrayList<>();for(LibraryItem item:docs)if(item.isTrashed()==trashMode)source.add(item);
        refreshFolderNavigation();
        boolean searching=!filter.trim().isEmpty();
        LibraryQuery.Filter wanted=new LibraryQuery.Filter().keyword(filter).folderId(!trashMode&&!searching&&!LibrarySidebar.UNFILED.equals(selectedFolder)?selectedFolder:"").rootOnly(!trashMode&&!searching&&selectedFolder.isEmpty()).unfiledOnly(!trashMode&&!searching&&LibrarySidebar.UNFILED.equals(selectedFolder)).tag(selectedTag).favoriteOnly(favoritesOnly).includeTrashed(true);
        visibleDocs.clear();visibleDocs.addAll(LibraryQuery.apply(source,wanted,librarySort));
        refreshFolderRows();
        counter.setText((trashMode?"最近删除":"我的资料")+"  ·  "+(visibleFolderCount>0?visibleFolderCount+" 个文件夹 · ":"")+visibleDocs.size()+" 份资料");
        selectedIds.retainAll(idsOf(docs));batchBar.setVisibility(batchMode&&!visibleDocs.isEmpty()?View.VISIBLE:View.GONE);if(libraryAdapter!=null)libraryAdapter.publish(visibleDocs,!docs.isEmpty());
    }
    private void refreshFolderNavigation(){
        if(folderHost==null||folderNavigation==null)return;
        StringBuilder stamp=new StringBuilder(selectedFolder).append('|').append(trashMode);
        for(LibraryItem item:docs)stamp.append('|').append(item.id).append(':').append(item.folderId).append(':').append(item.isTrashed());
        for(LibraryFolder folder:libraryFolders)stamp.append('|').append(folder.id).append(':').append(folder.parentId).append(':').append(folder.name);
        String next=stamp.toString();if(next.equals(folderStamp))return;folderStamp=next;
        folderHost.removeAllViews();folderHost.addView(folderNavigation.build(docs,libraryFolders,selectedFolder,trashMode),new LinearLayout.LayoutParams(-1,-1));
    }
    private LibraryFolder libraryFolder(String id){for(LibraryFolder folder:libraryFolders)if(folder.id.equals(id))return folder;return null;}
    private String folderPath(String id){
        ArrayList<String> names=new ArrayList<>();HashSet<String> seen=new HashSet<>();
        while(id!=null&&!id.isEmpty()&&seen.add(id)){LibraryFolder folder=libraryFolder(id);if(folder==null)break;names.add(0,folder.name);id=folder.parentId;}
        return android.text.TextUtils.join(" › ",names);
    }
    private void refreshFolderRows(){
        visibleFolderCount=0;if(folderRows==null)return;folderRows.removeAllViews();if(trashMode)return;
        LinearLayout heading=row();
        if(!selectedFolder.isEmpty()&&!LibrarySidebar.UNFILED.equals(selectedFolder)){
            LibraryFolder currentFolder=libraryFolder(selectedFolder);
            Button up=chip("‹ 返回上一级",false,v->{selectedFolder=currentFolder==null?"":currentFolder.parentId;showLibrary();});heading.addView(up);
            TextView crumb=text("全部资料 › "+folderPath(selectedFolder),14,INK);crumb.setSingleLine(true);crumb.setEllipsize(TextUtils.TruncateAt.START);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);cp.leftMargin=dp(9);heading.addView(crumb,cp);
        }else if(selectedFolder.isEmpty()){
            TextView crumb=text("全部资料",16,INK);crumb.setTypeface(null,1);heading.addView(crumb,new LinearLayout.LayoutParams(0,-2,1));
        }else{
            TextView crumb=text("未分类",16,INK);crumb.setTypeface(null,1);heading.addView(crumb,new LinearLayout.LayoutParams(0,-2,1));
        }
        if(!LibrarySidebar.UNFILED.equals(selectedFolder)){
            Button create=chip("＋ 文件夹",false,v->createLibraryFolder());heading.addView(create);
        }
        folderRows.addView(heading);space(folderRows,8);
        if(LibrarySidebar.UNFILED.equals(selectedFolder)||!filter.trim().isEmpty())return;
        ArrayList<LibraryFolder> children=new ArrayList<>();for(LibraryFolder folder:libraryFolders)if(folder.parentId.equals(selectedFolder))children.add(folder);
        children.sort((a,b)->a.name.compareToIgnoreCase(b.name));
        visibleFolderCount=children.size();
        for(LibraryFolder folder:children){
            LinearLayout card=row();card.setPadding(dp(14),dp(7),dp(7),dp(7));card.setBackground(shape(AppColors.SURFACE,15));
            TextView name=text("📁  "+folder.name,15,INK);name.setTypeface(null,1);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);card.addView(name,new LinearLayout.LayoutParams(0,dp(43),1));name.setGravity(Gravity.CENTER_VERTICAL);
            IconButton more=icon("more","管理文件夹",v->manageLibraryFolder(folder));card.addView(more,new LinearLayout.LayoutParams(dp(44),dp(44)));
            card.setOnClickListener(v->{selectedFolder=folder.id;showLibrary();});folderRows.addView(card);space(folderRows,7);
        }
    }
    private boolean folderNameValid(String name,String parentId,String exceptId){
        if(!LibraryItem.hasVisibleText(name)||name.length()>100){toast("文件夹名称需为 1 至 100 个字符");return false;}
        for(LibraryFolder other:libraryFolders)if(other.parentId.equals(parentId)&&!other.id.equals(exceptId)&&other.name.equalsIgnoreCase(name)){toast("同级已有同名文件夹");return false;}
        return true;
    }
    private void createLibraryFolder(){
        if(!ensureWritable())return;
        String parent=selectedFolder;
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("文件夹名称");
        new AlertDialog.Builder(this).setTitle("新建文件夹").setView(input).setNegativeButton("取消",null).setPositiveButton("创建",(dialog,which)->{
            String name=input.getText().toString().trim();if(!folderNameValid(name,parent,""))return;
            libraryFolders.add(new LibraryFolder(UUID.randomUUID().toString(),parent,name));if(save())showLibrary();
        }).show();
    }
    private void renameLibraryFolder(LibraryFolder folder){
        if(!ensureWritable())return;
        EditText input=new EditText(this);input.setSingleLine(true);input.setText(folder.name);input.setSelection(input.length());
        new AlertDialog.Builder(this).setTitle("重命名文件夹").setView(input).setNegativeButton("取消",null).setPositiveButton("保存",(dialog,which)->{
            String name=input.getText().toString().trim();if(!folderNameValid(name,folder.parentId,folder.id))return;
            folder.name=name;for(LibraryItem item:docs)if(folder.id.equals(item.folderId))item.folder=name;
            if(save())showLibrary();
        }).show();
    }
    private void manageLibraryFolder(LibraryFolder folder){
        new AlertDialog.Builder(this).setTitle(folder.name).setItems(new String[]{"重命名","移动文件夹","删除空文件夹"},(dialog,index)->{
            if(index==0)renameLibraryFolder(folder);
            else if(index==1)moveLibraryFolder(folder);
            else if(ensureWritable()){
                try{LibraryStore.deleteEmptyFolder(this,folder.id,libraryRevision);load();showLibrary();toast("文件夹已删除，最近删除中的资料仍可恢复到"+(folder.parentId.isEmpty()?"未分类":"上一级"));}
                catch(IOException|RuntimeException failure){load();showLibrary();toast(safeMessage(failure));}
            }
        }).setNegativeButton("取消",null).show();
    }
    private void moveLibraryFolder(LibraryFolder folder){
        if(!ensureWritable())return;ArrayList<LibraryFolder> choices=new ArrayList<>();
        for(LibraryFolder candidate:libraryFolders){String id=candidate.id;boolean descendant=false;
            while(!id.isEmpty()){if(id.equals(folder.id)){descendant=true;break;}LibraryFolder parent=libraryFolder(id);if(parent==null)break;id=parent.parentId;}
            if(!descendant)choices.add(candidate);
        }
        choices.sort((a,b)->folderPath(a.id).compareToIgnoreCase(folderPath(b.id)));
        ArrayList<String> names=new ArrayList<>();names.add("全部资料（顶层）");for(LibraryFolder choice:choices)names.add(folderPath(choice.id));
        new AlertDialog.Builder(this).setTitle("移动文件夹到").setItems(names.toArray(new String[0]),(dialog,index)->{
            String parent=index==0?"":choices.get(index-1).id;if(!folderNameValid(folder.name,parent,folder.id))return;
            folder.parentId=parent;if(save())showLibrary();
        }).setNegativeButton("取消",null).show();
    }
    private void chooseDocumentFolder(Collection<LibraryItem> selected){
        if(!ensureWritable())return;
        ArrayList<LibraryFolder> choices=new ArrayList<>(libraryFolders);choices.sort((a,b)->folderPath(a.id).compareToIgnoreCase(folderPath(b.id)));
        ArrayList<String> names=new ArrayList<>();names.add("未分类");for(LibraryFolder folder:choices)names.add(folderPath(folder.id));
        new AlertDialog.Builder(this).setTitle("移动到文件夹").setItems(names.toArray(new String[0]),(dialog,index)->{
            LibraryFolder target=index==0?null:choices.get(index-1);long now=System.currentTimeMillis();
            for(LibraryItem item:selected){item.folderId=target==null?"":target.id;item.folder=target==null?"":target.name;item.modified=now;}
            if(save()){selectedIds.clear();batchMode=false;showLibrary();}
        }).setNegativeButton("取消",null).show();
    }
    private void assignLegacyFolderName(LibraryItem item,String name){
        String clean=name==null?"":name.trim();
        if(clean.isEmpty()){item.folder="";item.folderId="";return;}
        LibraryFolder found=null;for(LibraryFolder folder:libraryFolders)if(folder.parentId.isEmpty()&&folder.name.equals(clean)){found=folder;break;}
        if(found==null){found=new LibraryFolder(UUID.randomUUID().toString(),"",clean);libraryFolders.add(found);}
        item.folder=clean;item.folderId=found.id;
    }
    private String metadata(LibraryItem d){String base=d.isNote()?("infinite".equals(d.paper)?"无限画布":d.paper)+" · "+("grid".equals(d.template)?"网格":"ruled".equals(d.template)?"横线":"空白"):d.pages+" 页 · "+size(d.size)+("PDF".equals(d.format)?"":" · 文字排版");if(!d.folder.isEmpty())base+=" · "+(d.folderId.isEmpty()?d.folder:folderPath(d.folderId));if(d.favorite)base+=" · ★";return base;}
    private void createNote(){
        if(!ensureWritable())return;
        trackDocumentDialog(NoteDialogs.create(this,(name,template,paper)->{
            LibraryItem d=new LibraryItem();d.id=UUID.randomUUID().toString();d.name=name;d.originalName=name;d.hash="";d.type="note";d.format="NOTE";d.template=template;d.paper=paper;d.pages=1;d.created=d.modified=d.opened=System.currentTimeMillis();
            LibraryFolder destination=libraryFolder(selectedFolder);if(destination!=null){d.folderId=destination.id;d.folder=destination.name;}
            try{NoteStore.create(this,d.id,template,paper);docs.add(d);if(!save()){docs.remove(d);NoteStore.delete(this,d.id);return;}populate();open(d);}catch(IOException e){trackDocumentDialog(ReaderDialogs.message(this,"创建失败","无法保存笔记，请检查本机可用空间。",true));}
        }));
    }
    private String size(long bytes) {return bytes<1048576?String.format(Locale.CHINA,"%.0f KB",bytes/1024.0):String.format(Locale.CHINA,"%.1f MB",bytes/1048576.0);}
    private File file(LibraryItem d) {return new File(getFilesDir(),d.id+".pdf");}
    private void load() {
        String activeId=current==null?null:current.id;
        LibraryStore.State state=LibraryStore.load(this);docs.clear();docs.addAll(state.items);libraryFolders.clear();libraryFolders.addAll(state.folders);librarySaveAllowed=state.allowSave;libraryWarning=state.warning;libraryRevision=state.revision;
        if(activeId!=null){current=null;for(LibraryItem item:docs)if(activeId.equals(item.id)&&!item.isTrashed()){current=item;break;}}
    }
    private boolean save() {
        if(!librarySaveAllowed){load();refreshAfterReload();return false;}try{libraryRevision=LibraryStore.saveIfRevision(this,libraryRevision,docs,libraryFolders);return true;}catch(LibraryStore.StaleIndexException stale){load();refreshAfterReload();toast("资料库已在后台更新，本次改动未保存");return false;}catch(IOException|RuntimeException e){load();refreshAfterReload();toast("资料库保存失败，请检查本机空间");return false;}
    }
    private void refreshAfterReload(){if(pdfView!=null&&current==null){showLibrary();return;}if(current==null)populate();}
    private void pick() {
        if(!canStartImport())return;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/pdf","application/epub+zip","application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document","text/plain","application/octet-stream"});
        try{startActivityForResult(i,PICK);}catch(ActivityNotFoundException e){toast("没有可用的系统文件选择器");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        if(request!=PICK&&request!=CREATE_BACKUP&&request!=OPEN_BACKUP&&request!=EXPORT_PDF&&request!=EXPORT_PNG&&request!=PICK_PDF_TEXT)return;
        if(!initialized){pendingPickerKind=request;pendingPickerDestination=data.getData();}
        else dispatchPickerResult(request,data.getData());
    }
    private void dispatchPickerResult(int kind,Uri destination){
        if(kind==PICK)offerImport(destination);
        else if(kind==CREATE_BACKUP)startBackupExport(destination);
        else if(kind==OPEN_BACKUP)startBackupRestore(destination);
        else if(kind==EXPORT_PDF||kind==EXPORT_PNG)completeExportPicker(kind,destination);
        else if(kind==PICK_PDF_TEXT)readPdfTextFile(destination);
    }
    private void showPdfTextTools(){
        if(readerActions==null||current==null)return;final ReaderActions actions=readerActions;
        new AlertDialog.Builder(this).setTitle("PDF 文字框").setItems(new String[]{"输入或粘贴文字","从 TXT 文件添加","撤销文字修改"},(dialog,index)->{
            if(readerActions!=actions)return;
            if(index==0){selectInkMode(0);actions.showAddText();}
            else if(index==1){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain");
                try{startActivityForResult(intent,PICK_PDF_TEXT);}catch(ActivityNotFoundException missing){toast("没有可用的系统文件选择器");}}
            else if(!actions.undoTextEdit())toast("没有可撤销的文字修改");
        }).setNegativeButton("取消",null).show();
    }
    private void readPdfTextFile(Uri uri){
        final ReaderActions actions=readerActions;final LibraryItem doc=current;
        if(actions==null||doc==null){toast("请先打开需要添加文字的 PDF");return;}
        final Context app=getApplicationContext();toast("正在读取 TXT…");
        worker.execute(()->{try(InputStream input=app.getContentResolver().openInputStream(uri)){
            final String value=PdfTextImport.read(input);runOnUiThread(()->{
                if(dead||readerActions!=actions||current==null||!doc.id.equals(current.id))return;
                actions.whenMetadataReady(()->{if(!dead&&readerActions==actions){selectInkMode(0);actions.showAddText(value);}});
            });
        }catch(IOException|RuntimeException problem){runOnUiThread(()->{if(!dead&&readerActions==actions)toast("TXT 读取失败："+(problem.getMessage()==null?"请检查文件编码或重新选择":problem.getMessage()));});}});
    }
    private void completeExportPicker(int kind,Uri destination){
        final ReaderActions actions=readerActions;final LibraryItem doc=current;
        if(doc==null||actions==null||!doc.id.equals(exportDocumentId)||exportFrom<0||exportTo>doc.pages||exportFrom>=exportTo||(kind==EXPORT_PNG&&exportTo!=exportFrom+1)){toast("原文档已关闭，请重新发起导出");return;}
        actions.whenMetadataReady(()->{if(dead||readerActions!=actions||current==null||!current.id.equals(doc.id))return;if(!actions.metadataWritable()){toast("书签与高亮读取失败，本次无法完整导出");return;}startReaderExport(kind,destination);});
    }
    private void offerImport(Uri uri){if(!canStartImport())return;String name=displayName(uri);String mime=null;try{mime=getContentResolver().getType(uri);}catch(RuntimeException ignored){}boolean convertible=!"application/pdf".equals(mime)&&!name.toLowerCase(Locale.ROOT).endsWith(".pdf");beginImportOffer(new PendingImportOffer(uri,null,name,convertible));}
    private String displayName(Uri uri){String name="未命名文档";try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))name=c.getString(0);}catch(Exception ignored){}return name;}
    private Button chip(String label,boolean active,View.OnClickListener listener){Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextSize(12);b.setTextColor(active?AppColors.ON_ACCENT:ACCENT);b.setMinHeight(dp(38));b.setMinimumHeight(dp(38));b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(12),0,dp(12),0);b.setBackground(shape(active?AppColors.ACCENT:AppColors.SURFACE,18));b.setOnClickListener(listener);return b;}
    private String sortLabel(){switch(librarySort){case OPENED:return "最近打开";case NAME:return "名称";case CREATED:return "导入时间";default:return "最近修改";}}
    private void chooseSort(){String[] labels={"最近修改","最近打开","名称","导入时间"};LibraryQuery.Sort[] values={LibraryQuery.Sort.MODIFIED,LibraryQuery.Sort.OPENED,LibraryQuery.Sort.NAME,LibraryQuery.Sort.CREATED};new AlertDialog.Builder(this).setTitle("排序方式").setItems(labels,(d,which)->{librarySort=values[which];showLibrary();}).setNegativeButton("取消",null).show();}
    private void chooseFolder(){TreeSet<String> values=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);for(LibraryItem item:docs)if(!item.isTrashed()&&!item.folder.trim().isEmpty())values.add(item.folder.trim());chooseMetadata("选择文件夹",new ArrayList<>(values),true);}
    private void chooseTag(){TreeSet<String> values=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);for(LibraryItem item:docs)if(!item.isTrashed())values.addAll(item.tags);chooseMetadata("选择标签",new ArrayList<>(values),false);}
    private void chooseMetadata(String title,List<String> values,boolean folder){ArrayList<String> choices=new ArrayList<>();choices.add("全部");choices.addAll(values);new AlertDialog.Builder(this).setTitle(title).setItems(choices.toArray(new String[0]),(d,which)->{String selected=which==0?"":choices.get(which);if(folder)selectedFolder=selected;else selectedTag=selected;showLibrary();}).setNegativeButton("取消",null).show();}
    private Set<String> idsOf(Collection<LibraryItem> items){HashSet<String> ids=new HashSet<>();for(LibraryItem item:items)ids.add(item.id);return ids;}
    private boolean ensureWritable(){if(!initialized){toast("资料库正在检查，请稍候");return false;}if(librarySaveAllowed)return true;trackDocumentDialog(ReaderDialogs.message(this,"资料库需要修复","索引原文已保留。请先使用“扫描修复”，确认本机文件后再新增或修改资料。",true));return false;}
    private void showBatchActions(){if(!ensureWritable())return;if(selectedIds.isEmpty()){toast("请先选择文档");return;}trackDocumentDialog(DocumentDialogs.batchActions(this,selectedIds.size(),()->{long now=System.currentTimeMillis();for(LibraryItem item:docs)if(selectedIds.contains(item.id)){item.favorite=true;item.modified=now;}finishBatch();},()->{ArrayList<LibraryItem> chosen=new ArrayList<>();for(LibraryItem item:docs)if(selectedIds.contains(item.id))chosen.add(item);chooseDocumentFolder(chosen);},()->{long now=System.currentTimeMillis();for(LibraryItem item:docs)if(selectedIds.contains(item.id)){thumbnails.invalidate(item.id);TrashManager.moveToTrash(item,now);}finishBatch();}));}
    private void finishBatch(){finishBatch(false);}
    private void finishBatch(boolean afterDelete){save();selectedIds.clear();batchMode=false;showLibrary(afterDelete);}
    private void openGlobalSearch(){reloadLibraryOnResume=true;startActivity(new Intent(this,GlobalSearchActivity.class));}
    private void showLibraryTools(){trackDocumentDialog(DocumentDialogs.tools(this,this::chooseBackupDestination,this::chooseBackupSource,()->{trashMode=true;batchMode=false;favoritesOnly=false;selectedFolder="";selectedTag="";selectedIds.clear();showLibrary();},this::rebuildLibrary,this::openGlobalSearch,null,null,null,()->{reloadLibraryOnResume=true;startActivity(new Intent(this,AppearanceActivity.class));},this::showFaultReport,()->trackDocumentDialog(DocumentDialogs.about(this))));}
    private void promptNewFaultReport(){
        if(dead||isFinishing()||!CrashDiagnostics.claimNewFatalPrompt(this))return;
        new AlertDialog.Builder(this).setTitle("发现上次运行的故障记录")
                .setMessage("可在资料库工具中随时查看或复制故障记录。")
                .setNegativeButton("稍后",null)
                .setPositiveButton("查看",(dialog,which)->showFaultReport()).show();
    }
    private void showFaultReport(){
        String report=CrashDiagnostics.read(this);
        if(report==null||report.isEmpty())report="暂无故障记录";
        final String copy=report;
        CrashDiagnostics.markRead(this);
        new AlertDialog.Builder(this).setTitle("故障记录")
                .setMessage(report)
                .setNegativeButton("关闭",null)
                .setPositiveButton("复制",(dialog,which)->{
                    android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    if(clipboard!=null){clipboard.setPrimaryClip(ClipData.newPlainText("晴阅故障记录",copy));toast("已复制故障记录");}
                }).show();
    }
    private void launchOptional(String simpleName,String unavailable){try{reloadLibraryOnResume=true;startActivity(new Intent().setClassName(getPackageName(),getPackageName()+"."+simpleName));}catch(ActivityNotFoundException e){reloadLibraryOnResume=false;toast(unavailable);}}
    private void chooseBackupDestination(){if(!ensureWritable())return;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/zip");intent.putExtra(Intent.EXTRA_TITLE,"晴阅整库备份-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmm",Locale.CHINA).format(new Date())+".qyb");try{startActivityForResult(intent,CREATE_BACKUP);}catch(ActivityNotFoundException e){toast("没有可用的系统文件选择器");}}
    private void chooseBackupSource(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip","application/octet-stream","application/vnd.qingyue.backup+zip"});try{startActivityForResult(intent,OPEN_BACKUP);}catch(ActivityNotFoundException e){toast("没有可用的系统文件选择器");}}
    private void startBackupExport(Uri destination){
        final Context app=getApplicationContext();
        try{attachBackup(BackupCoordinator.start(BackupCoordinator.Kind.BACKUP,(progress,cancel)->{
            LibraryStore.State state=LibraryStore.load(app);
            if(!state.allowSave)throw new IOException("资料库索引需要修复，无法建立完整备份");
            return BackupManager.exportTo(app,destination,state.items,progress,cancel);
        }));}catch(IllegalStateException busy){attachBackup(BackupCoordinator.latest());toast("已有备份任务正在运行");}
    }
    private void startBackupRestore(Uri source){if(!ensureWritable())return;
        new AlertDialog.Builder(this).setTitle("从备份恢复？").setMessage("恢复前会在应用私有目录建立安全副本。遇到编号冲突时保留当前资料，并新增“恢复副本”。").setNegativeButton("取消",null).setPositiveButton("校验并恢复",(d,w)->{
            final Context app=getApplicationContext();
            try{attachBackup(BackupCoordinator.start(BackupCoordinator.Kind.RESTORE,(progress,cancel)->BackupManager.restoreFrom(app,source,BackupManager.ConflictPolicy.KEEP_BOTH,progress,cancel)));}
            catch(IllegalStateException busy){attachBackup(BackupCoordinator.latest());toast("已有备份任务正在运行");}
        }).show();
    }
    private void attachBackup(BackupCoordinator.Session session){
        if(backupSession==session){if(session!=null)session.attach(backupListener);return;}
        if(backupSession!=null)backupSession.detach(backupListener);
        dismissBackupDialog();backupSession=session;
        if(session!=null)session.attach(backupListener);
    }
    private void backupChanged(BackupCoordinator.Session session,BackupCoordinator.Snapshot snapshot){
        if(dead||session!=backupSession)return;
        if(snapshot.state==BackupCoordinator.State.RUNNING){
            String title=snapshot.kind==BackupCoordinator.Kind.BACKUP?"导出整库备份":"恢复整库备份";
            if(backupDialog==null)backupDialog=DocumentDialogs.task(this,title,"正在准备…",session::cancel);
            String message=snapshot.cancellationRequested?"正在取消…":snapshot.detail.isEmpty()?"处理中…":snapshot.detail;
            if(snapshot.totalEntries>0)message+=" · "+snapshot.completedEntries+" / "+snapshot.totalEntries;
            backupDialog.update(title,message,snapshot.percent());
            backupDialog.setCancellationEnabled(!snapshot.cancellationRequested);
            return;
        }
        dismissBackupDialog();backupSession=null;session.detach(backupListener);BackupCoordinator.forget(session.id());
        if(snapshot.state==BackupCoordinator.State.COMPLETED){
            BackupManager.Result result=snapshot.result;
            if(snapshot.kind==BackupCoordinator.Kind.RESTORE){load();showLibrary();toast("已恢复 "+result.itemCount+" 项资料"+(result.conflictCopies>0?"，保留 "+result.conflictCopies+" 个冲突副本":""));}
            else toast("备份已保存 · "+result.itemCount+" 项资料");
        }else if(snapshot.state==BackupCoordinator.State.CANCELED)toast("已取消"+(snapshot.kind==BackupCoordinator.Kind.BACKUP?"备份":"恢复"));
        else trackDocumentDialog(ReaderDialogs.message(this,snapshot.kind==BackupCoordinator.Kind.BACKUP?"备份失败":"恢复失败",safeMessage(snapshot.error),true));
    }
    private void dismissBackupDialog(){if(backupDialog!=null){backupDialog.dismiss();backupDialog=null;}}
    private void rebuildLibrary(){new AlertDialog.Builder(this).setTitle("扫描并修复资料库？").setMessage("将保留损坏索引副本，并根据本机 PDF 与笔记重建可打开项目。无法识别的原文件不会被删除。").setNegativeButton("取消",null).setPositiveButton("开始扫描",(d,w)->{final ArrayList<LibraryItem> hints=new ArrayList<>();for(LibraryItem item:docs)hints.add(item.copy());final DocumentDialogs.TaskDialog progress=DocumentDialogs.task(this,"扫描资料库","正在检查本机文件…",null);worker.execute(()->{try{LibraryStore.State state=LibraryStore.rebuild(getApplicationContext(),hints);runOnUiThread(()->{if(dead)return;progress.dismiss();load();showLibrary();toast("已重建 "+state.items.size()+" 个项目");});}catch(Exception e){runOnUiThread(()->{if(dead)return;progress.dismiss();trackDocumentDialog(ReaderDialogs.message(this,"修复失败",safeMessage(e),true));});}});}).show();}
    private void permanentDelete(LibraryItem item){
        final String id=item.id;if(!deletingIds.add(id)){toast("这项资料正在删除，请稍候");return;}
        CrashDiagnostics.markStage("删除准备");
        thumbnails.invalidate(id);
        worker.execute(()->{TrashManager.Deletion tx=null;boolean indexCommitted=false;try{
            final Context app=getApplicationContext();tx=TrashManager.stagePermanentDelete(app,item);
            LibraryStore.mutate(app,items->{boolean removed=false;for(Iterator<LibraryItem> iterator=items.iterator();iterator.hasNext();)if(id.equals(iterator.next().id)){iterator.remove();removed=true;break;}if(!removed)throw new IOException("资料记录已被其他操作移除");});
            indexCommitted=true;if(item.isNote())NoteStore.delete(app,id);else InkStore.deleteDocument(app.getFilesDir(),id);
            TrashManager.Deletion.CommitResult cleanup=tx.commit();final String notice=cleanup.cleanupComplete?"已彻底删除":"已删除；清理将在稍后重试";
            runOnUiThread(()->{try{if(dead)return;CrashDiagnostics.markStage("删除刷新");load();showLibrary(true);toast(notice);}finally{deletingIds.remove(id);}});
        }catch(Exception e){
            CrashDiagnostics.record(getApplicationContext(),"文档删除失败",e);
            String problem=safeMessage(e);if(tx!=null)if(indexCommitted){TrashManager.Deletion.CommitResult cleanup=tx.commit();problem="资料已从索引删除，但收尾未完成"+(cleanup.cleanupComplete?"":"；暂存目录已保留");}else try{tx.rollback();}catch(IOException rollback){problem+="；删除事务保留在 "+tx.recoveryDirectory().getAbsolutePath();}
            final String message=problem;final boolean committedForUi=indexCommitted;runOnUiThread(()->{try{if(dead)return;CrashDiagnostics.markStage("删除刷新");load();showLibrary(true);trackDocumentDialog(ReaderDialogs.message(this,committedForUi?"删除已提交":"删除失败",message,true));}finally{deletingIds.remove(id);}});
        }});
    }
    private String safeMessage(Throwable failure){String message=failure==null?null:failure.getMessage();return message==null||message.trim().isEmpty()?"操作未完成，请检查本机存储后重试。":message;}

    private boolean handleInternalJump(Intent intent){if(intent==null||!intent.hasExtra("docId"))return false;String id=intent.getStringExtra("docId");int page=(int)Math.max(0,Math.min(Integer.MAX_VALUE,longExtra(intent,"page",0)));long notePageId=longExtra(intent,"notePageId",0);LibraryItem target=null;for(LibraryItem item:docs)if(item.id.equals(id)){target=item;break;}setIntent(new Intent(this,MainActivity.class));if(target==null||target.isTrashed()){toast(target==null?"来源文档已不存在":"来源文档位于最近删除，请先恢复");return true;}if(target.isNote()){target.opened=System.currentTimeMillis();if(!save())return true;lastOpenedNoteId=target.id;Intent note=new Intent(this,NoteActivity.class).putExtra("id",target.id).putExtra("name",target.name).putExtra("page",Math.max(0,page));if(notePageId>0)note.putExtra("notePageId",notePageId);startActivity(note);NoteMotion.applyActivityTransition(this,true);}else{target.page=Math.max(0,Math.min(page,target.pages-1));open(target);if(pdfView!=null)pdfView.jumpTo(target.page);}return true;}
    private long longExtra(Intent intent,String key,long fallback){if(intent==null||intent.getExtras()==null)return fallback;Object value=intent.getExtras().get(key);if(value instanceof Number)return ((Number)value).longValue();if(value instanceof String)try{return Long.parseLong(((String)value).trim());}catch(NumberFormatException ignored){}return fallback;}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(!initialized||inserting!=null&&!inserting.finished)return;load();showLibrary();if(!handleInternalJump(intent)&&!openSourceIntent(intent))handleIncomingIntent(intent);}
    private boolean openSourceIntent(Intent intent){
        if(intent==null||!Intent.ACTION_VIEW.equals(intent.getAction())||intent.getData()==null||!"qingyue".equals(intent.getData().getScheme()))return false;
        setIntent(new Intent(this,MainActivity.class));
        try{ReaderSourceLink source=ReaderSourceLink.parse(intent.getDataString());for(LibraryItem doc:docs)if(!doc.isNote()&&doc.id.equals(source.documentId)){
            if(doc.isTrashed()){toast("来源文档位于最近删除，请先恢复");return true;}
            if(source.page>=doc.pages){toast("来源页面已不存在");return true;}
            if(current!=null&&current.id.equals(doc.id)&&pdfView!=null){pendingSource=source;pdfView.jumpTo(source.page);}
            else{pendingSource=source;doc.page=source.page;open(doc);}return true;
        }toast("来源文档已不在本机文档库");}catch(IllegalArgumentException invalid){toast("来源链接无效");}return true;
    }
    private void handleIncomingIntent(Intent intent){if(intent==null)return;Uri uri=null;CharSequence shared=null;if(Intent.ACTION_VIEW.equals(intent.getAction()))uri=intent.getData();else if(Intent.ACTION_SEND.equals(intent.getAction())){uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);if(uri==null)shared=intent.getCharSequenceExtra(Intent.EXTRA_TEXT);}if(uri!=null)offerImport(uri);else if(shared!=null&&canStartImport()){String name="分享文字-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmm",Locale.CHINA).format(new Date())+".txt";beginImportOffer(new PendingImportOffer(null,shared.toString(),name,true));}setIntent(new Intent(this,MainActivity.class));}
    private boolean canStartImport(){if(!ensureWritable())return false;if(importSession!=null){toast("已有文档正在导入或写入资料库");return false;}if(!pendingImportOfferId.isEmpty()){toast("请先处理当前导入请求");return false;}return true;}
    private void beginImportOffer(PendingImportOffer offer){String id=UUID.randomUUID().toString();pendingImportOffers.put(id,offer);pendingImportOfferId=id;showPendingImportOffer();}
    private void showPendingImportOffer(){final String id=pendingImportOfferId;final PendingImportOffer offer=pendingImportOffers.get(id);if(offer==null){pendingImportOfferId="";return;}final boolean[] accepted={false};Dialog dialog=DocumentDialogs.importOptions(this,offer.name,offer.convertible,keep->{accepted[0]=true;pendingImportOffers.remove(id);if(id.equals(pendingImportOfferId))pendingImportOfferId="";if(!canStartImport())return;if(offer.uri!=null)attachImport(ImportCoordinator.startUri(getApplicationContext(),offer.uri,keep));else attachImport(ImportCoordinator.startText(getApplicationContext(),offer.text,offer.name,keep));});documentDialog=dialog;dialog.setOnDismissListener(v->{if(documentDialog==dialog)documentDialog=null;if(!accepted[0]&&!dead){pendingImportOffers.remove(id);if(id.equals(pendingImportOfferId))pendingImportOfferId="";}});}
    private void resumeNewestImport(){for(ImportSession session:ImportCoordinator.sessions()){attachImport(session);return;}}
    private void attachImport(ImportSession session){if(importSession==session){if(session!=null)session.attach(importListener);return;}if(importSession!=null)importSession.detach(importListener);dismissImportDialog();importSession=session;if(session!=null)session.attach(importListener);}
    private void importChanged(ImportSession session,ImportSession.Snapshot snapshot){if(dead||session!=importSession)return;if(snapshot.phase==ImportSession.Phase.READY){ImportSession.Claim claim=session.claim();if(claim!=null)commitImport(claim);return;}if(snapshot.phase==ImportSession.Phase.COMPLETED){dismissImportDialog();ImportCoordinator.forget(session.id());importSession=null;load();if(current==null)showLibrary();toast(snapshot.completionMessage.isEmpty()?"导入成功":snapshot.completionMessage);return;}if(snapshot.phase==ImportSession.Phase.FAILED||snapshot.phase==ImportSession.Phase.CANCELED){dismissImportDialog();ImportCoordinator.forget(session.id());importSession=null;if(snapshot.phase==ImportSession.Phase.FAILED)trackDocumentDialog(ReaderDialogs.message(this,"导入失败",snapshot.error.isEmpty()?snapshot.message:snapshot.error,true));else toast("已取消导入");return;}showImportProgress(session,snapshot);}
    private void showImportProgress(ImportSession session,ImportSession.Snapshot snapshot){if(importDialog==null)importDialog=DocumentDialogs.task(this,"正在导入",snapshot.message,session::cancel);String stage;switch(snapshot.phase){case COPYING:stage="复制到本机";break;case EXTRACTING:stage="提取文字";break;case CONVERTING:stage="生成阅读页面";break;case VALIDATING:stage="校验文档";break;case CLAIMED:stage="写入资料库";break;default:stage="准备导入";}String detail=snapshot.message;if(snapshot.unit==ImportSession.Unit.BYTES&&snapshot.completed>0)detail+=" · "+size(snapshot.completed);else if(snapshot.unit==ImportSession.Unit.PAGES&&snapshot.completed>0)detail+=" · "+snapshot.completed+" 页";importDialog.setCancellationEnabled(snapshot.phase!=ImportSession.Phase.CLAIMED);importDialog.update(stage,detail,snapshot.percent());}
    private void dismissImportDialog(){if(importDialog!=null){importDialog.dismiss();importDialog=null;}}
    private void commitImport(ImportSession.Claim claim){showImportProgress(importSession,importSession.snapshot());final Context app=getApplicationContext();worker.execute(()->{final ArrayList<File> created=new ArrayList<>();final String[] completion={"导入成功"};try{ImportSession.Result result=claim.result;LibraryStore.mutate(app,items->{LibraryItem duplicate=null;for(LibraryItem old:items)if(!old.isNote()&&!result.hash.isEmpty()&&result.hash.equalsIgnoreCase(old.hash)){File oldPdf=new File(app.getFilesDir(),old.id+".pdf");if(!oldPdf.exists()||readablePdf(oldPdf)){duplicate=old;break;}}long now=System.currentTimeMillis();if(duplicate!=null){File existing=new File(app.getFilesDir(),duplicate.id+".pdf");boolean repaired=!existing.isFile();if(repaired){copyFile(result.pdf,existing);created.add(existing);duplicate.pages=result.pages;duplicate.page=Math.max(0,Math.min(duplicate.page,result.pages-1));duplicate.size=result.size;duplicate.format=result.format;duplicate.modified=now;}if(result.originalAttachment!=null&&duplicate.attachment.isEmpty()){String safe=safeAttachmentName(result.originalAttachmentName);File dir=new File(LibraryStore.attachmentsDirectory(app),duplicate.id);if(dir.exists()||!dir.mkdirs())throw new IOException("无法保存原文件附件");created.add(dir);File original=new File(dir,safe);copyFile(result.originalAttachment,original);duplicate.attachment=duplicate.id+"/"+safe;duplicate.originalName=result.originalAttachmentName;}boolean restored=duplicate.isTrashed();if(restored)TrashManager.restore(duplicate,now);completion[0]=restored?"文档已存在，已从最近删除恢复":repaired?"已修复缺失的文档原文":"文档已存在";return;}String id=result.id;File finalPdf=new File(app.getFilesDir(),id+".pdf");if(finalPdf.exists())throw new IOException("导入编号冲突，请重试");copyFile(result.pdf,finalPdf);created.add(finalPdf);String attachment="";if(result.originalAttachment!=null){String safe=safeAttachmentName(result.originalAttachmentName);File dir=new File(LibraryStore.attachmentsDirectory(app),id);if(dir.exists()||!dir.mkdirs())throw new IOException("无法保存原文件附件");created.add(dir);File original=new File(dir,safe);copyFile(result.originalAttachment,original);attachment=id+"/"+safe;}LibraryItem item=new LibraryItem();item.id=id;item.name=result.name;item.originalName=result.originalAttachmentName.isEmpty()?result.name:result.originalAttachmentName;item.hash=result.hash;item.pages=result.pages;item.page=0;item.size=result.size;item.format=result.format;item.created=item.modified=item.opened=now;item.attachment=attachment;items.add(item);});claim.complete(completion[0]);}catch(Exception failure){boolean rolledBack=true;for(int i=created.size()-1;i>=0;i--)rolledBack&=deleteTreeQuietly(created.get(i));String message=safeMessage(failure)+(rolledBack?"":"；部分新文件未能清理，请使用扫描修复检查");claim.fail(message);}});}
    private boolean readablePdf(File value){try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(value,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(descriptor)){return renderer.getPageCount()>0;}catch(Exception|LinkageError ignored){return false;}}
    private void copyFile(File source,File target)throws IOException{
        if(!source.isFile()||source.length()>200L*1024*1024)throw new IOException("导入暂存文件无效或超过 200 MB");
        File parent=target.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("无法创建目标目录");
        // Import staging and the library live under the same app-private filesDir. The prepared
        // file is already durable; rename keeps the index lock free of a long copy and fsync.
        if(target.exists()||!source.renameTo(target))throw new IOException("无法提交 "+target.getName());
    }
    private String safeAttachmentName(String value){String name=value==null?"original.bin":value.replace('\\','_').replace('/','_').replace(':','_').trim();if(name.isEmpty())name="original.bin";if(name.length()>120)name=name.substring(name.length()-120);return name;}
    private boolean deleteTreeQuietly(File file){if(file==null||!file.exists())return true;boolean success=true;if(file.isDirectory()){File[] children=file.listFiles();if(children==null)success=false;else for(File child:children)success&=deleteTreeQuietly(child);}return file.delete()&&success;}

    private final class LibraryListAdapter extends BaseAdapter {
        private final ListView ownerList;
        private final boolean showingTrash;
        private final ArrayList<RowHolder> rows=new ArrayList<>();
        private List<LibraryItem> snapshot=Collections.emptyList();
        private boolean disposed,hasLibraryItems;
        LibraryListAdapter(ListView ownerList,boolean showingTrash){this.ownerList=ownerList;this.showingTrash=showingTrash;}
        void publish(List<LibraryItem> items,boolean hasItems){
            if(disposed)return;
            snapshot=new ArrayList<>(items);
            hasLibraryItems=hasItems;
            notifyDataSetChanged();
        }
        void dispose(){
            if(disposed)return;
            disposed=true;
            android.widget.ListAdapter attached=ownerList.getAdapter();
            if(attached==this||(attached instanceof android.widget.WrapperListAdapter &&
                    ((android.widget.WrapperListAdapter)attached).getWrappedAdapter()==this))ownerList.setAdapter(null);
            snapshot=Collections.emptyList();
            for(RowHolder holder:rows){if(holder.token!=null){holder.token.cancel();holder.token=null;}holder.image.setImageDrawable(null);}
            rows.clear();
        }
        @Override public int getCount(){return disposed?0:snapshot.isEmpty()&&visibleFolderCount>0?0:Math.max(1,snapshot.size());}
        @Override public LibraryItem getItem(int position){return disposed||position<0||position>=snapshot.size()?null:snapshot.get(position);}
        @Override public long getItemId(int position){return position;}
        @Override public boolean isEnabled(int position){return getItem(position)!=null;}
        @Override public View getView(int position,View recycled,ViewGroup parent){
            LibraryItem item=getItem(position);if(item==null){if(recycled!=null&&recycled.getTag() instanceof RowHolder){RowHolder old=(RowHolder)recycled.getTag();if(old.token!=null)old.token.cancel();}return emptyRow();}
            RowHolder holder;if(recycled==null||!(recycled.getTag() instanceof RowHolder)){holder=createRow();recycled=holder.card;recycled.setTag(holder);}else holder=(RowHolder)recycled.getTag();
            bindRow(holder,item);return recycled;
        }
        private View emptyRow(){LinearLayout empty=column();empty.setGravity(Gravity.CENTER);empty.setPadding(dp(16),dp(28),dp(16),dp(34));FolderArtView art=new FolderArtView(MainActivity.this);empty.addView(art,new LinearLayout.LayoutParams(dp(82),dp(82)));space(empty,9);TextView title=text(showingTrash?"最近删除是空的":!hasLibraryItems?"从一份文档或一本笔记开始":"没有匹配的资料",17,INK);title.setGravity(Gravity.CENTER);empty.addView(title);space(empty,6);TextView hint=text(showingTrash?"移入最近删除的资料会显示在这里":!hasLibraryItems?"点击上方“新建笔记”或“导入”":"试试清除筛选或更换关键词",12,MUTED);hint.setGravity(Gravity.CENTER);empty.addView(hint);return empty;}
        private RowHolder createRow(){RowHolder h=new RowHolder();rows.add(h);h.card=row();h.card.setPadding(dp(11),dp(11),dp(7),dp(11));
            h.coverFrame=new FrameLayout(MainActivity.this);h.image=new ImageView(MainActivity.this);h.image.setScaleType(ImageView.ScaleType.CENTER_CROP);h.image.setVisibility(View.GONE);h.coverFrame.addView(h.image,new FrameLayout.LayoutParams(-1,-1));h.fallback=text("PDF",13,ACCENT);h.fallback.setGravity(Gravity.CENTER);h.fallback.setTypeface(null,1);h.fallback.setBackground(AppColors.isClassic()?new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xffffeddd,0xffffd0aa}):shape(AppColors.SOFT,10));h.coverFrame.addView(h.fallback,new FrameLayout.LayoutParams(-1,-1));h.card.addView(h.coverFrame,new LinearLayout.LayoutParams(dp(AppColors.isClassic()?64:48),dp(AppColors.isClassic()?84:66)));
            LinearLayout copy=column();copy.setPadding(dp(13),dp(1),dp(8),0);h.title=text("",16,INK);h.title.setTypeface(null,1);h.title.setMaxLines(2);h.title.setEllipsize(TextUtils.TruncateAt.END);copy.addView(h.title);space(copy,4);h.meta=text("",11,MUTED);h.meta.setSingleLine(true);h.meta.setEllipsize(TextUtils.TruncateAt.END);copy.addView(h.meta);space(copy,5);h.detail=text("",12,ACCENT);h.detail.setSingleLine(true);h.detail.setEllipsize(TextUtils.TruncateAt.END);copy.addView(h.detail);space(copy,5);h.tags=text("",11,MUTED);h.tags.setSingleLine(true);h.tags.setEllipsize(TextUtils.TruncateAt.END);copy.addView(h.tags);h.card.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
            h.more=icon("more","管理文档",v->{});h.more.setBackground(shape(AppColors.isClassic()?AppColors.SOFT:Color.TRANSPARENT,12));h.card.addView(h.more,new LinearLayout.LayoutParams(dp(40),dp(44)));return h;}
        private void bindRow(RowHolder h,LibraryItem item){if(h.token!=null)h.token.cancel();h.boundId=item.id;h.image.setImageDrawable(null);h.image.setVisibility(View.GONE);h.fallback.setVisibility(View.VISIBLE);h.fallback.setText(item.isNote()?"笔记":item.format);
            boolean selected=selectedIds.contains(item.id);GradientDrawable background=shape(selected?(AppColors.isClassic()?0xffffead8:AppColors.SOFT):AppColors.SURFACE,AppColors.isClassic()?19:15);background.setStroke(dp(selected?2:1),selected?AppColors.ACCENT:AppColors.isClassic()?0x60efdcc9:AppColors.BORDER);h.card.setBackground(background);h.card.setContentDescription((selected?"已选择，":"")+item.name+"，"+metadata(item));
            h.title.setText((item.favorite?"★ ":"")+item.name);h.meta.setText(metadata(item));
            if(item.isTrashed())h.detail.setText("删除于 "+formatTime(item.trashedAt));else h.detail.setText(item.isNote()?"继续记录 · 修改于 "+formatTime(item.modified):"继续阅读 · 第 "+(item.page+1)+" 页");
            StringBuilder labels=new StringBuilder();for(int i=0;i<Math.min(2,item.tags.size());i++){if(labels.length()>0)labels.append("  ·  ");labels.append('#').append(item.tags.get(i));}if(item.tags.size()>2)labels.append("  +").append(item.tags.size()-2);h.tags.setText(labels.length()==0?(item.attachment.isEmpty()?"本机资料":"已保留原文件附件"):labels.toString());
            h.tags.setVisibility(!AppColors.isClassic()&&labels.length()==0&&item.attachment.isEmpty()?View.GONE:View.VISIBLE);
            h.more.setContentDescription(item.isTrashed()?"管理最近删除的 "+item.name:"管理 "+item.name);h.more.setOnClickListener(v->manage(item));h.card.setOnClickListener(v->{if(batchMode&&!trashMode){toggleSelected(item);}else if(item.isTrashed())manage(item);else open(item);});h.card.setOnLongClickListener(v->{if(trashMode)return false;batchMode=true;selectedIds.add(item.id);populate();return true;});
            h.token=thumbnails.request(item,dp(64),dp(84),(token,bitmap)->{if(libraryAdapter!=this||h.token!=token||token.isCancelled()||!item.id.equals(h.boundId))return;if(bitmap!=null){h.image.setImageBitmap(bitmap);h.image.setVisibility(View.VISIBLE);h.fallback.setVisibility(View.GONE);}});
        }
    }
    private void toggleSelected(LibraryItem item){if(!selectedIds.remove(item.id))selectedIds.add(item.id);populate();}
    private String formatTime(long time){if(time<=0)return "未知";return new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(time));}
    private static final class RowHolder {LinearLayout card;FrameLayout coverFrame;ImageView image;TextView fallback,title,meta,detail,tags;IconButton more;String boundId;LibraryThumbnailLoader.Token token;}
    private void manage(LibraryItem d) {
        if(d.isTrashed()){trackDocumentDialog(DocumentDialogs.trashActions(this,d.name,d.trashedAt,()->{if(!ensureWritable())return;TrashManager.restore(d,System.currentTimeMillis());save();showLibrary();},()->trackDocumentDialog(DocumentDialogs.confirmPermanent(this,d.name,()->permanentDelete(d)))));return;}
        trackDocumentDialog(DocumentDialogs.libraryActions(this,d.name,metadata(d),d.page+1,d.pages,d.isNote(),d.format,d.favorite,d.folder,d.tags,
            ()->open(d),
            ()->trackDocumentDialog(DocumentDialogs.rename(this,d.name,name->{if(!ensureWritable())return;d.name=name;d.modified=System.currentTimeMillis();save();populate();})),
            ()->new AlertDialog.Builder(this).setTitle("整理资料").setItems(new String[]{"移动到文件夹","编辑文件夹名称与标签"},(dialog,which)->{
                if(which==0)chooseDocumentFolder(Collections.singletonList(d));
                else trackDocumentDialog(DocumentDialogs.organize(this,d.folder,d.tags,(folder,tags)->{if(!ensureWritable())return;assignLegacyFolderName(d,folder);d.tags.clear();d.tags.addAll(LibraryItem.cleanedTags(tags));d.modified=System.currentTimeMillis();save();showLibrary();}));
            }).show(),
            ()->{if(!ensureWritable())return;d.favorite=!d.favorite;d.modified=System.currentTimeMillis();save();populate();},
            ()->{if(!ensureWritable())return;CrashDiagnostics.markStage("删除准备");thumbnails.invalidate(d.id);TrashManager.moveToTrash(d,System.currentTimeMillis());selectedIds.remove(d.id);if(save())showLibrary(true);}));
    }
    private void trackDocumentDialog(Dialog dialog){documentDialog=dialog;dialog.setOnDismissListener(v->{if(documentDialog==dialog)documentDialog=null;});}
    private IconButton icon(String kind,String label,View.OnClickListener action){
        IconButton b=new IconButton(this,kind,label);b.setOnClickListener(action);return b;
    }
    private void addIcon(LinearLayout row,IconButton button){row.addView(button,new LinearLayout.LayoutParams(dp(44),dp(44)));}
    private void open(LibraryItem d) {
        if(d==null||d.isTrashed())return;
        if(d.isNote()){d.opened=System.currentTimeMillis();if(!save())return;lastOpenedNoteId=d.id;startActivity(new Intent(this,NoteActivity.class).putExtra("id",d.id).putExtra("name",d.name));NoteMotion.applyActivityTransition(this,true);return;}
        if(inserting!=null&&!inserting.finished){toast("正在插入页面，请稍候");return;}
        generation++;releaseView();current=d;readerQuery="";headerCollapsed=false;requestedPage=d.page;
        PdfInsertJob retained=INSERTIONS.get(d.id);
        if(retained!=null){attachInsertion(retained);return;}
        pdfOpening=true;showReaderWait("正在打开文档…","正在准备页面与批注");
        final int openingGeneration=generation;final Context app=getApplicationContext();
        worker.execute(()->{
            DocumentEditLease acquired=null;Throwable failure=null;
            try{acquired=DocumentEditLease.acquire(app,d.id);}catch(IOException|RuntimeException problem){failure=problem;}
            final DocumentEditLease ready=acquired;final Throwable problem=failure;
            runOnUiThread(()->{
                if(dead||generation!=openingGeneration||current!=d){if(ready!=null)ready.close();return;}
                pdfOpening=false;
                if(problem!=null){showReaderOpenFailure(safeMessage(problem));return;}
                editLease=ready;d.opened=System.currentTimeMillis();
                if(!save()){releaseView();showReaderOpenFailure("资料库暂时不可写，请返回后重试");return;}
                buildReader(d);
            });
        });
    }
    private void buildReader(LibraryItem d){
        CrashDiagnostics.markStage("阅读器");setupRoot();final String openId=d.id;final int openGeneration=generation;
        LinearLayout header=row();header.setPadding(dp(8),dp(AppColors.isClassic()?3:9),dp(8),dp(AppColors.isClassic()?3:9));header.setBackgroundColor(AppColors.isClassic()?AppColors.SURFACE:AppColors.BACKGROUND);
        addIcon(header,icon("back","返回文档库",v->showLibrary()));
        LinearLayout titles=column();titles.setPadding(dp(6),0,0,0);
        TextView title=text(d.name,15,INK);title.setTypeface(null,1);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);titles.addView(title);
        readerStatus=text("准备文档…",10,MUTED);readerStatus.setSingleLine(true);readerStatus.setEllipsize(TextUtils.TruncateAt.END);readerStatus.setOnClickListener(v->{if(pdfView!=null){if(pdfView.isPickingColor())pdfView.cancelColorPick();else pdfView.retry();}});titles.addView(readerStatus);
        header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        addIcon(header,icon("more","更多操作与设置",v->readerMenu()));readerHeader=header;root.addView(header);
        annotationTools();
        FrameLayout canvas=new FrameLayout(this);
        pdfView=new PdfReaderView(this,file(d),getFilesDir(),d.id,d.pages,d.page,worker,new PdfReaderView.Host(){
            @Override public void changed(int page){if(dead||generation!=openGeneration||current==null||!openId.equals(current.id))return;requestedPage=page;if(pdfPageSidebar!=null)pdfPageSidebar.setCurrentPage(page);if(current.page!=page){current.page=page;save();}updateInkTools();if(pendingSource!=null&&pendingSource.documentId.equals(openId)&&pendingSource.page==page){ReaderSourceLink source=pendingSource;pendingSource=null;pdfView.jumpTo(source.page,source.y);}}
            @Override public void error(String message){if(!dead)toast(message);}
            @Override public void colorPicked(int picked){if(dead||current==null||!openId.equals(current.id))return;if(ToolPreferences.pdfToolName(inkMode)==null)selectInkMode(1);inkColor=picked;rememberColor();applyTool();toast("已提取 "+hex(inkColor));}
            @Override public void readingScrolled(){if(!dead&&current!=null&&openId.equals(current.id))setHeaderCollapsed(true);}
            @Override public void inkSaved(String documentId,long modifiedAtMillis){try{if(!LibraryStore.touchModified(getApplicationContext(),documentId,modifiedAtMillis))return;runOnUiThread(()->{if(dead)return;for(LibraryItem item:docs)if(item.id.equals(documentId)){item.modified=Math.max(item.modified,modifiedAtMillis);break;}if(thumbnails!=null)thumbnails.invalidate(documentId);});}catch(IOException failure){runOnUiThread(()->{if(!dead)toast("批注已保存，但资料库修改时间暂未更新");});}}
        });
        readerActions=new ReaderActions(this,pdfView,file(d),getFilesDir(),d.id,d.name,d.pages);
        readerActions.setPageInsertion(this::showPdfInsertionOptions);
        readerActions.setSourceConsumer(source->{reloadLibraryOnResume=true;NoteSourceActions.show(this,source);});
        readerActions.setAdvancedToolSelection(tool->{if(pdfView!=null&&current!=null&&current.id.equals(openId)){selectInkMode(tool);if(tool==4)penSettings(tool,colorButton);}});
        pdfView.setTwoFingerUndoEnabled(twoFingerUndo);
        pdfView.setHorizontalPaging(horizontalPaging);
        canvas.addView(pdfView,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout pager=row();pager.setPadding(dp(4),dp(AppColors.isClassic()?0:5),dp(4),dp(AppColors.isClassic()?0:5));pager.setBackground(shape(AppColors.SURFACE,AppColors.isClassic()?24:16));pager.setElevation(dp(AppColors.isClassic()?4:1));
        prev=icon("up","上一页，也可向右滑动",v->render(requestedPage-1));next=icon("down","下一页，也可向左滑动",v->render(requestedPage+1));addIcon(pager,prev);
        pageLabel=text((d.page+1)+" / "+d.pages,12,INK);pageLabel.setGravity(Gravity.CENTER);pageLabel.setMinWidth(dp(60));pageLabel.setPadding(dp(8),0,dp(8),0);pageLabel.setContentDescription("当前页码，点击跳页");pageLabel.setOnClickListener(v->jump());pager.addView(pageLabel,new LinearLayout.LayoutParams(-2,dp(44)));addIcon(pager,next);
        if(AppColors.isClassic()){FrameLayout.LayoutParams overlay=new FrameLayout.LayoutParams(-2,dp(44),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);overlay.bottomMargin=dp(12);canvas.addView(pager,overlay);}
        else{pageLabel.setTextColor(ACCENT);pageLabel.setTextSize(14);pageLabel.setMinWidth(dp(112));pageLabel.setBackground(shape(AppColors.SOFT,12));prev.setRotation(-90);next.setRotation(-90);pager.setGravity(Gravity.CENTER);}
        pdfPagesExpanded=getPreferences(0).getBoolean("pdfPagesExpanded",true);
        pdfReadingBody=row();pdfReadingBody.setGravity(Gravity.TOP);
        pdfPageSidebar=new PdfPageSidebar(this,file(d),d.pages,d.page,page->{if(pdfView!=null&&current!=null&&openId.equals(current.id))pdfView.jumpTo(page);},this::showPdfInsertionOptions,()->{if(readerActions!=null)readerActions.showNavigator();},()->{pdfPagesExpanded=false;getPreferences(0).edit().putBoolean("pdfPagesExpanded",false).apply();updatePdfSidebar();});
        pdfReadingBody.addView(pdfPageSidebar,new LinearLayout.LayoutParams(dp(112),-1));pdfReadingBody.addView(canvas,new LinearLayout.LayoutParams(0,-1,1));root.addView(pdfReadingBody,new LinearLayout.LayoutParams(-1,0,1));
        pdfReadingBody.addOnLayoutChangeListener((view,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->updatePdfSidebar());updatePdfSidebar();
        if(!AppColors.isClassic()){
            LinearLayout footerRow=row();footerRow.addView(pager,new LinearLayout.LayoutParams(0,-1,1));
            LinearLayout.LayoutParams footer=new LinearLayout.LayoutParams(-1,dp(60));footer.setMargins(dp(12),dp(5),dp(12),dp(8));root.addView(footerRow,footer);
        }
        applyTool();updatePagingControls();updateInkTools();setHeaderCollapsed(headerCollapsed);
    }
    private void showReaderWait(String title,String detail){
        setupRoot();LinearLayout waiting=column();waiting.setGravity(Gravity.CENTER);waiting.setPadding(dp(28),dp(24),dp(28),dp(24));
        ProgressBar spinner=new ProgressBar(this);waiting.addView(spinner,new LinearLayout.LayoutParams(dp(40),dp(40)));space(waiting,20);
        TextView heading=text(title,18,INK);heading.setGravity(Gravity.CENTER);waiting.addView(heading);space(waiting,12);
        TextView hint=text(detail,14,MUTED);hint.setGravity(Gravity.CENTER);waiting.addView(hint);root.addView(waiting,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void showReaderOpenFailure(String message){
        setupRoot();LinearLayout panel=column();panel.setPadding(dp(24),dp(24),dp(24),dp(24));panel.addView(text("文档暂时无法打开",18,INK));space(panel,12);panel.addView(text(message,14,MUTED));space(panel,20);
        Button back=button("返回资料库",false);back.setOnClickListener(v->showLibrary());panel.addView(back);root.addView(panel);
    }
    private void showPdfInsertionOptions(int page){
        if(dead||current==null||pdfView==null||readerActions==null||inserting!=null)return;
        if(readerActions.exporting()){toast("正在导出，请完成或取消导出后再插入页面");return;}
        final LibraryItem doc=current;final PdfReaderView target=pdfView;final int count=doc.pages;
        if(page<0||page>=count)return;target.pause();dismissToolPopup();closeReaderDialog();
        trackReaderDialog(PageInsertionDialog.show(this,page,count,index->{
            if(dead||current!=doc||pdfView!=target||doc.pages!=count)return;
            if(readerActions==null||readerActions.exporting()){toast("正在导出，请稍后再插入页面");return;}
            beginPdfInsertion(doc,index,count);
        }));
    }
    private void beginPdfInsertion(LibraryItem doc,int index,int count){
        if(inserting!=null||current!=doc||pdfView==null)return;
        final PdfInsertJob job=new PdfInsertJob(doc.id,pdfView.page(),index,count);
        PdfInsertJob running=INSERTIONS.putIfAbsent(doc.id,job);
        if(running!=null){toast("这份文档正在插入页面，请稍候");return;}
        generation++;releaseView();pdfOpening=false;attachInsertion(job);
        final Context app=getApplicationContext();
        worker.execute(()->{
            try{job.result=PdfPageInsertion.insertBlankPage(app,job.documentId,job.index,job.count);}
            catch(IOException|RuntimeException|OutOfMemoryError problem){job.failure=problem;}
            finally{job.finished=true;new Handler(Looper.getMainLooper()).post(()->{MainActivity owner=job.owner.get();if(owner!=null)owner.finishPdfInsertion(job);});}
        });
    }
    private void attachInsertion(PdfInsertJob job){
        inserting=job;job.owner=new java.lang.ref.WeakReference<>(this);
        if(job.finished){finishPdfInsertion(job);return;}
        showReaderWait("正在插入空白页…","新页面将位于第 "+(job.index+1)+" 页。大文档处理可能需要一些时间，请稍候。");
    }
    private void finishPdfInsertion(PdfInsertJob job){
        if(dead||!job.finished||inserting!=job||current==null||!job.documentId.equals(current.id))return;
        inserting=null;INSERTIONS.remove(job.documentId,job);job.owner=new java.lang.ref.WeakReference<>(null);
        if(thumbnails!=null)thumbnails.invalidate(job.documentId);
        load();LibraryItem updated=null;String id=job.result==null?job.documentId:job.result.documentId;
        for(LibraryItem item:docs)if(id.equals(item.id)&&!item.isTrashed()){updated=item;break;}
        if(updated==null){current=null;showLibrary();toast("文档状态已变化，请在资料库重新打开");return;}
        updated.page=Math.max(0,Math.min(updated.pages-1,job.result==null?job.oldPage:job.result.insertedPage));
        open(updated);
        toast(job.failure==null?"已插入第 "+(updated.page+1)+" 页":safeMessage(job.failure));
    }
    private void annotationTools(){
        LinearLayout strip=row();strip.setPadding(dp(4),0,dp(4),0);strip.setBackgroundColor(AppColors.SURFACE);
        pdfSidebarToggle=icon("sidebar","展开页面缩略图",v->togglePdfSidebar());if(!AppColors.isClassic())pdfSidebarToggle.setVisualLabel("页面");strip.addView(pdfSidebarToggle,new LinearLayout.LayoutParams(dp(50),dp(AppColors.isClassic()?48:66)));
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);scroll.setFillViewport(false);
        LinearLayout toolbar=row();toolbar.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);toolbar.setPadding(dp(2),dp(2),dp(2),dp(2));
        inkButtons.clear();String[] kinds={"hand","pen","ballpoint","pencil","marker","eraser"},labels={"浏览 / 手指滚动","钢笔；长按或再次点击调节","圆珠笔；长按或再次点击调节","铅笔；长按或再次点击调节","荧光笔；长按或再次点击调节","局部橡皮；长按或再次点击调节"};int[] quickModes={0,1,10,11,2,4};
        for(int i=0;i<kinds.length;i++){final int mode=quickModes[i];IconButton b=icon(kinds[i],labels[i],v->{
            if(inkMode==mode&&mode!=0){penSettings(mode,v);return;}selectInkMode(mode);
        });if(mode!=0)b.setOnLongClickListener(v->{selectInkMode(mode);penSettings(mode,v);return true;});inkButtons.add(b);
        if(AppColors.isClassic())addIcon(toolbar,b);else{b.setVisualLabel(new String[]{"手指","钢笔","圆珠笔","铅笔","荧光笔","橡皮"}[i]);toolbar.addView(b,new LinearLayout.LayoutParams(dp(50),dp(62)));}}
        View divider=new View(this);divider.setBackgroundColor(AppColors.BORDER);LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(dp(1),dp(22));gap.setMargins(dp(5),0,dp(5),0);toolbar.addView(divider,gap);
        addIcon(toolbar,icon("text","添加或管理 PDF 文字框",v->showPdfTextTools()));
        undoButton=icon("undo",twoFingerUndo?"撤销本页笔迹；也可双指轻点页面":"撤销本页笔迹",v->{if(pdfView!=null)pdfView.undo();});redoButton=icon("redo","重做本页笔迹",v->{if(pdfView!=null)pdfView.redo();});addIcon(toolbar,undoButton);addIcon(toolbar,redoButton);
        colorButton=new ColorSwatch(this,inkColor,"笔迹颜色与调色盘");colorButton.setOnClickListener(v->{if(ToolPreferences.pdfToolName(inkMode)==null)selectInkMode(1);penSettings(inkMode,v);});colorButton.setOnLongClickListener(v->{if(ToolPreferences.pdfToolName(inkMode)==null)selectInkMode(1);penSettings(inkMode,v);return true;});toolbar.addView(colorButton,new LinearLayout.LayoutParams(dp(44),dp(44)));
        stylusButton=icon("stylus","切换仅手写笔绘制",v->{stylusOnly=!stylusOnly;getPreferences(0).edit().putBoolean("stylusOnly",stylusOnly).apply();applyTool();toast(stylusOnly?"仅手写笔绘制；手指滚动与翻页":"已允许手指绘制；双指可以移动页面");});addIcon(toolbar,stylusButton);
        scroll.addView(toolbar);strip.addView(scroll,new LinearLayout.LayoutParams(0,dp(AppColors.isClassic()?48:66),1));
        LinearLayout.LayoutParams toolsParams=new LinearLayout.LayoutParams(-1,dp(AppColors.isClassic()?48:68));if(!AppColors.isClassic()){toolsParams.setMargins(dp(10),0,dp(10),dp(3));strip.setBackground(shape(AppColors.SURFACE,16));}root.addView(strip,toolsParams);strip=row();strip.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);strip.setPadding(dp(12),0,dp(12),0);TextView toolHint=text("批注工具",11,MUTED);strip.addView(toolHint,new LinearLayout.LayoutParams(0,-2,1));
        View edge=new View(this);edge.setBackgroundColor(AppColors.BORDER);strip.addView(edge,new LinearLayout.LayoutParams(dp(1),dp(22)));
        addIcon(strip,icon("fit","设置 PDF 缩放比例",v->showZoomControls()));
        addIcon(strip,icon("search","搜索 PDF 内容",v->searchPdf()));
        addIcon(strip,icon("select","选择当前页文字",v->{if(readerActions!=null)readerActions.beginTextSelection();}));
        headerToggle=icon("down","收起标题栏",v->setHeaderCollapsed(!headerCollapsed));headerToggle.setRotation(180);addIcon(strip,headerToggle);root.addView(strip,new LinearLayout.LayoutParams(-1,dp(48)));
    }
    private void updatePdfSidebar(){
        if(pdfPageSidebar==null||pdfReadingBody==null)return;int width=pdfReadingBody.getWidth()>0?Math.round(pdfReadingBody.getWidth()/getResources().getDisplayMetrics().density):getResources().getConfiguration().screenWidthDp;
        int rail=ReaderSidebarLayout.width(width);boolean visible=pdfPagesExpanded&&rail>0;pdfPageSidebar.setVisibility(visible?View.VISIBLE:View.GONE);
        if(pdfSidebarToggle!=null){pdfSidebarToggle.setActive(visible);pdfSidebarToggle.setContentDescription(visible?"收起页面缩略图":"展开页面缩略图");}
        if(visible){if(pdfPageSidebar.getLayoutParams().width!=dp(rail)){pdfPageSidebar.getLayoutParams().width=dp(rail);pdfPageSidebar.requestLayout();}pdfPageSidebar.activate();}else pdfPageSidebar.deactivate();
    }
    private void togglePdfSidebar(){
        if(pdfReadingBody==null)return;int width=pdfReadingBody.getWidth()>0?Math.round(pdfReadingBody.getWidth()/getResources().getDisplayMetrics().density):getResources().getConfiguration().screenWidthDp;
        if(!ReaderSidebarLayout.canDock(width)){if(readerActions!=null)readerActions.showPageNavigator();return;}
        pdfPagesExpanded=!pdfPagesExpanded;getPreferences(0).edit().putBoolean("pdfPagesExpanded",pdfPagesExpanded).apply();updatePdfSidebar();
    }
    private void setHeaderCollapsed(boolean collapsed){
        if(headerCollapsed!=collapsed)dismissToolPopup();headerCollapsed=collapsed;
        if(readerHeader==null||headerToggle==null)return;
        readerHeader.setVisibility(collapsed?View.GONE:View.VISIBLE);
        headerToggle.setRotation(collapsed?0:180);headerToggle.setContentDescription(collapsed?"展开标题栏与更多设置":"收起标题栏");
    }
    private void updatePagingControls(){
        if(prev==null||next==null)return;
        prev.setRotation(!AppColors.isClassic()||horizontalPaging?-90:0);next.setRotation(!AppColors.isClassic()||horizontalPaging?-90:0);
        prev.setContentDescription(horizontalPaging?"上一页，也可向右滑动":"上一页");next.setContentDescription(horizontalPaging?"下一页，也可向左滑动":"下一页");
    }
    private void closeSearch(){if(searchDialog!=null){readerQuery=searchDialog.getQuery();searchDialog.dismiss();searchDialog=null;}}
    private void searchPdf(){
        if(current==null||pdfView==null)return;dismissToolPopup();closeReaderDialog();closeSearch();final LibraryItem doc=current;final String docId=doc.id;final PdfReaderView target=pdfView;target.pause();
        final PdfSearchDialog dialog=PdfSearchDialog.show(this,file(doc),readerQuery,page->{if(!dead&&current!=null&&docId.equals(current.id)&&pdfView==target){target.jumpTo(page);toast("已跳转到第 "+(page+1)+" 页");}});
        searchDialog=dialog;dialog.setOnDismissListener(v->{if(searchDialog==dialog){readerQuery=dialog.getQuery();searchDialog=null;}});
    }
    private void closeReaderDialog(){if(readerDialog!=null){Dialog dialog=readerDialog;readerDialog=null;dialog.dismiss();}}
    private void trackReaderDialog(Dialog dialog){readerDialog=dialog;dialog.setOnDismissListener(v->{if(readerDialog==dialog)readerDialog=null;});}
    private void readerSettings(){
        if(pdfView==null)return;final PdfReaderView target=pdfView;target.pause();closeReaderDialog();
        trackReaderDialog(ReaderDialogs.settings(this,horizontalPaging,twoFingerUndo,(horizontal,undo)->{
            if(dead||pdfView!=target)return;
            horizontalPaging=horizontal;twoFingerUndo=undo;
            getPreferences(0).edit().putBoolean("horizontalPaging",horizontal).putBoolean("twoFingerUndo",undo).apply();
            target.setHorizontalPaging(horizontal);target.setTwoFingerUndoEnabled(undo);updatePagingControls();
            if(undoButton!=null)undoButton.setContentDescription(undo?"撤销本页编辑；也可双指轻点页面":"撤销本页编辑");
        }));
    }
    private void selectInkMode(int mode){
        dismissToolPopup();
        inkMode=mode;
        String name=ToolPreferences.pdfToolName(mode);
        if(name!=null)inkColor=ToolPreferences.color(getPreferences(0),name,"inkColor",ToolPreferences.BLACK);
        applyTool();
    }
    private void showZoomControls(){
        final PdfReaderView target=pdfView;if(target==null)return;target.pause();closeReaderDialog();dismissToolPopup();
        LinearLayout panel=column();panel.setPadding(dp(22),dp(10),dp(22),dp(6));
        TextView hint=text("以当前视窗中心缩放，双指缩放仍可使用",12,MUTED);panel.addView(hint);
        EditText value=new EditText(this);value.setSingleLine(true);value.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);value.setText(Integer.toString(target.zoomPercent()));value.setSelectAllOnFocus(true);value.setHint("50—300");value.setContentDescription("缩放百分比，范围 50 到 300");panel.addView(value,new LinearLayout.LayoutParams(-1,dp(52)));
        SeekBar slider=new SeekBar(this);slider.setMax(250);slider.setProgress(target.zoomPercent()-50);slider.setContentDescription("缩放百分比滑条");slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int progress,boolean user){if(user)value.setText(Integer.toString(progress+50));}});panel.addView(slider,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout shortcuts=row();for(int percent:new int[]{50,100,200,300}){Button button=new Button(this);button.setText(percent+"%");button.setAllCaps(false);button.setOnClickListener(v->{value.setText(Integer.toString(percent));slider.setProgress(percent-50);});shortcuts.addView(button,new LinearLayout.LayoutParams(0,dp(44),1));}panel.addView(shortcuts);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("PDF 缩放").setView(panel).setNegativeButton("取消",null).setNeutralButton("适应宽度",(d,w)->{if(pdfView==target)target.fitWidth();}).setPositiveButton("应用",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{int percent=Integer.parseInt(value.getText().toString().trim());if(percent<50||percent>300)throw new NumberFormatException();if(pdfView==target)target.setZoomPercent(percent);dialog.dismiss();}catch(NumberFormatException invalid){value.setError("请输入 50 到 300 的整数");}}));dialog.show();
    }
    private void applyTool(){if(pdfView!=null){if(readerActions!=null)readerActions.cancelTextSelection();pdfView.setTool(inkMode,inkColor,toolSize(inkMode),inkMode==4?localEraserSize:eraserSize,stylusOnly);}String name=ToolPreferences.pdfToolName(inkMode);if(name!=null)ToolPreferences.saveColor(getPreferences(0),name,inkColor);String sizeKey=ToolPreferences.pdfSizeKey(inkMode);if(sizeKey!=null)getPreferences(0).edit().putFloat(sizeKey,toolSize(inkMode)).apply();updateInkTools();}
    private void updateInkTools(){
        if(current==null)return;
        int[] quickModes={0,1,10,11,2,4};for(int i=0;i<inkButtons.size();i++)inkButtons.get(i).setActive(quickModes[i]==inkMode);
        if(stylusButton!=null){stylusButton.setActive(stylusOnly);stylusButton.setContentDescription(stylusOnly?"仅手写笔绘制，已开启":"仅手写笔绘制，已关闭");}
        if(colorButton!=null)colorButton.setColor(inkColor);
        if(pdfView!=null){undoButton.setEnabled(pdfView.canUndo());redoButton.setEnabled(pdfView.canRedo());readerStatus.setText(pdfView.status());readerStatus.setTextColor(pdfView.status().contains("失败")||pdfView.status().contains("未保存")?0xffbf3939:MUTED);}
        if(pageLabel!=null)pageLabel.setText((requestedPage+1)+" / "+current.pages);
        if(prev!=null){prev.setEnabled(requestedPage>0);next.setEnabled(requestedPage<current.pages-1);}
    }
    private String hex(int c){return String.format(Locale.ROOT,"#%06X",c&0xffffff);}
    private void rememberColor(){recentColors.remove(Integer.valueOf(inkColor));recentColors.add(0,inkColor);while(recentColors.size()>6)recentColors.remove(recentColors.size()-1);JSONArray colors=new JSONArray();for(int c:recentColors)colors.put(c);getPreferences(0).edit().putString("recentColors",colors.toString()).apply();}
    private float toolSize(int tool){return tool==4?localEraserSize:tool==3?eraserSize:tool==2?markerWidth:tool==10?ballpointWidth:tool==11?pencilWidth:tool>=6&&tool<=9?shapeWidths[tool]:inkWidth;}
    private void dismissToolPopup(){if(toolPopup!=null)toolPopup.dismiss();}
    private void penSettings(int tool,View anchor){
        final PdfReaderView target=pdfView;if(target==null)return;dismissToolPopup();target.pause();
        final boolean eraser=tool==3||tool==4;
        final String toolTitle=tool==1?"钢笔":tool==10?"圆珠笔":tool==11?"铅笔":tool==2?"荧光笔":tool==3?"整笔橡皮":tool==4?"局部橡皮":tool==6?"直线":tool==7?"矩形":tool==8?"椭圆":"箭头";
        LinearLayout shell=column();shell.setPadding(dp(6),dp(4),dp(6),dp(8));shell.setFocusableInTouchMode(true);
        LinearLayout heading=row();heading.setPadding(dp(6),0,0,0);
        IconButton toolIcon=new IconButton(this,eraser?"eraser":tool==2?"marker":tool==10?"ballpoint":tool==11?"pencil":tool>=6&&tool<=9?"line":"pen",toolTitle);toolIcon.setTint(AppColors.ACCENT_DARK);toolIcon.setBackground(shape(AppColors.SOFT,12));toolIcon.setOnLongClickListener(null);toolIcon.setLongClickable(false);toolIcon.setFocusable(false);addIcon(heading,toolIcon);
        TextView title=text(toolTitle,18,INK);title.setTypeface(null,1);title.setPadding(dp(10),0,0,0);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        addIcon(heading,icon("close","关闭工具设置",v->dismissToolPopup()));shell.addView(heading);
        View rule=new View(this);rule.setBackgroundColor(AppColors.BORDER);shell.addView(rule,new LinearLayout.LayoutParams(-1,dp(1)));
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setFillViewport(false);
        LinearLayout panel=column();panel.setPadding(dp(14),dp(15),dp(14),dp(10));scroll.addView(panel);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView sizeLabel=text("",12,MUTED);panel.addView(sizeLabel);space(panel,8);
        SizePreviewView preview=new SizePreviewView(this);panel.addView(preview,new LinearLayout.LayoutParams(-1,dp(88)));
        Runnable refreshSize=()->{float diameter=eraser?toolSize(tool)*getResources().getDisplayMetrics().density:target.previewDiameter(toolSize(tool));preview.setPreview(tool==2?(inkColor&0xffffff)|0x55000000:tool==11?(inkColor&0xffffff)|0xb0000000:inkColor,diameter,eraser);sizeLabel.setText((eraser?"擦除范围":"线条粗细")+"  ·  "+String.format(Locale.CHINA,"%.1f px",diameter));};
        SeekBar seek=new SeekBar(this);seek.setMax(100);seek.setProgress(ToolSize.toProgress(ToolPreferences.pdfSizeType(tool),toolSize(tool)));seek.setContentDescription(eraser?"橡皮范围大小":"笔画粗细");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int n,boolean user){if(!user)return;float value=ToolSize.fromProgress(ToolPreferences.pdfSizeType(tool),n);if(tool==3)eraserSize=value;else if(tool==4)localEraserSize=value;else if(tool==2)markerWidth=value;else if(tool==10)ballpointWidth=value;else if(tool==11)pencilWidth=value;else if(tool>=6&&tool<=9)shapeWidths[tool]=value;else inkWidth=value;refreshSize.run();applyTool();}});panel.addView(seek,new LinearLayout.LayoutParams(-1,dp(44)));
        final EditText code=new EditText(this);code.setSingleLine(true);code.setTextSize(14);code.setTextColor(INK);code.setHintTextColor(MUTED);code.setSelectAllOnFocus(true);code.setHint("#RRGGBB");code.setText(hex(inkColor));code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});code.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);code.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        if(eraser){space(panel,8);panel.addView(text(tool==4?"圆圈表示局部擦除范围。":"圆圈表示擦除范围，触及的整条笔迹会被移除。",12,MUTED));}
        else {
            ArrayList<ColorSwatch> swatches=new ArrayList<>();ArrayList<Integer> values=new ArrayList<>();
            ColorPickerView picker=new ColorPickerView(this);picker.setColor(inkColor);
            final boolean[] syncing={false},pickerDriving={false};
            ColorPickerView.Listener choose=c->{inkColor=c|0xff000000;syncing[0]=true;if(!pickerDriving[0])picker.setColor(inkColor);code.setText(hex(inkColor));code.setError(null);syncing[0]=false;for(int j=0;j<swatches.size();j++)swatches.get(j).setActive(values.get(j)==inkColor);refreshSize.run();applyTool();};
            space(panel,6);View separator=new View(this);separator.setBackgroundColor(AppColors.BORDER);panel.addView(separator,new LinearLayout.LayoutParams(-1,dp(1)));space(panel,12);
            LinearLayout colorHeader=row();TextView colorTitle=text("笔迹颜色",13,INK);colorTitle.setTypeface(null,1);colorHeader.addView(colorTitle,new LinearLayout.LayoutParams(0,-2,1));
            IconButton sample=icon("dropper","从 PDF 页面吸取颜色",v->{dismissToolPopup();if(pdfView==target){target.beginColorPick();toast("在 PDF 页面按住拖动，松手应用颜色；返回键可取消");}});sample.setBackground(shape(AppColors.SOFT,12));addIcon(colorHeader,sample);panel.addView(colorHeader);space(panel,10);
            LinearLayout tabs=row();tabs.setPadding(dp(3),dp(3),dp(3),dp(3));tabs.setBackground(shape(AppColors.BACKGROUND,12));
            TextView presetTab=text("色板",13,ACCENT),spectrumTab=text("光谱",13,MUTED);presetTab.setGravity(Gravity.CENTER);spectrumTab.setGravity(Gravity.CENTER);presetTab.setContentDescription("显示常用与最近使用的颜色");spectrumTab.setContentDescription("显示自由调色和十六进制色号");tabs.addView(presetTab,new LinearLayout.LayoutParams(0,dp(36),1));tabs.addView(spectrumTab,new LinearLayout.LayoutParams(0,dp(36),1));panel.addView(tabs);space(panel,10);
            LinearLayout presets=column(),spectrum=column();panel.addView(presets);panel.addView(spectrum);
            int[] colors={0xff000000,0xff666666,0xffaaaaaa,0xffffffff,0xffe53935,0xffff7043,0xfff6c343,0xff81c784,0xff16805b,0xff00acc1,0xff2464d6,0xff3949ab,0xff8e44ad,0xffd94f9b,0xff8d6e63,0xffffcdd2,0xffc8e6c9,0xffbbdefb};
            for(int r=0;r<3;r++){LinearLayout line=row();for(int col=0;col<6;col++){int c=colors[r*6+col];ColorSwatch b=new ColorSwatch(this,c,hex(c));b.setActive(c==inkColor);b.setOnClickListener(v->choose.changed(c));swatches.add(b);values.add(c);line.addView(b,new LinearLayout.LayoutParams(0,dp(44),1));}presets.addView(line);}
            if(!recentColors.isEmpty()){space(presets,10);presets.addView(text("最近使用",11,MUTED));LinearLayout recent=row();for(int c:recentColors){ColorSwatch b=new ColorSwatch(this,c,hex(c));b.setActive(c==inkColor);b.setOnClickListener(v->choose.changed(c));swatches.add(b);values.add(c);recent.addView(b,new LinearLayout.LayoutParams(0,dp(44),1));}for(int i=recentColors.size();i<6;i++)recent.addView(new View(this),new LinearLayout.LayoutParams(0,dp(44),1));presets.addView(recent);}
            spectrum.addView(picker,new LinearLayout.LayoutParams(-1,dp(166)));
            picker.setListener(c->{if(!syncing[0]){pickerDriving[0]=true;try{choose.changed(c);}finally{pickerDriving[0]=false;}}});
            LinearLayout codeRow=row();codeRow.addView(text("HEX",11,MUTED));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(46),1);cp.leftMargin=dp(12);codeRow.addView(code,cp);spectrum.addView(codeRow);
            code.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(Editable s){}public void onTextChanged(CharSequence s,int a,int before,int count){if(syncing[0])return;String value=s.toString();if(value.matches("#?[0-9a-fA-F]{6}")){int c=Color.parseColor(value.startsWith("#")?value:"#"+value);inkColor=c;picker.setColor(c);for(int j=0;j<swatches.size();j++)swatches.get(j).setActive(values.get(j)==c);code.setError(null);refreshSize.run();applyTool();}else if(value.length()>=6)code.setError("请输入 6 位十六进制色号");}});
            Runnable showPresets=()->{presets.setVisibility(View.VISIBLE);spectrum.setVisibility(View.GONE);presetTab.setBackground(shape(AppColors.SURFACE,10));spectrumTab.setBackgroundColor(Color.TRANSPARENT);presetTab.setTextColor(ACCENT);spectrumTab.setTextColor(MUTED);presetTab.setSelected(true);spectrumTab.setSelected(false);if(code.hasFocus()){android.view.inputmethod.InputMethodManager im=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);im.hideSoftInputFromWindow(code.getWindowToken(),0);shell.requestFocus();}};
            presetTab.setOnClickListener(v->showPresets.run());spectrumTab.setOnClickListener(v->{presets.setVisibility(View.GONE);spectrum.setVisibility(View.VISIBLE);spectrumTab.setBackground(shape(AppColors.SURFACE,10));presetTab.setBackgroundColor(Color.TRANSPARENT);spectrumTab.setTextColor(ACCENT);presetTab.setTextColor(MUTED);spectrumTab.setSelected(true);presetTab.setSelected(false);});showPresets.run();
        }
        refreshSize.run();
        PopupWindow popup=new PopupWindow(shell,dp(360),dp(eraser?292:552),true);GradientDrawable bg=shape(AppColors.SURFACE,20);bg.setStroke(dp(1),AppColors.BORDER);popup.setBackgroundDrawable(bg);popup.setElevation(dp(12));popup.setOutsideTouchable(true);popup.setAnimationStyle(0);popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);popup.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);popup.setClippingEnabled(true);
        final View host=root;toolPopup=popup;
        Runnable position=()->{
            if(toolPopup!=popup||!anchor.isAttachedToWindow())return;
            Rect visible=new Rect();(shell.isAttachedToWindow()?shell:host).getWindowVisibleDisplayFrame(visible);
            // The focused popup receives IME insets independently from the activity.
            // Keep its visible display frame within this activity's split-screen window.
            View decor=host.getRootView();int[] decorScreen=new int[2];decor.getLocationOnScreen(decorScreen);Rect bounded=new Rect(visible);
            if(decor.getWidth()>0&&decor.getHeight()>0&&bounded.intersect(decorScreen[0],decorScreen[1],decorScreen[0]+decor.getWidth(),decorScreen[1]+decor.getHeight()))visible.set(bounded);
            if(visible.width()<=0||visible.height()<=0)return;
            int[] a=new int[2],screen=new int[2],window=new int[2];anchor.getLocationOnScreen(a);host.getLocationOnScreen(screen);host.getLocationInWindow(window);
            int[] box=PopupPlacement.place(visible.left,visible.top,visible.right,visible.bottom,a[0],a[1],a[0]+anchor.getWidth(),a[1]+anchor.getHeight(),dp(360),dp(eraser?292:552),dp(10),dp(7),dp(180));
            int x=box[0]-(screen[0]-window[0]),y=box[1]-(screen[1]-window[1]);
            if(popup.isShowing())popup.update(x,y,box[2],box[3]);else{popup.setWidth(box[2]);popup.setHeight(box[3]);popup.showAtLocation(host,Gravity.TOP|Gravity.LEFT,x,y);}
        };
        ViewTreeObserver.OnGlobalLayoutListener listener=()->position.run();host.getViewTreeObserver().addOnGlobalLayoutListener(listener);shell.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        popup.setOnDismissListener(()->{if(host.getViewTreeObserver().isAlive())host.getViewTreeObserver().removeOnGlobalLayoutListener(listener);if(shell.getViewTreeObserver().isAlive())shell.getViewTreeObserver().removeOnGlobalLayoutListener(listener);if(code.hasFocus()){android.view.inputmethod.InputMethodManager im=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);im.hideSoftInputFromWindow(code.getWindowToken(),0);}if(!eraser)rememberColor();if(toolPopup==popup)toolPopup=null;});
        code.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){android.view.inputmethod.InputMethodManager im=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);im.hideSoftInputFromWindow(code.getWindowToken(),0);shell.requestFocus();return true;}return false;});
        shell.requestFocus();position.run();
    }
    private void readerMenu(){
        if(pdfView==null||readerActions==null)return;final PdfReaderView target=pdfView;final ReaderActions actions=readerActions;target.pause();closeReaderDialog();dismissToolPopup();
        trackReaderDialog(ReaderDialogs.menu(this,horizontalPaging,
            ()->{if(pdfView==target)readerSettings();},
            ()->{if(pdfView==target)target.fitWidth();},
            ()->{if(pdfView==target)showZoomControls();},
            ()->{if(readerActions==actions)actions.showNavigator();},
            ()->{if(readerActions==actions)actions.showAdvancedTools();},
            ()->{if(readerActions==actions)actions.beginTextSelection();},
            ()->{if(readerActions==actions){reloadLibraryOnResume=true;NoteSourceActions.show(this,actions.currentSource());}},
            ()->{if(pdfView==target&&current!=null){target.pause();Intent intent=new Intent(this,OcrActivity.class);intent.putExtra(OcrActivity.EXTRA_DOC_ID,current.id);intent.putExtra(OcrActivity.EXTRA_PAGE,target.page());startActivity(intent);}},
            ()->{if(readerActions==actions)showExportOptions();},
            actions.canRestoreLastClear(),
            ()->{if(readerActions==actions)actions.restoreLastClear();},
            ()->{if(pdfView==target){final int page=target.page();trackReaderDialog(ReaderDialogs.confirmClear(this,page+1,()->{if(pdfView==target)target.clearPage(page);}));}},
            ()->{if(pdfView==target)trackReaderDialog(ReaderDialogs.help(this));}));
    }
    private void showExportOptions(){
        final LibraryItem doc=current;if(doc==null||readerActions==null)return;
        if(!readerActions.metadataReady()){toast("正在读取书签与高亮，请稍后导出");return;}
        if(!readerActions.metadataWritable()){toast("书签与高亮读取失败，本次无法完整导出");return;}
        new AlertDialog.Builder(this).setTitle("导出与分享")
                .setItems(new String[]{"带批注 PDF · 全部页面","带批注 PDF · 指定页码","当前页 PNG 图片"},(dialog,which)->{
                    if(current!=doc||pdfView==null)return;
                    if(which==0)prepareExport(EXPORT_PDF,0,doc.pages);
                    else if(which==1)showExportRange();
                    else prepareExport(EXPORT_PNG,pdfView.page(),pdfView.page()+1);
                }).show();
    }
    private void showExportRange(){
        final LibraryItem doc=current;if(doc==null)return;
        EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);input.setText("1-"+doc.pages);
        input.setHint("例如 3-12");input.setPadding(dp(20),dp(8),dp(20),dp(8));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("导出页码范围").setMessage("填写起始页和结束页，均包含在导出中。")
                .setView(input).setNegativeButton("取消",null).setPositiveButton("继续",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String value=input.getText().toString().trim();String[] parts=value.split("-",-1);
            try{if(current!=doc||parts.length!=2)throw new NumberFormatException();int first=Integer.parseInt(parts[0].trim()),last=Integer.parseInt(parts[1].trim());if(first<1||last<first||last>doc.pages)throw new NumberFormatException();dialog.dismiss();prepareExport(EXPORT_PDF,first-1,last);}
            catch(NumberFormatException invalid){input.setError("请输入 1 到 "+doc.pages+" 内的范围，例如 3-12");}
        }));dialog.show();
    }
    private void prepareExport(int kind,int from,int to){
        final LibraryItem doc=current;final ReaderActions actions=readerActions;if(doc==null||actions==null)return;
        if(kind==EXPORT_PNG){launchExportPicker(doc,kind,from,to,false);return;}
        toast("正在检查原 PDF 的数字签名…");
        worker.execute(()->{boolean signed=false;String error=null;try{signed=PdfAnnotationExporter.sourceHasSignatures(getApplicationContext(),file(doc));}catch(IOException|RuntimeException|OutOfMemoryError problem){error="无法检查原 PDF，导出已停止";}
            final boolean hasSignature=signed;final String failure=error;runOnUiThread(()->{if(dead||current!=doc||readerActions!=actions)return;if(failure!=null){toast(failure);return;}
                if(hasSignature)new AlertDialog.Builder(this).setTitle("数字签名提醒").setMessage("原 PDF 含数字签名。生成带批注的副本会使副本中的签名失效；原文件不会改动。仍要继续吗？")
                        .setNegativeButton("取消",null).setPositiveButton("继续导出",(dialog,which)->launchExportPicker(doc,kind,from,to,true)).show();
                else launchExportPicker(doc,kind,from,to,false);
            });});
    }
    private void launchExportPicker(LibraryItem doc,int kind,int from,int to,boolean allowSigned){
        if(current!=doc||readerActions==null)return;exportDocumentId=doc.id;exportFrom=from;exportTo=to;exportSigned=allowSigned;
        String name=doc.name+(kind==EXPORT_PDF&&from>0||kind==EXPORT_PDF&&to<doc.pages?"_第"+(from+1)+"-"+to+"页":"_带批注");
        Intent intent=kind==EXPORT_PDF?ReaderActions.createPdfDestination(name):ReaderActions.createPngDestination(doc.name+"_第"+(from+1)+"页");
        try{startActivityForResult(intent,kind);}catch(ActivityNotFoundException missing){toast("没有可用的系统保存位置选择器");}
    }
    private void startReaderExport(int kind,Uri destination){
        final ReaderActions actions=readerActions;final LibraryItem doc=current;if(actions==null||doc==null)return;
        TextView detail=text("正在准备…",13,MUTED);detail.setPadding(dp(22),dp(12),dp(22),dp(12));
        AlertDialog progress=new AlertDialog.Builder(this).setTitle("正在导出").setView(detail).setNegativeButton("取消导出",(dialog,which)->actions.cancelExport()).create();
        progress.setCanceledOnTouchOutside(false);progress.setCancelable(false);exportProgress=progress;progress.show();
        ReaderActions.ExportListener listener=new ReaderActions.ExportListener(){
            @Override public void progress(int done,int total){if(readerActions==actions&&exportProgress==progress)detail.setText("已处理 "+done+" / "+total+" 页");}
            @Override public void complete(PdfAnnotationExporter.Report report){if(readerActions!=actions||current!=doc)return;finishReaderExport(progress,null,destination,kind);}
            @Override public void failed(String message){if(readerActions!=actions||current!=doc)return;finishReaderExport(progress,message,destination,kind);}
        };
        if(kind==EXPORT_PDF)actions.exportPdf(destination,new PdfAnnotationExporter.PageRange(exportFrom,exportTo),exportSigned,listener);
        else actions.exportPagePng(destination,exportFrom,1800,listener);
    }
    private void finishReaderExport(Dialog progress,String failure,Uri destination,int kind){
        if(exportProgress==progress){progress.dismiss();exportProgress=null;}
        if(failure!=null){trackReaderDialog(ReaderDialogs.message(this,"导出未完成",failure,true));return;}
        new AlertDialog.Builder(this).setTitle("导出完成").setMessage("副本已保存到所选位置。")
                .setNegativeButton("完成",null).setPositiveButton("分享",(dialog,which)->{
                    try{Intent share=ReaderActions.share(destination,kind==EXPORT_PDF?"application/pdf":"image/png");startActivity(Intent.createChooser(share,"分享导出文件"));}
                    catch(ActivityNotFoundException missing){toast("没有可用的分享应用");}
                }).show();
    }
    private void jump(){
        if(current==null||pdfView==null)return;final PdfReaderView target=pdfView;target.pause();closeReaderDialog();
        trackReaderDialog(ReaderDialogs.jump(this,target.page()+1,current.pages,page->{if(pdfView==target)target.jumpTo(page);}));
    }
    private void render(int page){if(pdfView!=null)pdfView.jumpTo(page);}
    private void releaseView(){
        if(pdfPageSidebar!=null){pdfPageSidebar.close();pdfPageSidebar=null;}pdfReadingBody=null;pdfSidebarToggle=null;
        dismissToolPopup();closeReaderDialog();closeSearch();
        final ReaderActions closingActions=readerActions;readerActions=null;if(closingActions!=null)closingActions.close();
        if(exportProgress!=null){exportProgress.dismiss();exportProgress=null;}if(documentDialog!=null){documentDialog.dismiss();documentDialog=null;}
        if(pdfView!=null){pdfView.dispose();pdfView=null;}readerHeader=null;headerToggle=null;
        final DocumentEditLease closingLease=editLease;editLease=null;
        if(closingLease!=null)worker.execute(()->{if(closingActions!=null)closingActions.awaitClosed();closingLease.close();});
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){if(inserting!=null&&!inserting.finished){toast("正在插入页面，请稍候");return;}if(toolPopup!=null&&toolPopup.isShowing()){dismissToolPopup();return;}if(pdfView!=null&&pdfView.isPickingColor()){pdfView.cancelColorPick();return;}if(readerActions!=null&&readerActions.handleBack())return;if(current!=null){refreshModified(current);showLibrary();return;}if(trashMode||batchMode){trashMode=false;batchMode=false;selectedIds.clear();showLibrary();return;}if(!selectedFolder.isEmpty()){LibraryFolder folder=libraryFolder(selectedFolder);selectedFolder=folder==null?"":folder.parentId;showLibrary();return;}super.onBackPressed();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);if(!initialized){if(pendingStartupState!=null)out.putAll(pendingStartupState);out.putParcelable("pendingPickerDestination",pendingPickerDestination);out.putInt("pendingPickerKind",pendingPickerKind);return;}out.putString("libraryFilter",filter);out.putString("libraryFolder",selectedFolder);out.putString("libraryTag",selectedTag);out.putBoolean("libraryFavorites",favoritesOnly);out.putBoolean("libraryTrash",trashMode);out.putBoolean("libraryBatch",batchMode);out.putString("librarySort",librarySort.name());out.putStringArrayList("librarySelection",new ArrayList<>(selectedIds));if(importSession!=null)out.putString("importSession",importSession.id());if(backupSession!=null)out.putString("backupSession",backupSession.id());if(!pendingImportOfferId.isEmpty())out.putString("pendingImportOffer",pendingImportOfferId);if(current!=null){out.putString("doc",current.id);out.putBoolean("headerCollapsed",headerCollapsed);out.putString("readerQuery",searchDialog==null?readerQuery:searchDialog.getQuery());}out.putString("exportDocumentId",exportDocumentId);out.putInt("exportFrom",exportFrom);out.putInt("exportTo",exportTo);out.putBoolean("exportSigned",exportSigned);out.putParcelable("pendingPickerDestination",pendingPickerDestination);out.putInt("pendingPickerKind",pendingPickerKind);}
    @Override protected void onPause(){dismissToolPopup();closeReaderDialog();closeSearch();if(readerActions!=null)readerActions.pauseDialogs();if(pdfView!=null)pdfView.pause();if(initialized&&!pdfOpening&&inserting==null){if(current!=null)refreshModified(current);save();}super.onPause();}
    @Override protected void onResume(){super.onResume();stylusOnly=getPreferences(0).getBoolean("stylusOnly",true);twoFingerUndo=getPreferences(0).getBoolean("twoFingerUndo",true);if(!initialized||pdfOpening||inserting!=null)return;if(reloadLibraryOnResume){reloadLibraryOnResume=false;load();if(current==null){showLibrary();return;}}if(!lastOpenedNoteId.isEmpty()){load();for(LibraryItem item:docs)if(item.id.equals(lastOpenedNoteId)){refreshModified(item);if(thumbnails!=null)thumbnails.invalidate(item.id);break;}lastOpenedNoteId="";save();}if(current==null)populate();}
    @Override protected void onDestroy(){dead=true;generation++;if(importSession!=null)importSession.detach(importListener);if(backupSession!=null)backupSession.detach(backupListener);dismissImportDialog();dismissBackupDialog();releaseView();if(libraryAdapter!=null){libraryAdapter.dispose();libraryAdapter=null;}if(isFinishing()&&!pendingImportOfferId.isEmpty())pendingImportOffers.remove(pendingImportOfferId);if(thumbnails!=null){thumbnails.close();thumbnails=null;}super.onDestroy();}
    private void refreshModified(LibraryItem item){if(item==null)return;long latest=item.modified;if(item.isNote())latest=Math.max(latest,newestModified(new File(new File(getFilesDir(),"notes"),item.id),0));else{File[] files=getFilesDir().listFiles();if(files!=null)for(File value:files)if(value.isFile()&&(value.getName().equals(item.id+".pdf")||value.getName().startsWith(item.id+"-")&&value.getName().contains(".ink")||value.getName().equals(item.id+".reader")||value.getName().equals(item.id+".reader.bak")||value.getName().equals(item.id+".reader.new")))latest=Math.max(latest,value.lastModified());if(ReaderStore.hasPending(getFilesDir(),item.id))latest=Math.max(latest,System.currentTimeMillis());if(latest>item.modified)try{LibraryStore.touchModified(getApplicationContext(),item.id,latest);}catch(IOException ignored){}}item.modified=Math.max(1,latest);}
    private long newestModified(File file,int depth){if(file==null||!file.exists()||depth>6)return 0;long newest=file.lastModified();if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)newest=Math.max(newest,newestModified(child,depth+1));}return newest;}
}
