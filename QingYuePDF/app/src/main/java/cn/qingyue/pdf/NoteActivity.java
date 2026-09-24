package cn.qingyue.pdf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native note editor. The canvas owns drawing, history and durable note data. */
public final class NoteActivity extends ThemedActivity {
    private static final int PICK_IMAGE = 31;
    private static final int EXPORT_PDF = 32, EXPORT_PNG = 33;
    private static final ExecutorService IMAGES = Executors.newSingleThreadExecutor();
    private static final ExecutorService EXPORTS = Executors.newSingleThreadExecutor();
    private static final ConcurrentHashMap<String, ExportJob> EXPORT_JOBS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ImageJob> IMAGE_JOBS = new ConcurrentHashMap<>();
    private String noteId, name, tool = "pen";
    private NoteDocument document;
    private DocumentEditLease editLease;
    private IconButton sidebarToggle;
    private NoteCanvasView canvas;
    private LinearLayout root, header, readingBody;
    private NotePageSidebar pageSidebar, dialogSidebar;
    private boolean pagesExpanded;
    private TextView status;
    private IconButton undo, redo, trash, stylus, imageButton, headerToggle;
    private ColorSwatch colorButton;
    private final LinkedHashMap<String, IconButton> tools = new LinkedHashMap<>();
    private int color = ToolPreferences.BLACK;
    private float penSize = 2f, markerSize = 18f, eraserSize = 30f;
    private boolean stylusOnly, twoFingerUndo, resumed, destroyed, headerCollapsed, sourceConsumed;
    private boolean horizontalPages;
    private TextView pagesButton, textButton;
    private IconButton readModeButton, selectionButton;
    private boolean readOnly, wholeStrokeEraser, lassoFreeform=true;
    private int lassoFilter=NoteCanvasView.LASSO_ALL;
    private long pendingSourceItemId;
    private PopupWindow popup;
    private Dialog dialog;
    private SharedPreferences common, preferences;
    private String pendingExport;
    private final android.os.Handler pageSyncHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable pendingPageSync = this::syncLibraryPages;

