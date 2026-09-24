package cn.qingyue.pdf;

import android.content.ClipData;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline OCR for a bounded PDF page range; export preserves the original library PDF. */
public final class OcrActivity extends ThemedActivity {
    public static final String EXTRA_DOC_ID="docId", EXTRA_PAGE="page";
    private static final int CREATE_PDF=901, MAX_BATCH=20;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService previewWorker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final LinkedHashMap<Integer,PageResult> results=new LinkedHashMap<>();
    private final LinkedHashMap<Integer,EditText> editors=new LinkedHashMap<>();
    private OcrCancellation cancellation,previewCancellation; private ExportSnapshot pendingExport; private String recognizedSourceHash;
    private volatile int generation; private volatile boolean destroyed; private boolean running;
    private LibraryItem item; private File pdf;
    private TextView status; private EditText fromInput,toInput;
    private LinearLayout resultList; private Button recognize,save,copy,export,cancel,currentPage;
    private Spinner language,layout; private ProgressBar progressBar; private int initialPage;

    private static final class PageResult {
        final OcrHocr.Page boxes; final String recognized; final int confidence;
        PageResult(OcrHocr.Page boxes,String recognized,int confidence) { this.boxes=boxes; this.recognized=recognized;this.confidence=confidence; }
    }
    private static final class ExportSnapshot {
        final File source; final String sourceHash; final Map<Integer,OcrHocr.Page> pages;
        ExportSnapshot(File source,String sourceHash,Map<Integer,OcrHocr.Page> pages) {
            this.source=source; this.sourceHash=sourceHash; this.pages=Collections.unmodifiableMap(new LinkedHashMap<>(pages));
        }
    }
    private static final class Retained {
        final LinkedHashMap<Integer,PageResult> pages; final LinkedHashMap<Integer,String> edits;
        final ExportSnapshot pending; final String from,to,sourceHash; final int language,layout;
        Retained(LinkedHashMap<Integer,PageResult> pages,LinkedHashMap<Integer,String> edits,ExportSnapshot pending,String from,String to,String sourceHash,int language,int layout) {
            this.pages=pages;this.edits=edits;this.pending=pending;this.from=from;this.to=to;this.sourceHash=sourceHash;this.language=language;this.layout=layout;
        }
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String id=getIntent().getStringExtra(EXTRA_DOC_ID);
        for(LibraryItem entry:LibraryStore.load(this).items)
            if(entry.id.equals(id)&&!entry.isNote()&&!entry.isTrashed()){item=entry;break;}
        if(item==null){Toast.makeText(this,"找不到 PDF 文档",Toast.LENGTH_LONG).show();finish();return;}
        pdf=new File(getFilesDir(),item.id+".pdf");
        if(!pdf.isFile()){Toast.makeText(this,"PDF 文件不存在",Toast.LENGTH_LONG).show();finish();return;}
        int page=getIntent().getIntExtra(EXTRA_PAGE,item.page);
        if(page<0||page>=item.pages)page=item.page;
        initialPage=page;buildLayout(page);
        Object retained=getLastNonConfigurationInstance();
        if(retained instanceof Retained){Retained previous=(Retained)retained;
            fromInput.setText(previous.from);toInput.setText(previous.to);language.setSelection(previous.language);layout.setSelection(previous.layout);
            results.putAll(previous.pages);pendingExport=previous.pending;recognizedSourceHash=previous.sourceHash;renderResults(previous.edits);
            status.setText(pendingExport==null?"已恢复识别文字":"已恢复导出任务，请选择保存位置");
        }else if(state!=null&&state.getBoolean("pendingExport"))
            status.setText("导出任务已中断；请重新识别后再导出");
        else loadSavedPage(page);
        updateButtons();
    }
    private void buildLayout(int page) {
        if (AppColors.isClassic()) buildClassicLayout(page);
        else buildPaperLayout(page);
    }
    private void buildPaperLayout(int page) {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.BACKGROUND); root.setPadding(dp(16), dp(10), dp(16), dp(24));
        LinearLayout heading = new LinearLayout(this); heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        IconButton back = new IconButton(this, "back", "返回阅读器"); back.setOnClickListener(v -> finish());
        heading.addView(back, new LinearLayout.LayoutParams(dp(42), dp(52)));
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(5), 0, 0, 0);
        TextView title = paperText("PDF 文字识别", 20, AppColors.INK); title.setTypeface(null, 1); titles.addView(title);
        TextView name = paperText(item.name, 12, AppColors.MUTED);
        name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, dp(4), 0, 0); titles.addView(name);
        heading.addView(titles, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(heading); paperGap(root, 16);

        LinearLayout setup = paperCard();
        LinearLayout intro = new LinearLayout(this); intro.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView setupTitle = paperText("识别范围", 16, AppColors.INK); setupTitle.setTypeface(null, 1);
        intro.addView(setupTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView local = paperText("本地 · 离线", 10, AppColors.ACCENT_DARK);
        local.setPadding(dp(10), dp(5), dp(10), dp(5)); local.setBackground(pill(AppColors.SOFT));
        intro.addView(local); setup.addView(intro); paperGap(setup, 16);
        LinearLayout range = new LinearLayout(this); range.setGravity(android.view.Gravity.BOTTOM);
        fromInput = pageField(page + 1, "起始页"); toInput = pageField(page + 1, "结束页");
        range.addView(labeledField("起始页", fromInput), new LinearLayout.LayoutParams(0, -2, 1));
        range.addView(labeledField("结束页", toInput), new LinearLayout.LayoutParams(0, -2, 1));
        currentPage = button(range, "当前页", v -> {
            fromInput.setText(Integer.toString(initialPage + 1)); toInput.setText(Integer.toString(initialPage + 1));
        }); setup.addView(range); paperGap(setup, 14);
        LinearLayout options = new LinearLayout(this);
        language = selector(new String[]{"中文优先", "中英混合", "英文"});
        layout = selector(new String[]{"正文 / 常规页", "稀疏 / 封面"});
        options.addView(labeledField("识别语言", language), new LinearLayout.LayoutParams(0, -2, 1));
        options.addView(labeledField("页面版式", layout), new LinearLayout.LayoutParams(0, -2, 1));
        setup.addView(options); paperGap(setup, 12);
        setup.addView(paperText("可识别第 1–" + item.pages + " 页，每次最多 " + MAX_BATCH + " 页。", 12, AppColors.MUTED));
        paperGap(setup, 15);
        LinearLayout primary = new LinearLayout(this);
        recognize = button(primary, "开始识别", v -> startRecognition());
        recognize.setBackground(pill(AppColors.ACCENT)); recognize.setTextColor(AppColors.ON_ACCENT);
        LinearLayout.LayoutParams primarySize = new LinearLayout.LayoutParams(0, dp(48), 1);
        primarySize.rightMargin = dp(8); recognize.setLayoutParams(primarySize);
        cancel = button(primary, "取消任务", v -> cancelWork()); setup.addView(primary);
        root.addView(setup); paperGap(root, 20);

        TextView resultsTitle = paperText("识别结果", 16, AppColors.INK); resultsTitle.setTypeface(null, 1);
        root.addView(resultsTitle); paperGap(root, 8);
        status = paperText("选择页码和识别方式，然后开始识别", 12, AppColors.MUTED);
        root.addView(status); paperGap(root, 10);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100); progressBar.setVisibility(View.GONE); root.addView(progressBar);
        HorizontalScrollView actionScroll = new HorizontalScrollView(this); actionScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = new LinearLayout(this);
        save = button(actions, "保存文字", v -> saveText()); copy = button(actions, "复制", v -> copyText());
        export = button(actions, "导出可搜索 PDF", v -> chooseExport());
        actionScroll.addView(actions); root.addView(actionScroll); paperGap(root, 12);
        resultList = paperCard(); root.addView(resultList, new LinearLayout.LayoutParams(-1, -2));
        TextView empty = paperText("识别后，可在这里对照原页并修订文字", 13, AppColors.MUTED);
        empty.setGravity(android.view.Gravity.CENTER); empty.setPadding(dp(8), dp(34), dp(8), dp(34));
        resultList.addView(empty); paperGap(root, 12);
        root.addView(paperText("修订文字可保存和复制；PDF 文字层使用原始识别结果。导出副本保留原 PDF 页面，不含晴阅中的手写批注。", 11, AppColors.MUTED));
        ScrollView screen = new ScrollView(this); screen.setBackgroundColor(AppColors.BACKGROUND);
        screen.setVerticalScrollBarEnabled(false); screen.setFitsSystemWindows(true);
        screen.addView(root, new ScrollView.LayoutParams(-1, -2)); setContentView(screen);
    }
    private TextView paperText(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(color); view.setLineSpacing(dp(3), 1f); return view;
    }
    private LinearLayout paperCard() {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(16), dp(14), dp(16)); card.setBackground(pill(AppColors.SURFACE)); return card;
    }
    private void paperGap(LinearLayout parent, int height) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
    }
    private void buildClassicLayout(int page) {
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.BACKGROUND);root.setPadding(dp(16),dp(8),dp(16),dp(8));
        LinearLayout heading=new LinearLayout(this);heading.setOrientation(LinearLayout.HORIZONTAL);
        Button back=button(heading,"返回",v->finish());
        TextView title=new TextView(this);title.setText("PDF 文字识别");title.setTextColor(AppColors.INK);title.setTextSize(20);
        title.setGravity(17);heading.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(heading);
        TextView name=new TextView(this);name.setText(item.name);name.setTextColor(AppColors.MUTED);name.setSingleLine(true);root.addView(name);
        LinearLayout range=new LinearLayout(this);range.setOrientation(LinearLayout.HORIZONTAL);
        fromInput=pageField(page+1,"起始页");toInput=pageField(page+1,"结束页");
        range.addView(labeledField("起始页",fromInput),new LinearLayout.LayoutParams(0,-2,1));
        range.addView(labeledField("结束页",toInput),new LinearLayout.LayoutParams(0,-2,1));
        currentPage=button(range,"当前页",v->{fromInput.setText(Integer.toString(initialPage+1));toInput.setText(Integer.toString(initialPage+1));});
        root.addView(range);
        LinearLayout options=new LinearLayout(this);options.setOrientation(LinearLayout.HORIZONTAL);
        language=selector(new String[]{"中文优先","中英混合","英文"});
        layout=selector(new String[]{"正文 / 常规页","稀疏 / 封面"});
        options.addView(labeledField("识别语言",language),new LinearLayout.LayoutParams(0,-2,1));
        options.addView(labeledField("页面版式",layout),new LinearLayout.LayoutParams(0,-2,1));root.addView(options);
        TextView hint=new TextView(this);hint.setText("可识别第 1–"+item.pages+" 页；每次最多 "+MAX_BATCH+" 页。识别离线完成。");
        hint.setTextColor(AppColors.MUTED);root.addView(hint);
        TextView exportHint=new TextView(this);exportHint.setText("修订后的文字可保存和复制；PDF 文字层使用未修改的识别结果。副本保留原 PDF 页面，不包含晴阅内的手写批注。");
        exportHint.setTextColor(AppColors.MUTED);exportHint.setTextSize(12);root.addView(exportHint);
        LinearLayout primaryActions=new LinearLayout(this);primaryActions.setOrientation(LinearLayout.HORIZONTAL);primaryActions.setPadding(0,dp(10),0,dp(8));
        recognize=button(primaryActions,"开始识别",v->startRecognition());recognize.setBackground(pill(AppColors.ACCENT));recognize.setTextColor(AppColors.ON_ACCENT);
        LinearLayout.LayoutParams primarySize=new LinearLayout.LayoutParams(0,dp(48),1);primarySize.rightMargin=dp(8);recognize.setLayoutParams(primarySize);
        cancel=button(primaryActions,"取消任务",v->cancelWork());root.addView(primaryActions);
        HorizontalScrollView actionScroll=new HorizontalScrollView(this);actionScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        save=button(actions,"保存文字",v->saveText());
        copy=button(actions,"复制",v->copyText());
        export=button(actions,"导出可搜索 PDF",v->chooseExport());
        actionScroll.addView(actions);root.addView(actionScroll);
        status=new TextView(this);status.setText("选择页码和识别方式，然后开始识别");status.setTextColor(AppColors.INK);
        status.setPadding(dp(8),dp(6),dp(8),dp(4));root.addView(status);
        progressBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progressBar.setMax(100);progressBar.setVisibility(View.GONE);root.addView(progressBar);
        resultList=new LinearLayout(this);resultList.setOrientation(LinearLayout.VERTICAL);resultList.setPadding(dp(8),dp(8),dp(8),dp(8));
        GradientDrawable card=new GradientDrawable();card.setColor(AppColors.SURFACE);card.setCornerRadius(dp(12));
        resultList.setBackground(card);
        root.addView(resultList,new LinearLayout.LayoutParams(-1,-2));
        ScrollView screen=new ScrollView(this);screen.setFitsSystemWindows(true);screen.addView(root,new ScrollView.LayoutParams(-1,-2));setContentView(screen);
        TextView empty=new TextView(this);empty.setText("识别后可在此对照原页并修订文字");empty.setTextColor(AppColors.MUTED);empty.setPadding(dp(8),dp(12),dp(8),dp(8));resultList.addView(empty);
    }
    private LinearLayout labeledField(String label,View field){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(0,0,dp(8),0);
        TextView caption=new TextView(this);caption.setText(label);caption.setTextSize(12);caption.setTextColor(AppColors.MUTED);box.addView(caption);
        if (!AppColors.isClassic()) caption.setPadding(0, 0, 0, dp(6));
        box.addView(field,new LinearLayout.LayoutParams(-1,dp(44)));return box;
    }
    private Spinner selector(String[] values){Spinner spinner=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,values);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);spinner.setAdapter(adapter);spinner.setBackground(pill(AppColors.SURFACE));return spinner;}
    private GradientDrawable pill(int color){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(AppColors.isClassic()?12:16));shape.setStroke(dp(1),!AppColors.isClassic()&&color==AppColors.ACCENT?AppColors.ACCENT:AppColors.BORDER);return shape;}
    private EditText pageField(int initial,String hint) {
        EditText field=new EditText(this);field.setSingleLine(true);field.setInputType(2);field.setText(Integer.toString(initial));
        field.setHint(hint);field.setTextColor(AppColors.INK);field.setTextSize(16);field.setSelectAllOnFocus(true);field.setBackground(pill(AppColors.SURFACE));field.setPadding(dp(12),0,dp(12),0);return field;
    }
    private Button button(LinearLayout row,String title,View.OnClickListener action) {
        Button button=new Button(this);button.setText(title);button.setTextColor(AppColors.ACCENT_DARK);
        button.setAllCaps(false);button.setTextSize(14);button.setPadding(dp(12),0,dp(12),0);button.setOnClickListener(action);button.setBackground(pill(AppColors.SOFT));
        if (!AppColors.isClassic()) { button.setBackground(pill(AppColors.SURFACE)); button.setMinWidth(0); button.setMinimumWidth(0); button.setStateListAnimator(null); button.setElevation(0); }
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,dp(44));params.setMargins(0,0,dp(6),0);row.addView(button,params);return button;
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void startRecognition() {
        int from,to;
        try {from=Integer.parseInt(fromInput.getText().toString().trim())-1;
            to=Integer.parseInt(toInput.getText().toString().trim())-1;}
        catch(NumberFormatException bad){status.setText("请输入有效起止页码");return;}
        if(from<0||to<from||to>=item.pages||to-from+1>MAX_BATCH){status.setText("页范围无效；每次最多 "+MAX_BATCH+" 页");return;}
        cancelWork(); final int task=++generation,start=from,end=to;
        final OcrEngine.Language selectedLanguage=OcrEngine.Language.values()[language.getSelectedItemPosition()];
        final OcrEngine.Layout selectedLayout=OcrEngine.Layout.values()[layout.getSelectedItemPosition()];
        final File source=pdf;final OcrCancellation token=new OcrCancellation();
        cancellation=token;running=true;results.clear();editors.clear();resultList.removeAllViews();pendingExport=null;recognizedSourceHash=null;
        updateButtons();status.setText("正在准备离线识别…");
        worker.execute(()->{
            LinkedHashMap<Integer,PageResult> completed=new LinkedHashMap<>();
            try {
                String sourceHash=sourceHash(source,token);
                for(int index=start;index<=end;index++) {
                    token.throwIfCancelled();final int current=index;Bitmap bitmap=null;
                    try {
                        bitmap=OcrImageLoader.renderPdfPage(source,current,token).bitmap;
                        OcrEngine.Result found=OcrEngine.recognize(getApplicationContext(),bitmap,current,selectedLanguage,selectedLayout,token,
                            (percent,stage)->main.post(()->{if(valid(task)){status.setText("第 "+(current-start+1)+"/"+(end-start+1)+" 页 · "+stage+" "+percent+"%");progressBar.setProgress((current-start)*100/(end-start+1)+percent/(end-start+1));}}));
                        OcrHocr.Page boxes=OcrHocr.parse(found.hocr);
                        completed.put(current,new PageResult(boxes,found.text,found.confidence));
                    } finally {if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
                }
                if(!sourceHash.equals(sourceHash(source,token)))throw new IOException("PDF 原文在识别期间发生变化，请重新打开");
                main.post(()->{if(!valid(task))return;recognizedSourceHash=sourceHash;results.putAll(completed);renderResults(null);
                    running=false;cancellation=null;updateButtons();
                    int sum=0;for(PageResult page:completed.values())sum+=page.confidence;
                    int mean=completed.isEmpty()?0:sum/completed.size();
                    status.setText("已识别 "+completed.size()+" 页 · 引擎平均置信度 "+mean+"/100。"+(mean<70?"结果可信度偏低，请重点对照原页。":"请对照原页核对；置信度不等于准确率。"));});
            } catch(Exception | LinkageError | OutOfMemoryError failure) {
                if(!token.isCancelled())CrashDiagnostics.record(getApplicationContext(),"OCR_RECOGNIZE",failure);
                main.post(()->{if(!valid(task))return;running=false;cancellation=null;updateButtons();
                    status.setText(token.isCancelled()?"已取消":"识别失败："+safeMessage(failure));});
            }
        });
    }
    private void renderResults(Map<Integer,String> edits) {
        resultList.removeAllViews();editors.clear();
        LinkedHashMap<Integer,String> displayed=new LinkedHashMap<>();
        for(Map.Entry<Integer,PageResult> entry:results.entrySet())displayed.put(entry.getKey(),entry.getValue().recognized);
        if(edits!=null)displayed.putAll(edits);
        for(Map.Entry<Integer,String> entry:displayed.entrySet()){
            int page=entry.getKey();PageResult info=results.get(page);TextView label=new TextView(this);
            label.setText("第 "+(page+1)+" 页"+(info==null?"":" · 引擎置信度 "+info.confidence+"/100"+(info.confidence<70?" · 建议重点核对":"")));
            label.setTextColor(AppColors.ACCENT_DARK);label.setTextSize(17);resultList.addView(label);
            if (!AppColors.isClassic()) { label.setTextSize(13); label.setTypeface(null, 1); label.setPadding(0, dp(4), 0, dp(10)); }
            addPreview(page);
            EditText edit=new EditText(this);edit.setText(entry.getValue());
            edit.setTextColor(AppColors.INK);edit.setTextSize(15);edit.setGravity(3);edit.setMinLines(4);
            if (!AppColors.isClassic()) { edit.setBackground(pill(AppColors.BACKGROUND)); edit.setPadding(dp(12), dp(12), dp(12), dp(12)); edit.setLineSpacing(dp(4), 1f); }
            resultList.addView(edit,new LinearLayout.LayoutParams(-1,-2));editors.put(page,edit);
            if (!AppColors.isClassic()) paperGap(resultList, 18);
        }
    }
    private void addPreview(int page){
        Button open=new Button(this);open.setText("查看第 "+(page+1)+" 页原页");open.setAllCaps(false);
        open.setTextColor(AppColors.ACCENT_DARK);open.setBackground(pill(AppColors.SOFT));
        if (!AppColors.isClassic()) { open.setTextSize(12); open.setStateListAnimator(null); open.setElevation(0); }
        resultList.addView(open,new LinearLayout.LayoutParams(-1,dp(40)));
        if (!AppColors.isClassic()) paperGap(resultList, 8);
        open.setOnClickListener(v->showPreview(page));
    }
    private void showPreview(int page){
        if(previewCancellation!=null)previewCancellation.cancel();
        final OcrCancellation token=new OcrCancellation();previewCancellation=token;
        final int task=generation;
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(12),dp(8),dp(12),dp(8));
        TextView message=new TextView(this);message.setText("正在载入第 "+(page+1)+" 页…");message.setTextColor(AppColors.MUTED);content.addView(message);
        ImageView image=new ImageView(this);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        content.addView(image,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("第 "+(page+1)+" 页原页")
                .setView(scroll).setPositiveButton("关闭",(d,w)->{}).create();
        dialog.setOnDismissListener(d->{token.cancel();if(previewCancellation==token)previewCancellation=null;});
        dialog.show();
        previewWorker.execute(()->{
            if(token.isCancelled()||!valid(task))return;
            Bitmap bitmap=null;
            try{
                bitmap=OcrImageLoader.renderPdfPreview(pdf,page,token).bitmap;
                token.throwIfCancelled();
                final Bitmap delivered=bitmap;bitmap=null;
                main.post(()->{if(valid(task)&&!token.isCancelled()&&dialog.isShowing()){
                    image.setImageBitmap(delivered);message.setText("原页预览；关闭后可继续校对识别文字");
                }else delivered.recycle();});
            }catch(Exception | LinkageError | OutOfMemoryError failure){
                main.post(()->{if(valid(task)&&!token.isCancelled()&&dialog.isShowing())message.setText("原页预览不可用，请关闭后重试");});
            }finally{if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
        });
    }
    private void saveText() {
        if(editors.isEmpty()){status.setText("请先识别文字");return;}
        final int task=generation;final String docId=item.id,hash=item.hash,verifiedHash=recognizedSourceHash;
        final LinkedHashMap<Integer,String> texts=new LinkedHashMap<>();
        for(Map.Entry<Integer,EditText> entry:editors.entrySet())texts.put(entry.getKey(),entry.getValue().getText().toString());
        worker.execute(()->{try {
            if(verifiedHash==null)throw new IOException("PDF 来源尚未核验，请重新打开页面");
            OcrCancellation check=new OcrCancellation();String actual=sourceHash(pdf,check);
            if(!verifiedHash.equals(actual))throw new IOException("PDF 原文已变化，请重新识别或载入文字");
            for(Map.Entry<Integer,String> entry:texts.entrySet()){
                if(!valid(task))throw new IOException("OCR 保存已取消");
                OcrSidecar.saveVerified(getApplicationContext(),docId,entry.getKey(),hash,actual,entry.getValue());
            }
            main.post(()->{if(valid(task))status.setText("已保存 "+texts.size()+" 页识别文字");});
        }catch(IOException failure){CrashDiagnostics.record(getApplicationContext(),"OCR_SAVE",failure);
            main.post(()->{if(valid(task))status.setText("保存失败："+failure.getMessage());});}});
    }
    private void copyText() {
        StringBuilder text=new StringBuilder();
        for(Map.Entry<Integer,EditText> entry:editors.entrySet())
            text.append("第 ").append(entry.getKey()+1).append(" 页\n").append(entry.getValue().getText()).append("\n\n");
        if(text.length()>200000){status.setText("文字较多，请分批识别后复制，或先保存文字");return;}
        try{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("OCR",text.toString()));}
        catch(RuntimeException failure){status.setText("系统剪贴板暂不可用，请先保存文字");return;}
        Toast.makeText(this,"已复制文字",Toast.LENGTH_SHORT).show();
    }
    private void chooseExport() {
        if(results.isEmpty()){status.setText("请先识别带坐标的文字");return;}
        LinkedHashMap<Integer,OcrHocr.Page> boxes=new LinkedHashMap<>();
        for(Map.Entry<Integer,PageResult> entry:results.entrySet()){
            EditText edit=editors.get(entry.getKey());
            if(edit==null||!entry.getValue().recognized.equals(edit.getText().toString())){
                status.setText("第 "+(entry.getKey()+1)+" 页文字已修改；请重新识别后导出，以免坐标与文字不一致");return;
            }
            if(!entry.getValue().boxes.words.isEmpty())boxes.put(entry.getKey(),entry.getValue().boxes);
        }
        if(boxes.isEmpty()){status.setText("所选页面没有可导出的文字框");return;}
        if(recognizedSourceHash==null){status.setText("识别来源已失效，请重新识别");return;}
        pendingExport=new ExportSnapshot(pdf,recognizedSourceHash,boxes);
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/pdf")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,item.name+"-可搜索.pdf");
        try{startActivityForResult(intent,CREATE_PDF);}catch(RuntimeException unavailable){pendingExport=null;status.setText("无法打开系统保存位置选择器");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=CREATE_PDF)return;
        final ExportSnapshot snapshot=pendingExport;pendingExport=null;
        if(result!=RESULT_OK||data==null||data.getData()==null){status.setText("已取消导出，识别结果仍可使用");return;}
        if(snapshot==null){status.setText("导出状态已失效；请重新识别后重试");return;}
        final Uri target=data.getData();final int task=++generation;final OcrCancellation token=new OcrCancellation();
        cancellation=token;running=true;updateButtons();status.setText("正在生成可搜索 PDF…");
        worker.execute(()->{
            File temp=null,sourceCopy=null;boolean wroteDestination=false;
            try {
                sourceCopy=File.createTempFile("ocr-source-",".pdf",getCacheDir());
                copySource(snapshot.source,sourceCopy,snapshot.sourceHash,token);
                temp=File.createTempFile("ocr-export-",".pdf",getCacheDir());
                OcrPdfExporter.export(getApplicationContext(),sourceCopy,temp,snapshot.pages,token);
                token.throwIfCancelled();
                try(FileInputStream in=new FileInputStream(temp);OutputStream out=getContentResolver().openOutputStream(target,"w")){
                    if(out==null)throw new IOException("无法写入选择的位置");
                    wroteDestination=true;
                    byte[] buffer=new byte[65536];int n;
                    while((n=in.read(buffer))!=-1){token.throwIfCancelled();out.write(buffer,0,n);}out.flush();
                }
                token.throwIfCancelled();
                main.post(()->{if(valid(task)){running=false;cancellation=null;updateButtons();status.setText("已导出可搜索 PDF");}});
            }catch(Exception | LinkageError | OutOfMemoryError failure){
                if(!token.isCancelled())CrashDiagnostics.record(getApplicationContext(),"OCR_EXPORT",failure);
                final boolean partial=wroteDestination;
                main.post(()->{if(valid(task)){running=false;cancellation=null;updateButtons();status.setText((token.isCancelled()?"已取消":"导出失败："+safeMessage(failure))+(partial?"；保存位置可能留下未完成文件":""));}});
            }finally{if(temp!=null)temp.delete();if(sourceCopy!=null)sourceCopy.delete();}
        });
    }
    private static void copySource(File source,File copy,String expectedHash,OcrCancellation token)throws IOException {
        MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IOException("无法校验 PDF 原文",impossible);}
        try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(copy)){
            byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){token.throwIfCancelled();digest.update(buffer,0,n);out.write(buffer,0,n);}out.flush();
        }
        token.throwIfCancelled();if(expectedHash!=null&&!expectedHash.isEmpty()){
            if(!expectedHash.equalsIgnoreCase(hex(digest.digest())))throw new IOException("PDF 原文已变化，请重新打开并识别");
        }
    }
    private static String sourceHash(File source,OcrCancellation token)throws IOException {
        MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IOException("无法校验 PDF 原文",impossible);}
        try(InputStream in=new FileInputStream(source)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){token.throwIfCancelled();digest.update(buffer,0,n);}}
        token.throwIfCancelled();return hex(digest.digest());
    }
    private static String hex(byte[] digest){StringBuilder hash=new StringBuilder(digest.length*2);for(byte value:digest)hash.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return hash.toString();}
    private static String safeMessage(Throwable failure){String message=failure.getMessage();return message==null||message.trim().isEmpty()?"请稍后重试":message;}
    private void loadSavedPage(int page) {
        final int task=generation,requested=page;final String docId=item.id,hash=item.hash;
        worker.execute(()->{try{OcrCancellation check=new OcrCancellation();String actual=sourceHash(pdf,check);
            String value=OcrSidecar.loadForPdf(getApplicationContext(),docId,requested,hash,actual,true);
            main.post(()->{if(valid(task)&&!running&&!value.isEmpty()&&results.isEmpty()){
                recognizedSourceHash=actual;
                resultList.removeAllViews();
                TextView label=new TextView(this);label.setText("第 "+(requested+1)+" 页 · 已保存文字");
                label.setTextColor(AppColors.ACCENT_DARK);resultList.addView(label);
                addPreview(requested);
                EditText edit=new EditText(this);edit.setText(value);edit.setMinLines(4);resultList.addView(edit);
                editors.put(requested,edit);updateButtons();
                status.setText("已载入保存文字；重新识别后可导出 PDF 文字层");
            }});
        }catch(IOException failure){CrashDiagnostics.record(getApplicationContext(),"OCR_SIDECAR_READ",failure);}});
    }
    private boolean valid(int task){return !destroyed&&generation==task;}
    private void cancelWork(){if(cancellation!=null)cancellation.cancel();generation++;cancellation=null;running=false;
        if(status!=null)status.setText("已取消");updateButtons();}
    private void updateButtons(){if(recognize==null)return;fromInput.setEnabled(!running);toInput.setEnabled(!running);currentPage.setEnabled(!running);language.setEnabled(!running);layout.setEnabled(!running);progressBar.setVisibility(running?View.VISIBLE:View.GONE);
        recognize.setEnabled(!running);save.setEnabled(!running&&!editors.isEmpty());copy.setEnabled(!running&&!editors.isEmpty());
        export.setEnabled(!running&&!results.isEmpty());cancel.setEnabled(running);
        for(Button action:new Button[]{recognize,save,copy,export,cancel})action.setAlpha(action.isEnabled()?1f:.4f);}
    @Override public Object onRetainNonConfigurationInstance(){
        if(fromInput==null||toInput==null)return null;
        LinkedHashMap<Integer,String> edits=new LinkedHashMap<>();
        for(Map.Entry<Integer,EditText> entry:editors.entrySet())edits.put(entry.getKey(),entry.getValue().getText().toString());
        return new Retained(new LinkedHashMap<>(results),edits,pendingExport,
                fromInput.getText().toString(),toInput.getText().toString(),recognizedSourceHash,
                language.getSelectedItemPosition(),layout.getSelectedItemPosition());
    }
    @Override protected void onSaveInstanceState(Bundle out){out.putBoolean("pendingExport",pendingExport!=null);super.onSaveInstanceState(out);}
    @Override protected void onStop(){if(running)cancelWork();if(previewCancellation!=null)previewCancellation.cancel();super.onStop();}
    @Override protected void onDestroy(){destroyed=true;cancelWork();if(previewCancellation!=null)previewCancellation.cancel();worker.shutdownNow();previewWorker.shutdownNow();super.onDestroy();}
}
