package cn.qingyue.pdf;

import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
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

public class MainActivity extends Activity {
    private static final int BG=AppColors.BACKGROUND, INK=AppColors.INK, MUTED=AppColors.MUTED, ACCENT=AppColors.ACCENT_DARK;
    private static final int PICK=10;
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ArrayList<Doc> docs=new ArrayList<>();
    private LinearLayout root, list;
    private TextView counter, pageLabel, readerStatus;
    private IconButton prev, next;
    private final ArrayList<IconButton> inkButtons=new ArrayList<>();
    private IconButton undoButton,redoButton,stylusButton;
    private ColorSwatch colorButton;
    private View readerHeader;
    private IconButton headerToggle;
    private boolean headerCollapsed,twoFingerUndo=true,horizontalPaging;
    private PdfSearchDialog searchDialog;
    private Dialog documentDialog,readerDialog,importDialog;
    private String readerQuery="";
    private PopupWindow toolPopup;
    private int inkMode=0,inkColor=0xffd93838;
    private float inkWidth=0.006f;
    private float markerWidth=0.03f,eraserSize=28f;
    private final ArrayList<Integer> recentColors=new ArrayList<>();
    private boolean stylusOnly=true;
    private PdfReaderView pdfView;
    private Doc current;
    private int requestedPage, generation;
    private boolean importing, dead;
    private String filter="";