    private static final class ExportJob {
        final String noteId;
        final NoteDocument snapshot;
        final boolean pdf;
        ExportJob(String noteId, NoteDocument snapshot, boolean pdf) { this.noteId = noteId; this.snapshot = snapshot; this.pdf = pdf; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        common = getSharedPreferences("MainActivity", MODE_PRIVATE);
        preferences = getSharedPreferences("note_tools", MODE_PRIVATE);
        horizontalPages=preferences.getBoolean("horizontalPages",false);
        wholeStrokeEraser=preferences.getBoolean("wholeStrokeEraser",false);lassoFreeform=preferences.getBoolean("lassoFreeform",true);lassoFilter=preferences.getInt("lassoFilter",NoteCanvasView.LASSO_ALL);
        noteId = getIntent().getStringExtra("id"); name = getIntent().getStringExtra("name");
        if (name == null || name.trim().isEmpty()) name = "未命名笔记";
        stylusOnly = common.getBoolean("stylusOnly", true);
        twoFingerUndo = common.getBoolean("twoFingerUndo", true);
        color = ToolPreferences.color(preferences, "pen", "color", common.getInt("inkColor", ToolPreferences.BLACK));
        penSize = clamp(preferences.getFloat("pen", 2f), .5f, 20f);
        markerSize = clamp(preferences.getFloat("marker", 18f), 4f, 60f);
        eraserSize = clamp(preferences.getFloat("eraser", 30f), 8f, 100f);
        pendingSourceItemId=state==null?getIntent().getLongExtra("pendingSourceItemId",0):state.getLong("pendingSourceItemId",getIntent().getLongExtra("pendingSourceItemId",0));
        if (state != null) { tool = state.getString("tool", "pen"); headerCollapsed = state.getBoolean("collapsed", false); pendingExport = state.getString("pendingExport"); sourceConsumed=state.getBoolean("sourceConsumed",false); readOnly=state.getBoolean("readOnly",false); }
        if (colorTool(tool)) color = ToolPreferences.color(preferences, tool, "color", common.getInt("inkColor", ToolPreferences.BLACK));
        root = NoteDialogs.column(this); root.setBackgroundColor(AppColors.BACKGROUND); root.setFitsSystemWindows(true); setContentView(root);
        try { editLease=DocumentEditLease.acquire(this,noteId); document = NoteStore.load(this, noteId); }
        catch (Exception failure) {
            if(editLease!=null){editLease.close();editLease=null;}
            status = NoteDialogs.label(this, "笔记暂时无法打开", 18, AppColors.INK);
            status.setGravity(Gravity.CENTER); root.addView(status, new LinearLayout.LayoutParams(-1, -1));
            showDialog(ReaderDialogs.message(this, "无法打开笔记", failure.getMessage()==null?"笔记文件不可用，请返回文档库。":failure.getMessage(), true));
            return;
        }
        int requestedPage=state!=null&&state.containsKey("currentPage")?state.getInt("currentPage"):getIntent().getIntExtra("page",document.currentPage());
        if(requestedPage>=0&&requestedPage<document.pageCount())document.selectPage(requestedPage);
        pagesExpanded=state==null?preferences.getBoolean("pagesExpanded",true):state.getBoolean("pagesExpanded",true);
        makeHeader(); makeToolbar();
        canvas = new NoteCanvasView(this, document, noteId, new NoteCanvasView.Host() {
            @Override public void changed() { updateTools(); }
            @Override public void pageAdded() { toast("已插入第 " + (document.currentPage()+1) + " 页"); refreshPageSidebars(true); schedulePageSync(); }
            @Override public void pageChanged() { refreshCurrentPage(); schedulePageSync(); }
            @Override public void viewportPageChanged(int page) { refreshCurrentPage(); if(undo!=null){undo.setEnabled(!readOnly&&canvas.canUndo());redo.setEnabled(!readOnly&&canvas.canRedo());} }
            @Override public void viewportSettled() { pageSyncHandler.removeCallbacks(pendingPageSync); syncLibraryPages(); }
            @Override public void readingScrolled() { if (!headerCollapsed) setCollapsed(true); }
            @Override public void error(String message) { if (!destroyed) { if (status != null) {status.setText("保存或操作失败"); status.setTextColor(0xffb75448);} if (resumed) showDialog(ReaderDialogs.message(NoteActivity.this, "笔记提示", message, true)); else toast(message); } }
        });
        readingBody=NoteDialogs.row(this);readingBody.setGravity(Gravity.TOP);
        pageSidebar=createPageSidebar(false);readingBody.addView(pageSidebar,new LinearLayout.LayoutParams(dp(112),-1));
        LinearLayout.LayoutParams canvasSize = new LinearLayout.LayoutParams(0, -1, 1);
        if (!AppColors.isClassic()) canvasSize.setMargins(dp(8), 0, dp(8), dp(10));
        readingBody.addView(canvas, canvasSize);root.addView(readingBody,new LinearLayout.LayoutParams(-1,0,1));
        readingBody.addOnLayoutChangeListener((view,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->updatePageSidebar());
        updatePageSidebar();
        canvas.setHorizontalPages(horizontalPages);
        canvas.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {if (right > left && bottom > top) consumeImage();});
        setCollapsed(headerCollapsed); applyTool();
        String incoming=getIntent().getStringExtra(NoteSourceActions.EXTRA_SOURCE);
        if(incoming!=null&&!sourceConsumed)canvas.addOnLayoutChangeListener((view,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{if(right>left&&bottom>top&&!sourceConsumed)consumeSource(incoming);});
    }

    private void makeHeader() {
        if (!AppColors.isClassic()) { makePaperHeader(); return; }
        header = NoteDialogs.row(this); header.setPadding(dp(7), dp(4), dp(7), dp(4));
        addIcon(header, icon("back", "返回文档库", v -> finish()));
        LinearLayout titles = NoteDialogs.column(this); titles.setPadding(dp(5), 0, dp(5), 0);
        TextView title = NoteDialogs.label(this, name, 16, AppColors.INK); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); title.setTypeface(null, 1); titles.addView(title);
        status = NoteDialogs.label(this, paperDescription() + " · 自动保存", 10, AppColors.MUTED); status.setSingleLine(true); status.setEllipsize(android.text.TextUtils.TruncateAt.END); status.setPadding(0, dp(3), 0, 0); titles.addView(status);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView sources=NoteDialogs.label(this,"来源",12,AppColors.ACCENT_DARK);sources.setGravity(Gravity.CENTER);sources.setContentDescription("查看笔记中的来源链接");sources.setOnClickListener(v->showSources());header.addView(sources,new LinearLayout.LayoutParams(dp(48),-1));
        pagesButton=NoteDialogs.label(this,"页码",12,AppColors.ACCENT_DARK);pagesButton.setGravity(Gravity.CENTER);pagesButton.setContentDescription("展开页面工作栏");pagesButton.setOnClickListener(v->showPages());header.addView(pagesButton,new LinearLayout.LayoutParams(dp(48),-1));
        addExportButton();
        addIcon(header, icon("settings", "笔记设置", v -> settings()));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(59)));
    }

    private void makePaperHeader() {
        header = NoteDialogs.row(this); header.setPadding(dp(8), dp(5), dp(8), dp(5));
        addIcon(header, icon("back", "返回文档库", v -> finish()));
        LinearLayout titles = NoteDialogs.column(this); titles.setPadding(dp(7), 0, dp(8), 0);
        TextView title = NoteDialogs.label(this, name, 16, AppColors.INK);
        title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTypeface(null, 1); titles.addView(title);
        status = NoteDialogs.label(this, paperDescription() + " · 自动保存", 10, AppColors.MUTED);
        status.setSingleLine(true); status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setPadding(0, dp(4), 0, 0); titles.addView(status);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        pagesButton = NoteDialogs.label(this, "页码", 12, AppColors.ACCENT_DARK);
        pagesButton.setGravity(Gravity.CENTER); pagesButton.setBackground(NoteDialogs.shape(this, AppColors.SOFT, 12));
        pagesButton.setContentDescription("展开页面工作栏"); pagesButton.setOnClickListener(v -> showPages());
        header.addView(pagesButton, new LinearLayout.LayoutParams(dp(64), dp(36)));
        addIcon(header, icon("more", "笔记来源、导出、添加页面与设置", this::showActionMenu));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(64)));
    }

    private void addExportButton() {
        TextView button = NoteDialogs.label(this, "导出 PDF", 12, AppColors.ACCENT_DARK);
        button.setGravity(Gravity.CENTER); button.setContentDescription("导出 PDF 或 PNG");
        button.setOnClickListener(v -> {
            Dialog choices = new android.app.AlertDialog.Builder(this)
                    .setTitle("导出笔记")
                    .setItems(new String[]{"导出 PDF（全部页面）", "导出 PNG（当前页）"}, (dialog, which) -> beginExport(which == 0))
                    .create();
            choices.show(); showDialog(choices);
        });
        header.addView(button, new LinearLayout.LayoutParams(dp(68), -1));
    }

    private void showActionMenu(View anchor) {
        if (canvas == null) return;
        dismissPopup();
        ArrayList<NoteActionMenu.Action> actions=new ArrayList<>();
        actions.add(action("↗","查看来源","返回摘录所在的文档位置",this::showSources));
        actions.add(action("PDF","导出 PDF",NoteEditorPresentation.pdfScope(document),()->beginExport(true)));
        actions.add(action("PNG","导出 PNG",document.infinite()?"导出当前画布内容":"导出当前第 "+(document.currentPage()+1)+" 页",()->beginExport(false)));
        actions.add(action("◉",readOnly?"返回编辑":"只读浏览","手指和手写笔都只移动画布",this::toggleReadOnly));
        actions.add(action("%","缩放与定位",document.infinite()?"最高 1000% · 可回到画布原点":"缩放与页面适配",this::showCanvasNavigation));
        if(hasPageNavigation())actions.add(action("▤",document.infinite()?"画布管理":"页面操作",document.infinite()?"切换、复制、清空或删除独立画布":"插入、复制、清空、删除与调序",()->showNotePageActions(document.currentPage())));
        actions.add(action("⚙","笔记设置","笔盒、套索、橡皮与书写选项",this::settings));
        showActions(anchor,document.infinite()?"草稿操作":"笔记操作",actions);
    }

    private void beginExport(boolean pdf) {
        if (canvas == null || pendingExport != null) return;
        dismissPopup(); canvas.finishGesture();
        NoteDocument snapshot = canvas.snapshot();
        try { if(pdf){for(int i=0;i<snapshot.pageCount();i++)BasicNoteExportBounds.forItems(snapshot,snapshot.pageItems(i));}else BasicNoteExportBounds.forDocument(snapshot); }
        catch (IOException error) { toast(error.getMessage()); return; }
        String token = UUID.randomUUID().toString();
        EXPORT_JOBS.put(token, new ExportJob(noteId, snapshot, pdf)); pendingExport = token;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(pdf ? "application/pdf" : "image/png")
                .putExtra(Intent.EXTRA_TITLE, name + (pdf ? ".pdf" : ".png"));
        try { startActivityForResult(intent, pdf ? EXPORT_PDF : EXPORT_PNG); }
        catch (android.content.ActivityNotFoundException error) { EXPORT_JOBS.remove(token); pendingExport = null; toast("设备没有可用的文件选择器。"); }
    }

    private String paperDescription() {
        String template = "ruled".equals(document.template) ? "横线" : "grid".equals(document.template) ? "网格" : "全空";
        return ("infinite".equals(document.size) ? "无限画布" : document.size) + " · " + template;
    }

    private void makeToolbar() {
        LinearLayout strip = NoteDialogs.row(this); strip.setPadding(dp(6), 0, dp(5), 0);
        final int toolbarHeight = AppColors.isClassic() ? 48 : 60;
        if (!AppColors.isClassic()) strip.setBackground(NoteDialogs.shape(this, AppColors.SURFACE, 17));
        sidebarToggle=icon(hasPageNavigation()?"sidebar":"fit",hasPageNavigation()?"展开页面或独立画布列表":"画布缩放与定位",v->showPages());if(!AppColors.isClassic())sidebarToggle.setVisualLabel(hasPageNavigation()?(document.infinite()?"画布":"页面"):"定位");strip.addView(sidebarToggle,new LinearLayout.LayoutParams(dp(50),dp(toolbarHeight)));
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false); scroll.setFillViewport(false);
        LinearLayout bar = NoteDialogs.row(this);
        String[] kinds = {"hand", "pen", "ballpoint", "pencil", "marker", "eraser", "lasso", "select", "line", "circle", "rectangle", "arrow"};
        String[] labels = {"移动画布", "钢笔", "圆珠笔", "铅笔", "荧光笔", "橡皮", "套索", "选择与调整图片和文本", "直线", "圆形", "矩形", "箭头"};
        for (int i = 0; i < kinds.length; i++) {
            final String kind = kinds[i];
            IconButton button = icon(kind, labels[i], v -> {
                if(kind.equals("lasso")&&kind.equals(tool)){showLassoOptions();return;}
                if (kind.equals(tool) && adjustable(kind)) { toolSettings(kind, v); return; }
                selectTool(kind);
                if (kind.equals("select")) toast("点击图片或文本后拖动，拖动四角调整大小");
            });
            if("lasso".equals(kind))button.setOnLongClickListener(v->{selectTool(kind);showLassoOptions();return true;});
            else if (adjustable(kind)) button.setOnLongClickListener(v -> {selectTool(kind); toolSettings(kind, v); return true;});
            if (!AppColors.isClassic()) button.setVisualLabel("hand".equals(kind) ? "手指" : "select".equals(kind) ? "选择" : labels[i]);
            tools.put(kind, button);
            bar.addView(button, new LinearLayout.LayoutParams(dp(AppColors.isClassic() ? 44 : 50), dp(AppColors.isClassic() ? 44 : toolbarHeight)));
        }
        divider(bar);
        selectionButton=icon("lasso","选区操作：复制、剪切、粘贴、缩放与颜色",v->showSelectionActions());addIcon(bar,selectionButton);
        addIcon(bar,icon("penbox","笔盒与常用笔预设",v->showPenBox()));
        readModeButton=icon("read","只读浏览",v->toggleReadOnly());addIcon(bar,readModeButton);
        undo = icon("undo", "撤销上一步", v -> {if (canvas != null) canvas.undo();});
        redo = icon("redo", "重做上一步", v -> {if (canvas != null) canvas.redo();}); addIcon(bar, undo); addIcon(bar, redo);
        colorButton = new ColorSwatch(this, color, "颜色与调色盘");
        colorButton.setOnClickListener(v -> {if (!colorTool(tool)) selectTool("pen"); toolSettings(tool, v);});
        bar.addView(colorButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        divider(bar);
        imageButton = icon("image", "插入图片", v -> pickImage()); addIcon(bar, imageButton);
        textButton = NoteDialogs.label(this, "文本", 14, AppColors.ACCENT_DARK);textButton.setGravity(Gravity.CENTER);textButton.setContentDescription("插入或编辑文本框");textButton.setOnClickListener(v -> editText());bar.addView(textButton,new LinearLayout.LayoutParams(dp(48),dp(44)));
        trash = icon("trash", "删除选中的对象", v -> {if (canvas != null&&!readOnly) {if(canvas.selectionCount()>0)canvas.deleteSelection();else canvas.deleteSelected();}}); addIcon(bar, trash);
        stylus = icon("stylus", "仅手写笔绘制", v -> {
            stylusOnly = !stylusOnly; common.edit().putBoolean("stylusOnly", stylusOnly).apply(); applyTool();
            toast(stylusOnly ? "仅手写笔绘制；手指移动画布" : "已允许手指绘制；双指移动画布");
        }); addIcon(bar, stylus);
        addIcon(bar, icon("fit", "恢复画布位置与缩放", v -> {if (canvas != null) canvas.fit();}));
        TextView zoomControl=NoteDialogs.label(this,"缩放",12,AppColors.ACCENT_DARK);zoomControl.setGravity(Gravity.CENTER);zoomControl.setContentDescription("调整缩放百分比");zoomControl.setOnClickListener(v -> chooseZoom());bar.addView(zoomControl,new LinearLayout.LayoutParams(dp(48),dp(44)));
        scroll.addView(bar); strip.addView(scroll, new LinearLayout.LayoutParams(0, dp(toolbarHeight), 1));
        divider(strip); headerToggle = icon("down", "收起标题栏", v -> setCollapsed(!headerCollapsed)); addIcon(strip, headerToggle);
        LinearLayout.LayoutParams toolbarSize = new LinearLayout.LayoutParams(-1, dp(toolbarHeight));
        if (!AppColors.isClassic()) toolbarSize.setMargins(dp(10), dp(2), dp(10), dp(10));
        root.addView(strip, toolbarSize);
        if (AppColors.isClassic()) {
            View edge = new View(this); edge.setBackgroundColor(AppColors.BORDER);
            root.addView(edge, new LinearLayout.LayoutParams(-1, dp(1)));
        }
    }

    private void setCollapsed(boolean collapsed) {
        headerCollapsed = collapsed; if (header == null || headerToggle == null) return;
        dismissPopup(); header.setVisibility(collapsed ? View.GONE : View.VISIBLE); headerToggle.setRotation(collapsed ? 0 : 180);
        headerToggle.setContentDescription(collapsed ? "展开笔记标题与设置" : "收起标题栏");
        if(!collapsed)NoteMotion.enter(header);
    }
    private void editText(){
        if(canvas==null||readOnly)return;canvas.finishGesture();NoteDocument.Item selected=canvas.selectedText();
        LinearLayout body=NoteDialogs.column(this);body.setPadding(dp(20),dp(8),dp(20),0);
        EditText content=new EditText(this);content.setHint("输入文字，可换行");content.setMinLines(3);content.setMaxLines(12);content.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);content.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16000)});if(selected!=null)content.setText(selected.text);body.addView(content);
        EditText sizeInput=new EditText(this);sizeInput.setHint("字号 8–72");sizeInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);sizeInput.setText(String.valueOf(selected==null?20:(int)selected.width));body.addView(sizeInput);
        int[] palette={0xff36302b,0xffb75448,0xff236ba0,0xff318263,0xff7a55a3};String[] names={"深色","红色","蓝色","绿色","紫色"};
        android.widget.Spinner colors=new android.widget.Spinner(this);android.widget.ArrayAdapter<String> adapter=new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names);colors.setAdapter(adapter);if(selected!=null){for(int i=0;i<palette.length;i++)if(palette[i]==selected.color)colors.setSelection(i);}body.addView(colors);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle(selected==null?"插入文本框":"编辑文本框").setView(body).setNegativeButton("取消",null).setPositiveButton("保存",null).create();dialog.setOnShowListener(v -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {int size;try{size=Integer.parseInt(sizeInput.getText().toString().trim());}catch(NumberFormatException e){size=20;}size=Math.max(8,Math.min(72,size));if(content.getText().toString().trim().isEmpty()){content.setError("请输入文字");return;}canvas.putText(content.getText().toString(),size,palette[colors.getSelectedItemPosition()]);tool="select";applyTool();dialog.dismiss();}));dialog.show();showDialog(dialog);
    }
    private void chooseZoom() {
        if(canvas==null)return;canvas.finishGesture();
        final int maximum=document.infinite()?1000:300;
        LinearLayout content=NoteDialogs.column(this);content.setPadding(dp(20),dp(8),dp(20),0);
        EditText value=new EditText(this);value.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);value.setSingleLine(true);value.setText(String.valueOf(canvas.zoomPercent()));value.setSelectAllOnFocus(true);
        content.addView(NoteDialogs.label(this,"缩放比例 50–"+maximum+"%",14,AppColors.MUTED));content.addView(value);
        android.app.AlertDialog choice=new android.app.AlertDialog.Builder(this).setTitle("画布缩放").setView(content).setNegativeButton("取消",null).setPositiveButton("应用",null).create();
        choice.setOnShowListener(v->choice.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{int percent;try{percent=Integer.parseInt(value.getText().toString().trim());}catch(NumberFormatException e){value.setError("请输入有效百分比");return;}if(percent<50||percent>maximum){value.setError("范围为50–"+maximum+"%");return;}canvas.setZoomPercent(percent);choice.dismiss();}));
        choice.show();showDialog(choice);
    }

    private void consumeSource(String uri){
        if(sourceConsumed||canvas==null||destroyed||canvas.getWidth()<=0||canvas.getHeight()<=0)return;
        try{
            ReaderSourceLink source=ReaderSourceLink.parse(uri);
            NoteDocument.Item existing=pendingSourceItemId>0?canvas.document().find(pendingSourceItemId):null;
            if(existing==null||existing.type!=NoteDocument.TEXT||!uri.equals(existing.image)){
                pendingSourceItemId=canvas.insertSource(source);
                if(pendingSourceItemId==0){sourceConsumed=true;getIntent().removeExtra(NoteSourceActions.EXTRA_SOURCE);return;}
                getIntent().putExtra("pendingSourceItemId",pendingSourceItemId);
                tool="select";applyTool();
            }
            if(canvas.save()){sourceConsumed=true;getIntent().removeExtra(NoteSourceActions.EXTRA_SOURCE);getIntent().removeExtra("pendingSourceItemId");pendingSourceItemId=0;toast("已插入来源，可在顶部“来源”查看并返回原文");}
            else toast("来源已留在当前画布，尚未保存；请检查存储空间后重试保存");
        }catch(IllegalArgumentException bad){sourceConsumed=true;getIntent().removeExtra(NoteSourceActions.EXTRA_SOURCE);getIntent().removeExtra("pendingSourceItemId");pendingSourceItemId=0;toast("来源链接无效，未插入笔记");}
    }
    private void showSources(){
        if(canvas==null)return;canvas.finishGesture();
        ArrayList<ReaderSourceLink> links=new ArrayList<>();
        for(int page=0;page<document.pageCount();page++)for(NoteDocument.Item item:document.pageItems(page))if(item.type==NoteDocument.TEXT&&!item.image.isEmpty())try{links.add(ReaderSourceLink.parse(item.image));}catch(IllegalArgumentException ignored){}
        if(links.isEmpty()){showDialog(ReaderDialogs.message(this,"笔记来源","这份笔记还没有来源。阅读文档时可将当前页或摘录插入笔记。",true));return;}
        String[] labels=new String[links.size()];for(int i=0;i<labels.length;i++)labels[i]=NoteSourceActions.label(links.get(i));
        Dialog choices=new android.app.AlertDialog.Builder(this).setTitle("笔记来源 · 仅本机有效").setItems(labels,(d,index)->openSource(links.get(index))).setNegativeButton("关闭",null).create();choices.show();showDialog(choices);
    }
    private void openSource(ReaderSourceLink source){
        try{if(canvas!=null&&!canvas.save())return;startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(source.toUri()),this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();}
        catch(RuntimeException failure){showDialog(ReaderDialogs.message(this,"无法打开来源","来源文档可能已移走或删除，请返回资料库检查。",true));}
    }
    private void settings() {
        if (canvas == null) return;
        dismissPopup(); canvas.finishGesture();
        ArrayList<NoteActionMenu.Action> actions=new ArrayList<>();
        actions.add(action("✎","常用笔盒","收藏笔种、颜色和粗细，最多 8 支",this::showPenBox));
        actions.add(action("○","套索设置","自由 / 矩形与四类对象筛选",readOnly?null:this::showLassoOptions));
        actions.add(action("⌫","橡皮设置",wholeStrokeEraser?"当前：整笔擦除":"当前：局部擦除",readOnly?null:()->{selectTool("eraser");toolSettings("eraser",tools.get("eraser"));}));
        actions.add(action("✎","笔迹与操作","手写笔绘制、双指撤销等",this::toolOptions));
        if(!document.infinite()){
            actions.add(action("▤","页面操作","插入、复制、清空、删除与调序",()->showNotePageActions(document.currentPage())));
            actions.add(action("↕","页面浏览",horizontalPages?"当前：左右翻页":"当前：纵向连续",()->{horizontalPages=!horizontalPages;preferences.edit().putBoolean("horizontalPages",horizontalPages).apply();canvas.setHorizontalPages(horizontalPages);toast("已切换为"+(horizontalPages?"左右翻页":"纵向连续")+"浏览");}));
        }else {if(hasPageNavigation())actions.add(action("▤","独立画布管理","这些历史画布彼此独立，不连续拼接",()->showNotePageActions(document.currentPage())));actions.add(action("⌫","清空画布","删除当前画布的全部对象，可撤销",readOnly?null:()->confirmPageChange(document.currentPage(),false)));}
        showActions(root,"笔记设置",actions);
    }

    private NoteActionMenu.Action action(String symbol,String title,String detail,Runnable run){return new NoteActionMenu.Action(symbol,title,detail,run);}
    private void showActions(View anchor,String title,ArrayList<NoteActionMenu.Action> actions){dismissPopup();showDialog(NoteActionMenu.showActions(this,anchor,title,actions.toArray(new NoteActionMenu.Action[0])));}
    private void showActions(String title,NoteActionMenu.Action... actions){dismissPopup();showDialog(NoteActionMenu.showActions(this,root,title,actions));}
    private void toggleReadOnly(){if(canvas==null)return;canvas.finishGesture();readOnly=!readOnly;applyTool();toast(readOnly?"只读浏览：手指和手写笔都不绘制":"已返回编辑");}
    private boolean hasPageNavigation(){return NoteEditorPresentation.hasPageNavigation(document);}
    private String pagePosition(){return NoteEditorPresentation.pagePosition(document);}
    private void showCanvasNavigation(){
        if(canvas==null)return;
        ArrayList<NoteActionMenu.Action> actions=new ArrayList<>();
        actions.add(action("%","缩放比例","当前 "+canvas.zoomPercent()+"%"+(document.infinite()?" · 最高 1000%":" · 最高 300%"),this::chooseZoom));
        actions.add(action("□","适配视图",document.infinite()?"恢复画布默认位置与缩放":"让当前纸张适合窗口",()->canvas.fit()));
        if(document.infinite())actions.add(action("◎","回到原点","保留缩放比例，返回当前画布中心",()->canvas.resetOrigin()));
        if(document.infinite()&&hasPageNavigation())actions.add(action("▤","切换独立画布",pagePosition(),this::showPages));
        actions.add(action("◉",readOnly?"返回编辑":"只读浏览","手写笔和手指都只移动画布",this::toggleReadOnly));
        showActions(root,document.infinite()?"无限画布":"页面视图",actions);
    }
    private void showLassoOptions(){
        if(canvas==null||readOnly)return;
        showActions("套索",
                action("○","自由套索",lassoFreeform?"当前使用 · 沿轮廓圈选":"按任意形状圈选",()->setLassoMode(true)),
                action("□","矩形套索",!lassoFreeform?"当前使用 · 框选区域":"拖出矩形圈选",()->setLassoMode(false)),
                action("≡","选择哪些对象","笔迹、图形、文本、图片",this::showLassoFilters),
                action("⋯","选区操作","复制、剪切、粘贴、缩放、改色和层级",this::showSelectionActions));
    }
    private void setLassoMode(boolean freeform){lassoFreeform=freeform;preferences.edit().putBoolean("lassoFreeform",freeform).apply();selectTool("lasso");toast(freeform?"沿轮廓圈选对象，拖动选区可移动":"拖出矩形圈选对象，拖动选区可移动");}
    private void showLassoFilters(){
        final int[] flags={NoteCanvasView.LASSO_HANDWRITING,NoteCanvasView.LASSO_GRAPHICS,NoteCanvasView.LASSO_TEXT,NoteCanvasView.LASSO_IMAGE};
        final boolean[] selected=new boolean[4];for(int i=0;i<4;i++)selected[i]=(lassoFilter&flags[i])!=0;
        Dialog choice=new android.app.AlertDialog.Builder(this).setTitle("套索对象类型").setMultiChoiceItems(new String[]{"笔迹（含荧光笔）","图形","文本框","图片"},selected,(d,index,value)->selected[index]=value)
                .setNegativeButton("取消",null).setPositiveButton("应用",(d,which)->{int mask=0;for(int i=0;i<4;i++)if(selected[i])mask|=flags[i];if(mask==0){toast("至少选择一种对象类型");return;}lassoFilter=mask;preferences.edit().putInt("lassoFilter",mask).apply();canvas.setLassoFilter(mask);}).create();choice.show();showDialog(choice);
    }
    private int selectedObjectCount(){return canvas==null?0:canvas.selectionCount()>0?canvas.selectionCount():canvas.hasSelection()?1:0;}
    private void selectionAction(Runnable run){if(canvas==null||readOnly)return;canvas.finishGesture();run.run();updateTools();}
    private void showSelectionActions(){
        if(canvas==null)return;canvas.finishGesture();final boolean selected=!readOnly&&canvas.hasSelection();
        showActions("选区 · "+selectedObjectCount()+" 项",
                action("○","圈选对象","使用套索圈选多个对象",readOnly?null:()->selectTool("lasso")),
                action("＋","复制","保留原对象，放入当前笔记剪贴板",selected?()->selectionAction(()->canvas.copySelection()):null),
                action("✂","剪切","移除选区，可撤销",selected?()->selectionAction(()->canvas.cutSelection()):null),
                action("▣","粘贴",canvas.hasClipboard()?"粘贴到当前画布或页面":"当前笔记剪贴板为空",!readOnly&&canvas.hasClipboard()?()->selectionAction(()->{canvas.pasteSelection();tool="lasso";}):null),
                action("%","缩小至 80%","以选区中心缩放",selected?()->selectionAction(()->canvas.scaleSelection(.8f)):null),
                action("%","放大至 125%","以选区中心缩放",selected?()->selectionAction(()->canvas.scaleSelection(1.25f)):null),
                action("●","修改颜色","修改可着色的笔迹、图形和文字",selected?this::showSelectionColors:null),
                action("↑","置顶","放到其他对象前面",selected?()->selectionAction(()->canvas.bringSelectionForward()):null),
                action("↓","置底","放到其他对象后面",selected?()->selectionAction(()->canvas.sendSelectionBackward()):null),
                action("⌫","删除选区","可撤销",selected?()->selectionAction(()->canvas.deleteSelection()):null));
    }
    private void showSelectionColors(){
        int[] colors={color,0xff36302b,0xff2464d6,0xffe53935,0xff16805b,0xfff6c343,0xff8e44ad};
        ArrayList<NoteActionMenu.Action> choices=new ArrayList<>();for(int i=0;i<colors.length;i++){final int ink=colors[i];choices.add(action("●",(i==0?"当前颜色 · ":"")+hex(ink),"应用于可着色对象",()->selectionAction(()->canvas.recolorSelection(ink))));}showActions(root,"选区颜色",choices);
    }
    private void showPenBox(){
        ArrayList<NotePenPresets.Preset> presets=NotePenPresets.read(preferences.getString("penBox",null));ArrayList<NoteActionMenu.Action> actions=new ArrayList<>();
        for(NotePenPresets.Preset preset:presets)actions.add(action("✎",preset.title(),"点击使用此笔",readOnly?null:()->usePreset(preset)));
        actions.add(action("+","收藏当前笔",NotePenPresets.supported(tool)?NotePenPresets.name(tool)+" · "+hex(color)+" · "+String.format(Locale.CHINA,"%.1f pt",size(tool)):"请先选择钢笔、圆珠笔、铅笔或荧光笔",!readOnly&&NotePenPresets.supported(tool)?this::saveCurrentPreset:null));
        actions.add(action("−","整理笔盒","移除不常用的笔；最多收藏 8 支",presets.isEmpty()?null:this::managePenBox));
        showActions(root,"常用笔盒 · "+presets.size()+"/8",actions);
    }
    private void usePreset(NotePenPresets.Preset preset){
        if(readOnly)return;tool=preset.tool;color=preset.color;if("pen".equals(tool))penSize=preset.width;else if("marker".equals(tool))markerSize=preset.width;else preferences.edit().putFloat(tool+"Size",preset.width).apply();applyTool();toast("已使用 "+preset.title());
    }
    private void saveCurrentPreset(){
        ArrayList<NotePenPresets.Preset> presets=NotePenPresets.read(preferences.getString("penBox",null));
        NotePenPresets.Preset preset=new NotePenPresets.Preset(tool,color,size(tool));if(!NotePenPresets.valid(preset))return;
        if(NotePenPresets.contains(presets,preset)){toast("这支笔已经在笔盒中");return;}if(presets.size()>=NotePenPresets.LIMIT){toast("笔盒已满，请先移除一支笔");return;}
        presets.add(preset);preferences.edit().putString("penBox",NotePenPresets.write(presets)).apply();toast("已收藏当前笔");
    }
    private void managePenBox(){
        ArrayList<NotePenPresets.Preset> presets=NotePenPresets.read(preferences.getString("penBox",null));ArrayList<NoteActionMenu.Action> choices=new ArrayList<>();
        for(int i=0;i<presets.size();i++){final int index=i;choices.add(action("−","移除 "+presets.get(i).title(),"只从笔盒移除，不改笔记内容",()->{presets.remove(index);preferences.edit().putString("penBox",NotePenPresets.write(presets)).apply();toast("已从笔盒移除");}));}showActions(root,"整理笔盒",choices);
    }
    private void showNotePageActions(int page){
        if(canvas==null||!hasPageNavigation())return;final int count=document.pageCount();if(page<0||page>=count)return;
        showActions(document.infinite()?"独立画布 "+(page+1):"第 "+(page+1)+" 页",
                action("+",document.infinite()?"切换画布":"插入空白页",document.infinite()?"这些画布彼此独立，不连续拼接":"在本页之前或之后插入",document.infinite()?this::showPages:readOnly?null:()->showNoteInsertionOptions(page)),
                action("▣",document.infinite()?"复制本画布":"复制本页","创建独立副本并保留原内容",readOnly?null:()->runPageOperation(page,count,()->canvas.duplicatePage())),
                action("↑",document.infinite()?"上移画布":"上移一页","调整列表顺序",!readOnly&&page>0?()->runPageOperation(page,count,()->canvas.movePage(page,page-1)):null),
                action("↓",document.infinite()?"下移画布":"下移一页","调整列表顺序",!readOnly&&page+1<count?()->runPageOperation(page,count,()->canvas.movePage(page,page+1)):null),
                action("⌫",document.infinite()?"清空本画布":"清空本页","移除当前全部对象，可撤销",readOnly?null:()->confirmPageChange(page,false)),
                action("−",document.infinite()?"删除本画布":"删除本页",count>1?"移除当前画布或纸张及内容":"至少保留一张画布或纸张",!readOnly&&count>1?()->confirmPageChange(page,true):null));
    }
    private void runPageOperation(int page,int count,Runnable operation){
        if(canvas==null||destroyed||readOnly)return;if(document.pageCount()!=count||page<0||page>=count){toast("页面已变化，请重新选择");return;}
        canvas.finishGesture();canvas.selectPage(page);operation.run();refreshPageSidebars(true);updateTools();
    }
    private void confirmPageChange(int page,boolean delete){
        if(canvas==null||readOnly)return;final int count=document.pageCount();if(page<0||page>=count||delete&&count<=1)return;
        final int objects=document.pageItems(page).size();String subject=document.infinite()?"第 "+(page+1)+" 张无限画布":"第 "+(page+1)+" 页";
        Dialog confirm=new android.app.AlertDialog.Builder(this).setTitle((delete?"删除":"清空")+(document.infinite()?"画布":"页面"))
                .setMessage((delete?"将删除":"将清空")+subject+"的 "+objects+" 个对象。"+(delete?"此删除不能撤销；其他页面或独立画布会保留。":"纸张会保留，可用撤销恢复。"))
                .setNegativeButton("取消",null).setPositiveButton(delete?"删除":"清空",(d,which)->runPageOperation(page,count,()->{if(delete)canvas.deletePage();else canvas.clearPage();})).create();confirm.show();showDialog(confirm);
    }


    private void toolOptions(){showDialog(NoteDialogs.settings(this, stylusOnly, twoFingerUndo, (pen, undoEnabled) -> {
            stylusOnly = pen; twoFingerUndo = undoEnabled;
            common.edit().putBoolean("stylusOnly", pen).putBoolean("twoFingerUndo", undoEnabled).apply(); applyTool();
        }, () -> {if (canvas != null) canvas.fit();}));
    }
    private NotePageSidebar createPageSidebar(boolean modal){
        NotePageSidebar sidebar=new NotePageSidebar(this,document,noteId,new NotePageSidebar.Actions(){
            public void select(int page){if(canvas==null)return;canvas.selectPage(page);refreshPageSidebars(true);if(modal&&dialog!=null)dialog.dismiss();}
            public void move(int from,int to){if(canvas!=null&&!readOnly){canvas.movePage(from,to);refreshPageSidebars(true);}}
            public void insert(int page){showNoteInsertionOptions(page);}
            public void manage(int page){showNotePageActions(page);}
            public void append(){if(canvas!=null&&!readOnly){canvas.finishGesture();canvas.insertPage(document.pageCount());refreshPageSidebars(true);}}
            public void close(){if(modal){if(dialog!=null)dialog.dismiss();}else{pagesExpanded=false;preferences.edit().putBoolean("pagesExpanded",false).apply();updatePageSidebar();}}
        });sidebar.setEditingEnabled(!readOnly);return sidebar;
    }
    private void showNoteInsertionOptions(int page){
        if(canvas==null||destroyed||readOnly||document.infinite())return;
        dismissPopup();canvas.finishGesture();final NoteCanvasView target=canvas;final int count=document.pageCount();
        if(page<0||page>=count)return;
        showDialog(PageInsertionDialog.show(this,page,count,index->{
            if(destroyed||canvas!=target||document.pageCount()!=count){toast("页面已变化，请重新选择");return;}
            target.insertPage(index);refreshPageSidebars(true);
        }));
    }
    private void refreshPageSidebars(boolean force){if(force)updatePageSidebar();if(pageSidebar!=null)pageSidebar.refresh(force);if(dialogSidebar!=null)dialogSidebar.refresh(force);}
    private void refreshCurrentPage(){
        if(pagesButton!=null)pagesButton.setText(pagePosition());
        if(pageSidebar!=null)pageSidebar.refreshSelection();
        if(dialogSidebar!=null)dialogSidebar.refreshSelection();
    }
    private void schedulePageSync(){pageSyncHandler.removeCallbacks(pendingPageSync);pageSyncHandler.postDelayed(pendingPageSync,450);}
    private void updatePageSidebar(){
        if(pageSidebar==null||readingBody==null)return;int width=readingBody.getWidth()>0?Math.round(readingBody.getWidth()/getResources().getDisplayMetrics().density):getResources().getConfiguration().screenWidthDp;
        int rail=ReaderSidebarLayout.width(width);boolean visible=hasPageNavigation()&&pagesExpanded&&rail>0;pageSidebar.setVisibility(visible?View.VISIBLE:View.GONE);
        if(sidebarToggle!=null){sidebarToggle.setActive(visible);sidebarToggle.setContentDescription(!hasPageNavigation()?"画布缩放与定位":visible?"收起页面或画布列表":"展开页面或画布列表");if(!AppColors.isClassic())sidebarToggle.setVisualLabel(hasPageNavigation()?(document.infinite()?"画布":"页面"):"定位");}
        if(visible&&pageSidebar.getLayoutParams().width!=dp(rail)){pageSidebar.getLayoutParams().width=dp(rail);pageSidebar.requestLayout();pageSidebar.refresh(true);}
    }
    private void showPages(){
        if(canvas==null)return;if(!hasPageNavigation()){showCanvasNavigation();return;}dismissPopup();canvas.finishGesture();
        int width=readingBody.getWidth()>0?Math.round(readingBody.getWidth()/getResources().getDisplayMetrics().density):getResources().getConfiguration().screenWidthDp;
        if(ReaderSidebarLayout.canDock(width)){pagesExpanded=!pagesExpanded;preferences.edit().putBoolean("pagesExpanded",pagesExpanded).apply();updatePageSidebar();refreshPageSidebars(true);if(pagesExpanded)NoteMotion.enter(pageSidebar);return;}
        if(dialogSidebar!=null&&dialog!=null&&dialog.isShowing())return;
        Dialog pages=new Dialog(this);pages.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);dialogSidebar=createPageSidebar(true);pages.setContentView(dialogSidebar);pages.show();showDialog(pages);
        Rect frame=new Rect();root.getWindowVisibleDisplayFrame(frame);if(pages.getWindow()!=null)pages.getWindow().setLayout(Math.max(1,Math.min(dp(320),frame.width()-dp(24))),Math.max(1,frame.height()-dp(48)));
        pages.setOnDismissListener(v->{if(dialog==pages)dialog=null;dialogSidebar=null;});
    }
    private void syncLibraryPages(){if(document==null||noteId==null)return;final int count=document.pageCount(),page=document.currentPage();try{LibraryStore.mutate(getApplicationContext(),items->{for(LibraryItem item:items)if(noteId.equals(item.id)&&item.isNote()&&!item.isTrashed()){item.pages=count;item.page=page;break;}});}catch(IOException|RuntimeException failure){if(resumed)toast("页面已保存，但资料库页数暂未更新");}}
    private boolean adjustable(String kind) { return !"hand".equals(kind) && !"select".equals(kind) && !"lasso".equals(kind); }
    private boolean colorTool(String kind) { return adjustable(kind) && !"eraser".equals(kind); }
    private float size(String kind) { return "marker".equals(kind) ? markerSize : "eraser".equals(kind) ? eraserSize : "pen".equals(kind) ? penSize : clamp(preferences.getFloat(kind + "Size", "ballpoint".equals(kind) ? 1.3f : "pencil".equals(kind) ? 2.5f : 2f), .5f, 20f); }
    private void selectTool(String kind) {
        dismissPopup();
        readOnly=false;tool = kind;
        if (colorTool(kind)) color = ToolPreferences.color(preferences, kind, "color", common.getInt("inkColor", ToolPreferences.BLACK));
        applyTool();
    }
    private void applyTool() {
        if (!tools.containsKey(tool)) tool = "pen";
        if (canvas != null) {canvas.setReadOnly(readOnly);canvas.setTool(readOnly?"hand":tool, color, size(tool), stylusOnly); canvas.setTwoFingerUndoEnabled(!readOnly&&twoFingerUndo); canvas.setWholeStrokeEraser(wholeStrokeEraser);canvas.setLassoFreeform(lassoFreeform);canvas.setLassoFilter(lassoFilter);}
        if (colorTool(tool)) ToolPreferences.saveColor(preferences, tool, color);
        preferences.edit().putFloat("pen", penSize).putFloat("marker", markerSize).putFloat("eraser", eraserSize).apply();
        updateTools();
    }
    private void updateTools() {
        for (Map.Entry<String, IconButton> entry : tools.entrySet()) {entry.getValue().setActive(!readOnly&&entry.getKey().equals(tool));entry.getValue().setEnabled(!readOnly);}
        if(readModeButton!=null){readModeButton.setActive(readOnly);readModeButton.setContentDescription(readOnly?"只读浏览中，点击返回编辑":"进入只读浏览，手写笔也不绘制");}
        if(selectionButton!=null&&canvas!=null){selectionButton.setActive(canvas.hasSelection());selectionButton.setContentDescription("选区操作，已选 "+selectedObjectCount()+" 项");}
        if(colorButton!=null)colorButton.setEnabled(!readOnly);if(textButton!=null)textButton.setEnabled(!readOnly);
        if (colorButton != null) colorButton.setColor(color);
        if (stylus != null) {stylus.setEnabled(!readOnly);stylus.setActive(stylusOnly); stylus.setContentDescription("仅手写笔绘制，" + (stylusOnly ? "已开启" : "已关闭"));}
        if (canvas != null) {undo.setEnabled(!readOnly&&canvas.canUndo()); redo.setEnabled(!readOnly&&canvas.canRedo()); trash.setEnabled(!readOnly&&(canvas.hasSelection()||canvas.selectionCount()>0));}
        if(pageSidebar!=null)pageSidebar.setEditingEnabled(!readOnly);if(dialogSidebar!=null)dialogSidebar.setEditingEnabled(!readOnly);
        if(canvas!=null&&pagesButton!=null)pagesButton.setText(pagePosition());
        refreshPageSidebars(false);
        if (imageButton != null) {imageButton.setEnabled(!readOnly&&!IMAGE_JOBS.containsKey(noteId)); imageButton.setContentDescription(IMAGE_JOBS.containsKey(noteId) ? "正在导入图片" : "插入图片");}
    }

    private void toolSettings(String kind, View anchor) {
        if (canvas == null || readOnly) return;
        dismissPopup(); canvas.finishGesture();
        final boolean erasing = kind.equals("eraser");
        LinearLayout shell = NoteDialogs.column(this); shell.setPadding(dp(7), dp(5), dp(7), dp(8)); shell.setFocusableInTouchMode(true);
        LinearLayout heading = NoteDialogs.row(this); heading.setPadding(dp(6), 0, 0, 0);
        IconButton decoration = icon(kind, "", null); decoration.setTint(AppColors.ACCENT_DARK); decoration.setBackground(NoteDialogs.shape(this, AppColors.SOFT, 12)); decoration.setOnLongClickListener(null); decoration.setClickable(false); decoration.setFocusable(false); addIcon(heading, decoration);
        TextView title = NoteDialogs.label(this, erasing ? "橡皮" : kind.equals("marker") ? "荧光笔" : kind.equals("ballpoint") ? "圆珠笔" : kind.equals("pencil") ? "铅笔" : kind.equals("pen") ? "钢笔" : "笔迹与图形", 17, AppColors.INK); title.setTypeface(null, 1); title.setPadding(dp(10), 0, 0, 0); heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); addIcon(heading, icon("close", "关闭工具设置", v -> dismissPopup())); shell.addView(heading);
        ScrollView scrolling = new ScrollView(this); scrolling.setVerticalScrollBarEnabled(false);
        LinearLayout panel = NoteDialogs.column(this); panel.setPadding(dp(12), dp(12), dp(12), dp(8)); scrolling.addView(panel); shell.addView(scrolling, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView widthLabel = NoteDialogs.label(this, "", 12, AppColors.MUTED); panel.addView(widthLabel); NoteDialogs.gap(panel, 8);
        SizePreviewView preview = new SizePreviewView(this); panel.addView(preview, new LinearLayout.LayoutParams(-1, dp(88)));
        Runnable refresh = () -> {
            widthLabel.setText((erasing ? "擦除范围" : "线条粗细") + String.format(Locale.CHINA, "  ·  %.1f pt", size(kind)));
            preview.setPreview(kind.equals("marker") ? (color & 0xffffff) | 0x55000000 : color, canvas.previewDiameter(size(kind)), erasing);
        };
        float minimum = erasing ? 8f : kind.equals("marker") ? 4f : .5f;
        float maximum = erasing ? 100f : kind.equals("marker") ? 60f : 20f;
        SeekBar seek = new SeekBar(this); seek.setMax(100); seek.setProgress(Math.round(100 * (float)(Math.log(size(kind) / minimum) / Math.log(maximum / minimum)))); seek.setContentDescription(erasing ? "橡皮范围大小" : "笔画粗细");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar view) { }
            @Override public void onStopTrackingTouch(SeekBar view) { }
            @Override public void onProgressChanged(SeekBar view, int progress, boolean user) {
                if (!user) return;
                float width = (float)(minimum * Math.pow(maximum / minimum, progress / 100f));
                if (erasing) eraserSize = width; else if (kind.equals("marker")) markerSize = width; else if (kind.equals("pen")) penSize = width; else preferences.edit().putFloat(kind + "Size", width).apply();
                refresh.run(); applyTool();
            }
        }); panel.addView(seek, new LinearLayout.LayoutParams(-1, dp(44)));
        EditText hex = new EditText(this); hex.setTextColor(AppColors.INK); hex.setTextSize(14); hex.setSingleLine(true); hex.setSelectAllOnFocus(true); hex.setText(hex(color)); hex.setHint("#RRGGBB"); hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)}); hex.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        if (erasing) {
            NoteDialogs.gap(panel, 8);
            android.widget.RadioGroup modes=new android.widget.RadioGroup(this);
            android.widget.RadioButton partial=new android.widget.RadioButton(this);partial.setId(View.generateViewId());partial.setText("局部擦除 · 保留轨迹的其余部分");modes.addView(partial);
            android.widget.RadioButton whole=new android.widget.RadioButton(this);whole.setId(View.generateViewId());whole.setText("整笔擦除 · 移除碰到的整条笔迹");modes.addView(whole);
            modes.check(wholeStrokeEraser?whole.getId():partial.getId());
            modes.setOnCheckedChangeListener((group,id)->{wholeStrokeEraser=id==whole.getId();preferences.edit().putBoolean("wholeStrokeEraser",wholeStrokeEraser).apply();canvas.setWholeStrokeEraser(wholeStrokeEraser);});
            panel.addView(modes);
            panel.addView(NoteDialogs.label(this, "图片和文本请用套索或选择工具删除。", 12, AppColors.MUTED));
        } else {
            NoteDialogs.gap(panel, 12); panel.addView(NoteDialogs.label(this, "常用颜色", 12, AppColors.MUTED));
            int[] colors = {0xff000000, 0xff666666, 0xffaaaaaa, 0xffffffff, 0xffe53935, 0xffff7043, 0xfff6c343, 0xff81c784, 0xff16805b, 0xff00acc1, 0xff2464d6, 0xff3949ab, 0xff8e44ad, 0xffd94f9b, 0xff8d6e63, 0xffffcdd2, 0xffc8e6c9, 0xffbbdefb};
            ArrayList<ColorSwatch> swatches = new ArrayList<>();
            ColorPickerView picker = new ColorPickerView(this); picker.setColor(color);
            final boolean[] syncing = {false};
            ColorPickerView.Listener choose = chosen -> {
                color = chosen | 0xff000000;
                syncing[0] = true; hex.setText(hex(color)); hex.setError(null); picker.setColor(color); syncing[0] = false;
                for (int i = 0; i < colors.length; i++) swatches.get(i).setActive(colors[i] == color);
                refresh.run(); applyTool();
            };
            for (int r = 0; r < 3; r++) {
                LinearLayout row = NoteDialogs.row(this);
                for (int col = 0; col < 6; col++) {
                    final int value = colors[r * 6 + col];
                    ColorSwatch swatch = new ColorSwatch(this, value, hex(value)); swatch.setActive(value == color); swatch.setOnClickListener(v -> choose.changed(value)); swatches.add(swatch); row.addView(swatch, new LinearLayout.LayoutParams(0, dp(42), 1));
                }
                panel.addView(row);
            }
            NoteDialogs.gap(panel, 12); panel.addView(NoteDialogs.label(this, "自由调色", 12, AppColors.MUTED)); panel.addView(picker, new LinearLayout.LayoutParams(-1, dp(160)));
            picker.setListener(choose);
            LinearLayout codeRow = NoteDialogs.row(this); codeRow.addView(NoteDialogs.label(this, "HEX", 11, AppColors.MUTED)); LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(0, dp(46), 1); codeParams.leftMargin = dp(12); codeRow.addView(hex, codeParams); panel.addView(codeRow);
            hex.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void afterTextChanged(Editable value) { }
                @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                    if (syncing[0]) return;
                    String text = value.toString();
                    if (text.matches("#?[0-9a-fA-F]{6}")) {
                        color = Color.parseColor(text.startsWith("#") ? text : "#" + text); picker.setColor(color); hex.setError(null);
                        for (int i = 0; i < colors.length; i++) swatches.get(i).setActive(colors[i] == color);
                        refresh.run(); applyTool();
                    } else if (text.length() >= 6) hex.setError("请输入 6 位十六进制色号");
                }
            });
        }
        refresh.run();
        PopupWindow window = new PopupWindow(shell, dp(356), dp(erasing ? 420 : 610), true); window.setBackgroundDrawable(NoteDialogs.shape(this, AppColors.SURFACE, 20)); window.setElevation(dp(12)); window.setOutsideTouchable(true); window.setClippingEnabled(true); window.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED); window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); popup = window;
        Runnable position = () -> {
            if (popup != window || !anchor.isAttachedToWindow()) return;
            Rect visible = new Rect(); (shell.isAttachedToWindow() ? shell : root).getWindowVisibleDisplayFrame(visible);
            View decor = root.getRootView(); int[] decorPosition = new int[2]; decor.getLocationOnScreen(decorPosition); Rect bounded = new Rect(visible);
            if (decor.getWidth() > 0 && decor.getHeight() > 0 && bounded.intersect(decorPosition[0], decorPosition[1], decorPosition[0] + decor.getWidth(), decorPosition[1] + decor.getHeight())) visible.set(bounded);
            if (visible.width() <= 0 || visible.height() <= 0) return;
            int[] at = new int[2], screen = new int[2], inWindow = new int[2]; anchor.getLocationOnScreen(at); root.getLocationOnScreen(screen); root.getLocationInWindow(inWindow);
            int[] box = PopupPlacement.place(visible.left, visible.top, visible.right, visible.bottom, at[0], at[1], at[0] + anchor.getWidth(), at[1] + anchor.getHeight(), dp(356), dp(erasing ? 420 : 610), dp(10), dp(7), dp(180));
            int x = box[0] - screen[0] + inWindow[0], y = box[1] - screen[1] + inWindow[1];
            if (window.isShowing()) window.update(x, y, box[2], box[3]); else {window.setWidth(box[2]); window.setHeight(box[3]); window.showAtLocation(root, Gravity.TOP | Gravity.LEFT, x, y);}
        };
        ViewTreeObserver.OnGlobalLayoutListener listener = position::run; root.getViewTreeObserver().addOnGlobalLayoutListener(listener); shell.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        window.setOnDismissListener(() -> {
            if (root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
            if (shell.getViewTreeObserver().isAlive()) shell.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
            if (popup == window) popup = null;
            android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null && hex.hasFocus()) keyboard.hideSoftInputFromWindow(hex.getWindowToken(), 0);
        });
        hex.setOnEditorActionListener((view, action, event) -> {if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE); if (keyboard != null) keyboard.hideSoftInputFromWindow(hex.getWindowToken(), 0); shell.requestFocus(); return true;} return false;});
        shell.requestFocus(); position.run();NoteMotion.enter(shell);
    }

    private void pickImage() {
        if (canvas == null || readOnly || IMAGE_JOBS.containsKey(noteId)) return;
        dismissPopup(); canvas.finishGesture(); canvas.save();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {startActivityForResult(intent, PICK_IMAGE);} catch (android.content.ActivityNotFoundException exception) {showDialog(ReaderDialogs.message(this, "无法选择图片", "设备没有可用的文件选择器。", true));}
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EXPORT_PDF || request == EXPORT_PNG) {
            String token = pendingExport; pendingExport = null;
            ExportJob job = token == null ? null : EXPORT_JOBS.remove(token);
            if (result != RESULT_OK) return;
            if (job == null || !job.noteId.equals(noteId) || job.pdf != (request == EXPORT_PDF)) { toast("导出已中断，请重新选择格式和位置。"); return; }
            if (data == null || data.getData() == null) { toast("没有选择导出位置。"); return; }
            Uri destination = data.getData(); Context app = getApplicationContext();
            toast("正在导出笔记…");
            EXPORTS.execute(() -> {
                String message;
                try { BasicNoteExport.write(app, job.noteId, job.snapshot, destination, job.pdf); message = "笔记已导出"; }
                catch (IOException | RuntimeException | OutOfMemoryError error) { message = "导出失败：" + (error.getMessage() == null ? "请检查图片或存储空间" : error.getMessage()); }
                final String resultMessage = message;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> Toast.makeText(app, resultMessage, Toast.LENGTH_LONG).show());
            });
            return;
        }
        if (request != PICK_IMAGE || result != RESULT_OK || data == null || data.getData() == null || canvas == null) return;
        final Uri uri = data.getData(); final Context app = getApplicationContext();
        ImageJob job = new ImageJob(noteId); job.owner = new WeakReference<>(this);
        if (IMAGE_JOBS.putIfAbsent(noteId, job) != null) return;
        updateTools(); toast("正在插入图片…");
        IMAGES.execute(() -> {
            try {if (!job.cancelled) prepareImage(app, uri, job);} catch (OutOfMemoryError memory) {job.error = "图片太大，内存不足。请尝试尺寸较小的图片。";} catch (Exception failure) {job.error = failure.getMessage() == null ? "无法读取图片，请换一张图片重试。" : failure.getMessage();}
            job.ready = true;
            if (job.cancelled) {discardImage(app, job); return;}
            NoteActivity target = job.owner.get(); if (target != null) target.runOnUiThread(target::consumeImage);
        });
    }
    private void consumeImage() {
        if (!resumed || destroyed || canvas == null) return;
        ImageJob job = IMAGE_JOBS.get(noteId); if (job == null) {updateTools(); return;}
        job.owner = new WeakReference<>(this); if (!job.ready) {updateTools(); return;}
        if (canvas.getWidth() <= 0 || canvas.getHeight() <= 0) return;
        if (!IMAGE_JOBS.remove(noteId, job)) return;
        updateTools();
        if (job.error != null) {showDialog(ReaderDialogs.message(this, "图片未能插入", job.error, true)); return;}
        if (canvas.insertImage(job.filename, job.width, job.height)) {
            tool = "select"; applyTool(); toast("图片已插入：拖动移动，拖动四角调整大小");
        } else discardImage(getApplicationContext(), job);
    }
    private static void prepareImage(Context context, Uri uri, ImageJob job) throws IOException {
        File input = File.createTempFile("note-image-", ".tmp", context.getCacheDir());
        Bitmap bitmap = null; File output = null;
        try {
            try (InputStream source = context.getContentResolver().openInputStream(uri); FileOutputStream destination = new FileOutputStream(input)) {
                if (source == null) throw new IOException("无法打开所选图片。");
                byte[] buffer = new byte[32768]; int read; long total = 0;
                while ((read = source.read(buffer)) != -1) {if (job.cancelled) throw new IOException("图片导入已取消。"); total += read; if (total > 32L * 1024 * 1024) throw new IOException("单张图片请控制在 32 MB 以内。"); destination.write(buffer, 0, read);}
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeFile(input.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("无法识别这张图片，请使用 JPG、PNG 或设备支持的图片格式。");
            int sample = 1; while ((long)bounds.outWidth / sample > 2048 || (long)bounds.outHeight / sample > 2048) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bitmap = BitmapFactory.decodeFile(input.getAbsolutePath(), options);
            if (bitmap == null) throw new IOException("图片解码失败，请使用另一张图片。");
            Matrix rotation = new Matrix();
            try {
                ExifInterface exif = new ExifInterface(input.getAbsolutePath());
                int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                switch (orientation) {
                    case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: rotation.setScale(-1, 1); break;
                    case ExifInterface.ORIENTATION_ROTATE_180: rotation.setRotate(180); break;
                    case ExifInterface.ORIENTATION_FLIP_VERTICAL: rotation.setScale(1, -1); break;
                    case ExifInterface.ORIENTATION_TRANSPOSE: rotation.setRotate(90); rotation.postScale(-1, 1); break;
                    case ExifInterface.ORIENTATION_ROTATE_90: rotation.setRotate(90); break;
                    case ExifInterface.ORIENTATION_TRANSVERSE: rotation.setRotate(270); rotation.postScale(-1, 1); break;
                    case ExifInterface.ORIENTATION_ROTATE_270: rotation.setRotate(270); break;
                    default: break;
                }
            } catch (IOException ignored) { }
            if (!rotation.isIdentity()) {Bitmap corrected = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), rotation, true); if (corrected != bitmap) {bitmap.recycle(); bitmap = corrected;}}
            boolean alpha = bitmap.hasAlpha(); String basename = UUID.randomUUID().toString() + (alpha ? ".png" : ".jpg");
            output = File.createTempFile("note-image-ready-", ".tmp", context.getCacheDir());
            try (FileOutputStream destination = new FileOutputStream(output)) {if (!bitmap.compress(alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 92, destination)) throw new IOException("无法保存图片。"); destination.getFD().sync();}
            synchronized (NoteStore.class) {
                if (job.cancelled) throw new IOException("图片导入已取消。");
                if (!NoteStore.exists(context, job.noteId)) throw new IOException("这份笔记已被移除。");
                File target = new File(NoteStore.imagesDirectory(context, job.noteId), basename);
                if (!output.renameTo(target)) throw new IOException("无法保存图片。");
                job.filename = basename; job.width = bitmap.getWidth(); job.height = bitmap.getHeight();
            }
        } finally {if (bitmap != null) bitmap.recycle(); input.delete(); if (output != null) output.delete();}
    }
    private static void discardImage(Context context, ImageJob job) {
        synchronized (NoteStore.class) {
            if (job.filename == null || !NoteStore.exists(context, job.noteId)) return;
            try {new File(NoteStore.imagesDirectory(context, job.noteId), job.filename).delete();} catch (IOException ignored) { }
        }
    }
    private static final class ImageJob {
        final String noteId; volatile boolean ready, cancelled; volatile String error, filename; volatile int width, height;
        volatile WeakReference<NoteActivity> owner = new WeakReference<>(null);
        ImageJob(String noteId) {this.noteId = noteId;}
    }

    private void showDialog(Dialog next) {if (dialog != null) dialog.dismiss(); dialog = next;if(next.getWindow()!=null)NoteMotion.enter(next.getWindow().getDecorView()); next.setOnDismissListener(v -> {if (dialog == next) dialog = null;});}
    private void dismissPopup() {if (popup != null) popup.dismiss();}
    private IconButton icon(String kind, String title, View.OnClickListener listener) {IconButton view = new IconButton(this, kind, title); view.setOnClickListener(listener); return view;}
    private void addIcon(LinearLayout parent, View view) {parent.addView(view, new LinearLayout.LayoutParams(dp(44), dp(44)));}
    private void divider(LinearLayout parent) {View view = new View(this); view.setBackgroundColor(AppColors.BORDER); LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(1), dp(23)); params.setMargins(dp(5), 0, dp(5), 0); parent.addView(view, params);}
    private int dp(float value) {return NoteDialogs.dp(this, value);}
    private String hex(int value) {return String.format(Locale.ROOT, "#%06X", value & 0xffffff);}
    private void toast(String text) {Toast.makeText(this, text, Toast.LENGTH_LONG).show();}
    private static float clamp(float value, float minimum, float maximum) {return Float.isNaN(value) || Float.isInfinite(value) ? minimum : Math.max(minimum, Math.min(maximum, value));}
    @Override protected void onResume() {super.onResume(); resumed = true; consumeImage();}
    @Override protected void onPause() {resumed = false; pageSyncHandler.removeCallbacks(pendingPageSync); dismissPopup(); if (dialog != null) {dialog.dismiss(); dialog = null;} if (canvas != null) {canvas.finishGesture(); if(canvas.save())syncLibraryPages();} super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state) {super.onSaveInstanceState(state); state.putString("tool", tool);state.putBoolean("readOnly",readOnly); state.putBoolean("collapsed", headerCollapsed); state.putBoolean("pagesExpanded",pagesExpanded); state.putString("pendingExport", pendingExport);state.putBoolean("sourceConsumed",sourceConsumed);state.putLong("pendingSourceItemId",pendingSourceItemId);if(document!=null)state.putInt("currentPage",document.currentPage());}
    @Override protected void onNewIntent(Intent incoming){super.onNewIntent(incoming);setIntent(incoming);if(canvas!=null&&noteId.equals(incoming.getStringExtra("id"))&&incoming.hasExtra("page")){int page=incoming.getIntExtra("page",-1);if(page>=0&&page<document.pageCount())canvas.selectPage(page);}}
    @Override public void finish(){super.finish();NoteMotion.applyActivityTransition(this,false);}
    @Override protected void onDestroy() {
        destroyed = true; pageSyncHandler.removeCallbacks(pendingPageSync); dismissPopup(); if (dialog != null) {dialog.dismiss(); dialog = null;} if (canvas != null) {canvas.dispose(); canvas = null;}
        if(editLease!=null){editLease.close();editLease=null;}
        if (isFinishing() && pendingExport != null) { EXPORT_JOBS.remove(pendingExport); pendingExport = null; }
        ImageJob job = noteId == null ? null : IMAGE_JOBS.get(noteId);
        if (job != null && job.owner.get() == this) {
            job.owner = new WeakReference<>(null);
            if (isFinishing()) {job.cancelled = true; IMAGE_JOBS.remove(noteId, job); discardImage(getApplicationContext(), job);}
        }
        super.onDestroy();
    }
}