    static class Doc {
        String id,name,hash,type="pdf",format="PDF",template="blank",paper="A4"; int pages,page; long size,opened;
        boolean isNote(){return "note".equals(type);}
        JSONObject json() throws JSONException {
            return new JSONObject().put("id",id).put("name",name).put("hash",hash).put("pages",pages)
                    .put("page",page).put("size",size).put("opened",opened).put("type",type).put("format",format).put("template",template).put("paper",paper);
        }
        static Doc parse(JSONObject o) throws JSONException {
            Doc d=new Doc(); d.id=o.getString("id"); d.name=o.getString("name"); d.hash=o.optString("hash");
            d.pages=o.getInt("pages"); d.page=o.optInt("page"); d.size=o.optLong("size"); d.opened=o.optLong("opened");
            d.type=o.optString("type","pdf");d.format=o.optString("format","PDF");d.template=o.optString("template","blank");d.paper=o.optString("paper","A4");
            return d;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        stylusOnly=getPreferences(0).getBoolean("stylusOnly",true);
        twoFingerUndo=getPreferences(0).getBoolean("twoFingerUndo",true);
        horizontalPaging=getPreferences(0).getBoolean("horizontalPaging",false);
        inkColor=getPreferences(0).getInt("inkColor",0xffd93838)|0xff000000;
        inkWidth=ToolSize.clamp(1,getPreferences(0).getFloat("penSize",0.006f));
        markerWidth=ToolSize.clamp(2,getPreferences(0).getFloat("markerSize",0.03f));
        eraserSize=ToolSize.clamp(3,getPreferences(0).getFloat("eraserSize",28f));
        try{JSONArray history=new JSONArray(getPreferences(0).getString("recentColors","[]"));for(int i=0;i<Math.min(6,history.length());i++)recentColors.add(history.getInt(i)|0xff000000);}catch(JSONException ignored){}
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(android.os.Build.VERSION.SDK_INT>=27?BG:AppColors.INK);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|(android.os.Build.VERSION.SDK_INT>=27?View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0));
        load(); showLibrary();
        if(state!=null) {
            String id=state.getString("doc");
            for(Doc d:docs) if(d.id.equals(id)) { open(d); break; }
            if(current!=null){setHeaderCollapsed(state.getBoolean("headerCollapsed",false));readerQuery=state.getString("readerQuery","");}
        } else {
            Intent intent=getIntent(); Uri uri=null;
            if(Intent.ACTION_VIEW.equals(intent.getAction())) uri=intent.getData();
            if(Intent.ACTION_SEND.equals(intent.getAction())) uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if(uri!=null) importPdf(uri);
        }
    }
    private int dp(float n) {return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable shape(int color,int radius) {
        GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;
    }
    private TextView text(String s,int sp,int color) {
        TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);return t;
    }
    private Button button(String s, boolean primary) {
        Button b=new Button(this);b.setText(s);b.setTextSize(14);b.setAllCaps(false);
        b.setTextColor(primary?AppColors.SURFACE:ACCENT);b.setBackground(shape(primary?AppColors.ACCENT:AppColors.SOFT,14));
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
    private void showLibrary() {
        generation++;releaseView();current=null;setupRoot();
        ScrollView page=new ScrollView(this);page.setFillViewport(true);page.setVerticalScrollBarEnabled(false);
        LinearLayout content=column();content.setPadding(0,dp(6),0,dp(28));page.addView(content);root.addView(page,new LinearLayout.LayoutParams(-1,0,1));
        boolean stackedHeader=getResources().getConfiguration().screenWidthDp<360||getResources().getConfiguration().fontScale>1.3f;
        LinearLayout header=stackedHeader?column():row();LinearLayout titles=column();LinearLayout brand=row();
        TextView name=text("晴阅",31,INK);name.setTypeface(null,1);brand.addView(name);
        TextView pdfTag=text("阅读 · 笔记",12,ACCENT);pdfTag.setTypeface(null,1);pdfTag.setPadding(dp(9),dp(4),dp(9),dp(4));pdfTag.setBackground(shape(AppColors.SOFT,8));LinearLayout.LayoutParams tag=new LinearLayout.LayoutParams(-2,-2);tag.leftMargin=dp(10);brand.addView(pdfTag,tag);
        titles.addView(brand);space(titles,4);titles.addView(text("把阅读，留给自己",13,MUTED));header.addView(titles,stackedHeader?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1));
        TextView local=text("本地 · 离线",11,ACCENT);local.setPadding(dp(13),dp(8),dp(13),dp(8));local.setBackground(shape(AppColors.SOFT,18));LinearLayout.LayoutParams localParams=new LinearLayout.LayoutParams(-2,-2);if(stackedHeader)localParams.topMargin=dp(10);header.addView(local,localParams);content.addView(header);space(content,22);
        LinearLayout hero=column();hero.setPadding(dp(18),dp(16),dp(18),dp(18));hero.setBackground(new WarmBackdrop(true));
        LinearLayout heroRow=row();FolderArtView art=new FolderArtView(this);heroRow.addView(art,new LinearLayout.LayoutParams(dp(82),dp(90)));
        LinearLayout copy=column();copy.setPadding(dp(14),0,0,0);copy.addView(text("你的随身文档库",21,INK));space(copy,7);copy.addView(text("读一份文档，记下一点灵感。",13,AppColors.MUTED));heroRow.addView(copy,new LinearLayout.LayoutParams(0,-2,1));hero.addView(heroRow);space(hero,12);
        LinearLayout createRow=row();Button note=button("＋  新建笔记",true);note.setOnClickListener(v->createNote());createRow.addView(note,new LinearLayout.LayoutParams(0,-2,1));
        Button add=button("导入文档",false);add.setBackground(shape(AppColors.SURFACE,15));add.setOnClickListener(v->pick());LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,-2,1);ap.leftMargin=dp(10);createRow.addView(add,ap);hero.addView(createRow);space(hero,10);hero.addView(text("PDF · DOC · DOCX · TXT   /   Word 与文本按文字重新排版",11,AppColors.MUTED));content.addView(hero);space(content,18);
        LinearLayout searchBox=row();searchBox.setPadding(dp(9),0,dp(14),0);searchBox.setBackground(shape(AppColors.SURFACE,17));IconButton searchIcon=new IconButton(this,"search","搜索文档");searchIcon.setClickable(false);searchIcon.setFocusable(false);searchBox.addView(searchIcon,new LinearLayout.LayoutParams(dp(38),dp(46)));
        EditText search=new EditText(this);search.setSingleLine(true);search.setTextSize(14);search.setTextColor(INK);search.setHint("搜索文档名称");search.setHintTextColor(MUTED);search.setBackgroundColor(Color.TRANSPARENT);search.setPadding(dp(3),0,0,0);search.setText(filter);searchBox.addView(search,new LinearLayout.LayoutParams(0,dp(50),1));content.addView(searchBox);space(content,20);
        counter=text("",14,INK);counter.setTypeface(null,1);content.addView(counter);space(content,12);list=column();content.addView(list);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){filter=s.toString();populate();}public void afterTextChanged(Editable e){}});
        populate();root.requestFocus();
    }
    private void populate() {
        if(current!=null||list==null)return;list.removeAllViews();Collections.sort(docs,(a,b)->Long.compare(b.opened,a.opened));counter.setText("我的文档  ·  "+docs.size()+" 份");int count=0;
        for(Doc d:docs){if(!d.name.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)))continue;count++;
            LinearLayout card=row();card.setPadding(dp(16),dp(16),dp(8),dp(16));GradientDrawable cardBg=shape(AppColors.SURFACE,20);cardBg.setStroke(dp(1),0x60efdcc9);card.setBackground(cardBg);
            TextView cover=text(d.isNote()?"笔记":d.format,14,ACCENT);cover.setTypeface(null,1);cover.setGravity(Gravity.CENTER);GradientDrawable coverBg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xffffeddd,0xffffdbc0});coverBg.setCornerRadius(dp(11));cover.setBackground(coverBg);card.addView(cover,new LinearLayout.LayoutParams(dp(52),dp(68)));
            LinearLayout details=column();details.setPadding(dp(14),0,dp(12),0);TextView name=text(d.name,16,INK);name.setTypeface(null,1);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);details.addView(name);space(details,5);
            details.addView(text(metadata(d),12,MUTED));space(details,7);details.addView(text(d.isNote()?"继续记录 · 手写 / 图片 / 图形":"继续阅读 · 第 "+(d.page+1)+" 页",12,ACCENT));space(details,8);
            if(!d.isNote()){ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);progress.setProgress(Math.round((d.page+1)*1000f/d.pages));progress.setProgressTintList(android.content.res.ColorStateList.valueOf(AppColors.ACCENT));progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(AppColors.SOFT));details.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));}
            card.addView(details,new LinearLayout.LayoutParams(0,-2,1));IconButton more=icon("more","管理 "+d.name,v->manage(d));more.setBackground(shape(AppColors.SOFT,12));card.addView(more,new LinearLayout.LayoutParams(dp(40),dp(44)));card.setOnClickListener(v->open(d));list.addView(card);space(list,12);
        }
        if(count==0){LinearLayout empty=column();empty.setGravity(Gravity.CENTER);empty.setPadding(dp(20),dp(24),dp(20),dp(28));FolderArtView art=new FolderArtView(this);empty.addView(art,new LinearLayout.LayoutParams(dp(88),dp(88)));space(empty,10);empty.addView(text(docs.isEmpty()?"从一份文档或一本笔记开始":"没有找到匹配的文档",17,INK));space(empty,8);TextView hint=text(docs.isEmpty()?"文档与笔记都留在本机":"换个关键词试试",12,MUTED);hint.setGravity(Gravity.CENTER);empty.addView(hint);list.addView(empty);}
    }
    private String metadata(Doc d){return d.isNote()?("infinite".equals(d.paper)?"无限画布":d.paper)+" · "+("grid".equals(d.template)?"网格":"ruled".equals(d.template)?"横线":"空白"):d.pages+" 页 · "+size(d.size)+("PDF".equals(d.format)?"":" · 文字排版");}
    private void createNote(){
        if(importing){toast("正在导入，请稍候");return;}
        trackDocumentDialog(NoteDialogs.create(this,(name,template,paper)->{
            Doc d=new Doc();d.id=UUID.randomUUID().toString();d.name=name;d.hash="";d.type="note";d.format="NOTE";d.template=template;d.paper=paper;d.pages=1;d.opened=System.currentTimeMillis();
            try{NoteStore.create(this,d.id,template,paper);docs.add(d);save();populate();open(d);}catch(IOException e){trackDocumentDialog(ReaderDialogs.message(this,"创建失败","无法保存笔记，请检查本机可用空间。",true));}
        }));
    }
    private String size(long bytes) {return bytes<1048576?String.format(Locale.CHINA,"%.0f KB",bytes/1024.0):String.format(Locale.CHINA,"%.1f MB",bytes/1048576.0);}
    private File file(Doc d) {return new File(getFilesDir(),d.id+".pdf");}
    private void load() {
        try {JSONArray a=new JSONArray(getPreferences(0).getString("library","[]"));
            for(int i=0;i<a.length();i++){Doc d=Doc.parse(a.getJSONObject(i));if(d.id.matches("[a-f0-9-]{36}")&&d.pages>0&&(d.isNote()?NoteStore.exists(this,d.id):file(d).isFile())){d.page=Math.max(0,Math.min(d.page,d.pages-1));docs.add(d);}}
        } catch(Exception e){toast("文档列表读取失败，请重新导入文件");}
    }
    private void save() {
        try {JSONArray a=new JSONArray();for(Doc d:docs)a.put(d.json());getPreferences(0).edit().putString("library",a.toString()).apply();}
        catch(JSONException e){toast("保存阅读记录失败");}
    }
    private void pick() {
        if(importing){toast("正在导入，请稍候");return;}
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/pdf","application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document","text/plain","application/octet-stream"});
        try{startActivityForResult(i,PICK);}catch(ActivityNotFoundException e){toast("没有可用的系统文件选择器");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==PICK&&result==RESULT_OK&&data!=null&&data.getData()!=null)importPdf(data.getData());
    }
    private void importPdf(Uri uri) {
        if(importing)return;importing=true;
        Dialog progress=ReaderDialogs.progress(this,"正在导入文档","正在保存到本机。Word 与文本将按文字重新排版，较长文档需要一些时间…");importDialog=progress;progress.setOnDismissListener(v->{if(importDialog==progress)importDialog=null;});
        worker.execute(()->{
            File tmp=null,converted=null,stored=null;
            try {
                String name="未命名文档";
                try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
                    if(c!=null&&c.moveToFirst()&&!c.isNull(0))name=c.getString(0);
                } catch(Exception ignored){}
                tmp=File.createTempFile("import-",".tmp",getCacheDir());
                MessageDigest digest=MessageDigest.getInstance("SHA-256");long bytes=0;
                try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(tmp)) {
                    if(in==null)throw new IOException("无法读取所选文件");
                    byte[] buffer=new byte[65536];int n;
                    while((n=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("导入已取消");bytes+=n;if(bytes>200L*1024*1024)throw new IOException("每份文档最大支持 200 MB");out.write(buffer,0,n);digest.update(buffer,0,n);}
                }
                File readable=tmp;String format="PDF";
                byte[] signature=new byte[1024];int signatureLength;try(InputStream in=new FileInputStream(tmp)){signatureLength=in.read(signature);}
                boolean isPdf=signatureLength>0&&new String(signature,0,signatureLength,java.nio.charset.StandardCharsets.ISO_8859_1).contains("%PDF-");
                if(!isPdf){converted=File.createTempFile("converted-",".pdf",getCacheDir());format=DocumentImport.convert(this,tmp,name,getContentResolver().getType(uri),converted);readable=converted;}
                int pages;
                try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(readable,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd)){pages=renderer.getPageCount();}
                if(pages<1)throw new IOException("这份文档没有可阅读的页面");
                StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
                Doc d=new Doc();d.id=UUID.randomUUID().toString();d.name=name;d.hash=hash.toString();d.pages=pages;d.size=bytes;d.format=format;d.opened=System.currentTimeMillis();
                stored=file(d);if(!readable.renameTo(stored))throw new IOException("本机空间不足，无法保存文件");
                File added=stored;
                runOnUiThread(()->{
                    importing=false;if(dead){added.delete();return;}progress.dismiss();
                    for(Doc old:docs)if(!old.isNote()&&old.hash.equals(d.hash)){added.delete();toast("文档已存在，已为你打开");open(old);return;}
                    docs.add(d);save();toast("PDF".equals(d.format)?"导入成功":"导入成功，已按文字重新排版");open(d);
                });
            } catch(Exception | LinkageError e){
                if(stored!=null)stored.delete();
                String message=e instanceof SecurityException?"文件无访问权限，或文档已加密。请先解密后导入。":e instanceof IOException&&e.getMessage()!=null?e.getMessage():"无法导入：请确认是完整、未加密的 PDF、DOC、DOCX 或 TXT 文件。";
                runOnUiThread(()->{importing=false;if(!dead){progress.dismiss();trackDocumentDialog(ReaderDialogs.message(this,"导入失败",message,true));}});
            } finally{if(tmp!=null)tmp.delete();if(converted!=null)converted.delete();}
        });
    }
    private void manage(Doc d) {
        trackDocumentDialog(DocumentDialogs.actions(this,d.name,metadata(d),d.page+1,d.pages,d.isNote(),d.format,
            ()->open(d),
            ()->trackDocumentDialog(DocumentDialogs.rename(this,d.name,name->{d.name=name;save();populate();})),
            ()->trackDocumentDialog(DocumentDialogs.remove(this,d.name,d.isNote(),()->{
                if(d.isNote()){try{NoteStore.delete(this,d.id);}catch(IOException e){toast("笔记移除失败，请稍后重试");return;}docs.remove(d);save();populate();return;}
                if(file(d).exists()&&!file(d).delete()){toast("移除失败，请稍后重试");return;}
                worker.execute(()->InkStore.deleteDocument(getFilesDir(),d.id));docs.remove(d);save();populate();
            }))));
    }
    private void trackDocumentDialog(Dialog dialog){documentDialog=dialog;dialog.setOnDismissListener(v->{if(documentDialog==dialog)documentDialog=null;});}
    private IconButton icon(String kind,String label,View.OnClickListener action){
        IconButton b=new IconButton(this,kind,label);b.setOnClickListener(action);return b;
    }
    private void addIcon(LinearLayout row,IconButton button){row.addView(button,new LinearLayout.LayoutParams(dp(44),dp(44)));}
    private void open(Doc d) {
        if(d.isNote()){d.opened=System.currentTimeMillis();save();startActivity(new Intent(this,NoteActivity.class).putExtra("id",d.id).putExtra("name",d.name));return;}
        generation++;releaseView();current=d;readerQuery="";headerCollapsed=false;d.opened=System.currentTimeMillis();save();requestedPage=d.page;setupRoot();
        LinearLayout header=row();header.setPadding(dp(8),dp(3),dp(8),dp(3));header.setBackgroundColor(AppColors.SURFACE);
        addIcon(header,icon("back","返回文档库",v->showLibrary()));
        LinearLayout titles=column();titles.setPadding(dp(6),0,0,0);
        TextView title=text(d.name,15,INK);title.setTypeface(null,1);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);titles.addView(title);
        readerStatus=text("准备文档…",10,MUTED);readerStatus.setSingleLine(true);readerStatus.setEllipsize(TextUtils.TruncateAt.END);readerStatus.setOnClickListener(v->{if(pdfView!=null){if(pdfView.isPickingColor())pdfView.cancelColorPick();else pdfView.retry();}});titles.addView(readerStatus);
        header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        addIcon(header,icon("more","更多操作与设置",v->readerMenu()));readerHeader=header;root.addView(header);
        annotationTools();
        FrameLayout canvas=new FrameLayout(this);
        pdfView=new PdfReaderView(this,file(d),getFilesDir(),d.id,d.pages,d.page,worker,new PdfReaderView.Host(){
            @Override public void changed(int page){if(dead||current!=d)return;requestedPage=page;if(d.page!=page){d.page=page;save();}updateInkTools();}
            @Override public void error(String message){if(!dead)toast(message);}
            @Override public void colorPicked(int picked){if(dead||current!=d)return;inkColor=picked;rememberColor();applyTool();toast("已提取 "+hex(inkColor));}
            @Override public void readingScrolled(){if(!dead&&current==d)setHeaderCollapsed(true);}
        });
        pdfView.setTwoFingerUndoEnabled(twoFingerUndo);
        pdfView.setHorizontalPaging(horizontalPaging);
        canvas.addView(pdfView,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout pager=row();pager.setPadding(dp(4),0,dp(4),0);pager.setBackground(shape(AppColors.SURFACE,24));pager.setElevation(dp(4));
        prev=icon("up","上一页，也可向右滑动",v->render(requestedPage-1));next=icon("down","下一页，也可向左滑动",v->render(requestedPage+1));addIcon(pager,prev);
        pageLabel=text((d.page+1)+" / "+d.pages,12,INK);pageLabel.setGravity(Gravity.CENTER);pageLabel.setMinWidth(dp(60));pageLabel.setPadding(dp(8),0,dp(8),0);pageLabel.setContentDescription("当前页码，点击跳页");pageLabel.setOnClickListener(v->jump());pager.addView(pageLabel,new LinearLayout.LayoutParams(-2,dp(44)));addIcon(pager,next);
        FrameLayout.LayoutParams overlay=new FrameLayout.LayoutParams(-2,dp(44),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);overlay.bottomMargin=dp(12);canvas.addView(pager,overlay);
        root.addView(canvas,new LinearLayout.LayoutParams(-1,0,1));applyTool();updatePagingControls();updateInkTools();
    }
    private void annotationTools(){
        LinearLayout strip=row();strip.setPadding(dp(4),0,dp(4),0);strip.setBackgroundColor(AppColors.SURFACE);
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);scroll.setFillViewport(false);
        LinearLayout toolbar=row();toolbar.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);toolbar.setPadding(dp(2),dp(2),dp(2),dp(2));
        inkButtons.clear();String[] kinds={"hand","pen","marker","eraser"},labels={"浏览 / 手指滚动","画笔；长按或再次点击调节","荧光笔；长按或再次点击调节","整笔橡皮；长按或再次点击调节"};
        for(int i=0;i<kinds.length;i++){final int mode=i;IconButton b=icon(kinds[i],labels[i],v->{
            if(inkMode==mode&&mode!=0){penSettings(mode,v);return;}inkMode=mode;applyTool();
        });if(mode!=0)b.setOnLongClickListener(v->{inkMode=mode;applyTool();penSettings(mode,v);return true;});inkButtons.add(b);addIcon(toolbar,b);}
        View divider=new View(this);divider.setBackgroundColor(AppColors.BORDER);LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(dp(1),dp(22));gap.setMargins(dp(5),0,dp(5),0);toolbar.addView(divider,gap);
        undoButton=icon("undo",twoFingerUndo?"撤销本页编辑；也可双指轻点页面":"撤销本页编辑",v->{if(pdfView!=null)pdfView.undo();});redoButton=icon("redo","重做本页编辑",v->{if(pdfView!=null)pdfView.redo();});addIcon(toolbar,undoButton);addIcon(toolbar,redoButton);
        colorButton=new ColorSwatch(this,inkColor,"笔迹颜色与调色盘");colorButton.setOnClickListener(v->penSettings(inkMode==2?2:1,v));colorButton.setOnLongClickListener(v->{penSettings(inkMode==2?2:1,v);return true;});toolbar.addView(colorButton,new LinearLayout.LayoutParams(dp(44),dp(44)));
        stylusButton=icon("stylus","切换仅手写笔绘制",v->{stylusOnly=!stylusOnly;getPreferences(0).edit().putBoolean("stylusOnly",stylusOnly).apply();applyTool();toast(stylusOnly?"仅手写笔绘制；手指滚动与翻页":"已允许手指绘制；双指可以移动页面");});addIcon(toolbar,stylusButton);
        scroll.addView(toolbar);strip.addView(scroll,new LinearLayout.LayoutParams(0,dp(48),1));
        View edge=new View(this);edge.setBackgroundColor(AppColors.BORDER);strip.addView(edge,new LinearLayout.LayoutParams(dp(1),dp(22)));
        addIcon(strip,icon("search","搜索 PDF 内容",v->searchPdf()));
        headerToggle=icon("down","收起标题栏",v->setHeaderCollapsed(!headerCollapsed));headerToggle.setRotation(180);addIcon(strip,headerToggle);root.addView(strip,new LinearLayout.LayoutParams(-1,dp(48)));
    }
    private void setHeaderCollapsed(boolean collapsed){
        if(readerHeader==null||headerToggle==null)return;
        if(headerCollapsed!=collapsed)dismissToolPopup();headerCollapsed=collapsed;
        readerHeader.setVisibility(collapsed?View.GONE:View.VISIBLE);
        headerToggle.setRotation(collapsed?0:180);headerToggle.setContentDescription(collapsed?"展开标题栏与更多设置":"收起标题栏");
    }
    private void updatePagingControls(){
        if(prev==null||next==null)return;
        prev.setRotation(horizontalPaging?-90:0);next.setRotation(horizontalPaging?-90:0);
        prev.setContentDescription(horizontalPaging?"上一页，也可向右滑动":"上一页");next.setContentDescription(horizontalPaging?"下一页，也可向左滑动":"下一页");
    }
    private void closeSearch(){if(searchDialog!=null){readerQuery=searchDialog.getQuery();searchDialog.dismiss();searchDialog=null;}}
    private void searchPdf(){
        if(current==null||pdfView==null)return;dismissToolPopup();closeReaderDialog();closeSearch();final Doc doc=current;final PdfReaderView target=pdfView;target.pause();
        final PdfSearchDialog dialog=PdfSearchDialog.show(this,file(doc),readerQuery,page->{if(!dead&&current==doc&&pdfView==target){target.jumpTo(page);toast("已跳转到第 "+(page+1)+" 页");}});
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
    private void applyTool(){if(pdfView!=null)pdfView.setTool(inkMode,inkColor,inkMode==2?markerWidth:inkWidth,eraserSize,stylusOnly);getPreferences(0).edit().putInt("inkColor",inkColor).putFloat("penSize",inkWidth).putFloat("markerSize",markerWidth).putFloat("eraserSize",eraserSize).apply();updateInkTools();}
    private void updateInkTools(){
        if(current==null)return;
        for(int i=0;i<inkButtons.size();i++)inkButtons.get(i).setActive(i==inkMode);
        if(stylusButton!=null){stylusButton.setActive(stylusOnly);stylusButton.setContentDescription(stylusOnly?"仅手写笔绘制，已开启":"仅手写笔绘制，已关闭");}
        if(colorButton!=null)colorButton.setColor(inkColor);
        if(pdfView!=null){undoButton.setEnabled(pdfView.canUndo());redoButton.setEnabled(pdfView.canRedo());readerStatus.setText(pdfView.status());readerStatus.setTextColor(pdfView.status().contains("失败")||pdfView.status().contains("未保存")?0xffbf3939:MUTED);}
        if(pageLabel!=null)pageLabel.setText((requestedPage+1)+" / "+current.pages);
        if(prev!=null){prev.setEnabled(requestedPage>0);next.setEnabled(requestedPage<current.pages-1);}
    }
    private String hex(int c){return String.format(Locale.ROOT,"#%06X",c&0xffffff);}
    private void rememberColor(){recentColors.remove(Integer.valueOf(inkColor));recentColors.add(0,inkColor);while(recentColors.size()>6)recentColors.remove(recentColors.size()-1);JSONArray colors=new JSONArray();for(int c:recentColors)colors.put(c);getPreferences(0).edit().putString("recentColors",colors.toString()).apply();}
    private float toolSize(int tool){return tool==3?eraserSize:tool==2?markerWidth:inkWidth;}
    private void dismissToolPopup(){if(toolPopup!=null)toolPopup.dismiss();}
    private void penSettings(int tool,View anchor){
        final PdfReaderView target=pdfView;if(target==null)return;dismissToolPopup();target.pause();
        final boolean eraser=tool==3;
        LinearLayout shell=column();shell.setPadding(dp(6),dp(4),dp(6),dp(8));shell.setFocusableInTouchMode(true);
        LinearLayout heading=row();heading.setPadding(dp(6),0,0,0);
        IconButton toolIcon=new IconButton(this,eraser?"eraser":tool==2?"marker":"pen",eraser?"橡皮":"画笔");toolIcon.setTint(AppColors.ACCENT_DARK);toolIcon.setBackground(shape(AppColors.SOFT,12));toolIcon.setOnLongClickListener(null);toolIcon.setLongClickable(false);toolIcon.setClickable(false);toolIcon.setFocusable(false);addIcon(heading,toolIcon);
        TextView title=text(eraser?"橡皮":tool==2?"荧光笔":"画笔",18,INK);title.setTypeface(null,1);title.setPadding(dp(10),0,0,0);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        addIcon(heading,icon("close","关闭工具设置",v->dismissToolPopup()));shell.addView(heading);
        View rule=new View(this);rule.setBackgroundColor(AppColors.BORDER);shell.addView(rule,new LinearLayout.LayoutParams(-1,dp(1)));
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setFillViewport(false);
        LinearLayout panel=column();panel.setPadding(dp(14),dp(15),dp(14),dp(10));scroll.addView(panel);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView sizeLabel=text("",12,MUTED);panel.addView(sizeLabel);space(panel,8);
        SizePreviewView preview=new SizePreviewView(this);panel.addView(preview,new LinearLayout.LayoutParams(-1,dp(88)));
        Runnable refreshSize=()->{float diameter=eraser?eraserSize*getResources().getDisplayMetrics().density:target.previewDiameter(toolSize(tool));preview.setPreview(tool==2?(inkColor&0xffffff)|0x55000000:inkColor,diameter,eraser);sizeLabel.setText((eraser?"擦除范围":"线条粗细")+"  ·  "+String.format(Locale.CHINA,"%.1f px",diameter));};
        SeekBar seek=new SeekBar(this);seek.setMax(100);seek.setProgress(ToolSize.toProgress(tool,toolSize(tool)));seek.setContentDescription(eraser?"橡皮范围大小":"笔画粗细");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int n,boolean user){if(!user)return;float value=ToolSize.fromProgress(tool,n);if(tool==3)eraserSize=value;else if(tool==2)markerWidth=value;else inkWidth=value;refreshSize.run();applyTool();}});panel.addView(seek,new LinearLayout.LayoutParams(-1,dp(44)));
        final EditText code=new EditText(this);code.setSingleLine(true);code.setTextSize(14);code.setTextColor(INK);code.setHintTextColor(MUTED);code.setSelectAllOnFocus(true);code.setHint("#RRGGBB");code.setText(hex(inkColor));code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});code.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);code.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        if(eraser){space(panel,8);panel.addView(text("圆圈表示擦除范围，触及的整条笔迹会被移除。",12,MUTED));}
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
        if(pdfView==null)return;final PdfReaderView target=pdfView;target.pause();closeReaderDialog();dismissToolPopup();
        trackReaderDialog(ReaderDialogs.menu(this,horizontalPaging,
            ()->{if(pdfView==target)readerSettings();},
            ()->{if(pdfView==target)target.fitWidth();},
            ()->{if(pdfView==target){final int page=target.page();trackReaderDialog(ReaderDialogs.confirmClear(this,page+1,()->{if(pdfView==target)target.clearPage(page);}));}},
            ()->{if(pdfView==target)trackReaderDialog(ReaderDialogs.help(this));}));
    }
    private void jump(){
        if(current==null||pdfView==null)return;final PdfReaderView target=pdfView;target.pause();closeReaderDialog();
        trackReaderDialog(ReaderDialogs.jump(this,target.page()+1,current.pages,page->{if(pdfView==target)target.jumpTo(page);}));
    }
    private void render(int page){if(pdfView!=null)pdfView.jumpTo(page);}
    private void releaseView(){dismissToolPopup();closeReaderDialog();closeSearch();if(documentDialog!=null){documentDialog.dismiss();documentDialog=null;}if(pdfView!=null){pdfView.dispose();pdfView=null;}readerHeader=null;headerToggle=null;}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){if(importing)return;if(toolPopup!=null&&toolPopup.isShowing()){dismissToolPopup();return;}if(pdfView!=null&&pdfView.isPickingColor()){pdfView.cancelColorPick();return;}if(current!=null)showLibrary();else super.onBackPressed();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);if(current!=null){out.putString("doc",current.id);out.putBoolean("headerCollapsed",headerCollapsed);out.putString("readerQuery",searchDialog==null?readerQuery:searchDialog.getQuery());}}
    @Override protected void onPause(){dismissToolPopup();closeReaderDialog();closeSearch();if(pdfView!=null)pdfView.pause();save();super.onPause();}
    @Override protected void onResume(){super.onResume();stylusOnly=getPreferences(0).getBoolean("stylusOnly",true);twoFingerUndo=getPreferences(0).getBoolean("twoFingerUndo",true);if(current==null)populate();}
    @Override protected void onDestroy(){dead=true;generation++;if(importDialog!=null){importDialog.dismiss();importDialog=null;}releaseView();super.onDestroy();}
}
