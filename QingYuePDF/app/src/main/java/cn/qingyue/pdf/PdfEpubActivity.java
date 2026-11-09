package cn.qingyue.pdf;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Simple offline PDF to EPUB setup, progress, warning and chapter preview screen. */
public final class PdfEpubActivity extends ThemedActivity {
    public static final String EXTRA_DOC_ID="docId";
    public static final String EXTRA_SESSION_ID="sessionId";
    public static final String EXTRA_SAVE_NOW="saveNow";
    private static final int CREATE_EPUB=902;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private LibraryItem item;
    private EditText title,author,from,to;
    private CheckBox allPages,coverFirstPage,deskew;
    private TextView status,warnings,warningDetails,previewText;
    private ProgressBar progress;
    private Button sample,convert,cancel,save,share,ocr,smaller,larger,warningsToggle,continueTask,editChapters;
    private Spinner chapters;
    private EpubConversionCoordinator.Session session;
    private final EpubConversionCoordinator.Listener listener=this::changed;
    private String pendingSaveSessionId, savedSessionId, saveJobId;
    private Uri savedUri;
    private SaveJob saveJob;
    private final SaveJob.Listener saveListener=this::saveChanged;
    private boolean destroyed;
    private float previewSp=16;
    private int chapterGeneration;
    private int suggestedOcrPage;
    private String chapterDraft;
    private boolean manualChapters;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        EpubConversionCoordinator.initialize(this);
        String requestedSession=getIntent().getStringExtra(EXTRA_SESSION_ID);
        session=EpubConversionCoordinator.find(requestedSession);
        String id=getIntent().getStringExtra(EXTRA_DOC_ID);
        if(session!=null)id=session.documentId();
        for(LibraryItem candidate:LibraryStore.load(this).items)
            if(candidate.id.equals(id)&&!candidate.isNote()&&!candidate.isTrashed()){item=candidate;break;}
        if(item==null&&session!=null){item=new LibraryItem();item.id=session.documentId();item.name=session.title();item.pages=session.to();}
        if(item==null){
            Toast.makeText(this,"找不到原 PDF",Toast.LENGTH_LONG).show();finish();return;
        }
        build();
        if(state!=null){
            title.setText(state.getString("title",suggestTitle(item.name)));
            author.setText(state.getString("author",""));
            from.setText(state.getString("from","1"));to.setText(state.getString("to",Integer.toString(item.pages)));
            allPages.setChecked(state.getBoolean("all",true));
            coverFirstPage.setChecked(state.getBoolean("cover",false));
            deskew.setChecked(state.getBoolean("deskew",false));
            chapterDraft=state.getString("chapterDraft");manualChapters=state.getBoolean("manualChapters");
            previewSp=state.getFloat("previewSp",16);previewText.setTextSize(previewSp);
            suggestedOcrPage=state.getInt("suggestedOcrPage",item.page);
            pendingSaveSessionId=state.getString("pendingSaveSessionId");
            savedSessionId=state.getString("savedSessionId");
            savedUri=state.getParcelable("savedUri");
            saveJobId=state.getString("saveJobId");
            if(session==null)session=EpubConversionCoordinator.find(state.getString("sessionId"));
        }else{title.setText(session==null?suggestTitle(item.name):session.title());author.setText(session==null?"":session.author());
            from.setText(session==null?"1":String.valueOf(session.from()+1));to.setText(Integer.toString(session==null?item.pages:session.to()));
            allPages.setChecked(session==null||session.from()==0&&session.to()==item.pages);
            deskew.setChecked(session!=null&&session.deskew());
            if(session!=null&&session.overrides()!=null){manualChapters=true;chapterDraft=formatHeadings(session.overrides());}}
        updateRange();
        if(session!=null)session.attach(listener);
        else if(state!=null&&state.getString("sessionId")!=null)status.setText("原任务记录不可用，请从导出中心查看。");
        saveJob=SaveJob.find(saveJobId);if(saveJob!=null)saveJob.attach(saveListener);
        render(session==null?null:session.snapshot());
        if(getIntent().getBooleanExtra(EXTRA_SAVE_NOW,false))MAIN.post(this::chooseSave);
    }
    private void build(){
        ScrollView screen=new ScrollView(this);screen.setFillViewport(true);screen.setBackgroundColor(AppColors.BACKGROUND);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18),dp(16),dp(18),dp(28));screen.addView(body);
        setContentView(screen);UiSafeArea.install(this,screen,true);
        TextView heading=text("转换为 EPUB 电子书",22,AppColors.INK);heading.setTypeface(null,1);body.addView(heading);
        add(body,text(item.name+" · "+item.pages+" 页",12,AppColors.MUTED),6);
        add(body,text("本机离线转换 · 原 PDF 不会修改",12,AppColors.ACCENT_DARK),8);
        LinearLayout setup=card();add(body,setup,18);
        add(setup,text("书籍信息",17,AppColors.INK),0);
        title=field("电子书标题");author=field("作者（可留空）");
        add(setup,title,10);add(setup,author,8);
        allPages=new CheckBox(this);allPages.setText("全部页面");allPages.setTextColor(AppColors.INK);
        allPages.setButtonTintList(android.content.res.ColorStateList.valueOf(AppColors.ACCENT_DARK));
        allPages.setOnCheckedChangeListener((button,checked)->updateRange());add(setup,allPages,12);
        LinearLayout range=row();from=pageField("起始页");to=pageField("结束页");
        range.addView(from,new LinearLayout.LayoutParams(0,dp(48),1));range.addView(to,new LinearLayout.LayoutParams(0,dp(48),1));add(setup,range,4);
        TextView rangeHelp=text("试转与页码说明  ▾",12,AppColors.ACCENT_DARK);
        rangeHelp.setContentDescription("展开试转与页码说明");rangeHelp.setClickable(true);
        add(setup,rangeHelp,8);
        TextView rangeDetail=text("起止页均包含在内。可先试转 3 页检查 OCR；若前 3 页是封面或目录，可指定正文页试转。",12,AppColors.MUTED);
        rangeDetail.setVisibility(View.GONE);add(setup,rangeDetail,5);
        rangeHelp.setOnClickListener(v->{boolean open=rangeDetail.getVisibility()!=View.VISIBLE;
            rangeDetail.setVisibility(open?View.VISIBLE:View.GONE);
            rangeHelp.setText(open?"试转与页码说明  ▴":"试转与页码说明  ▾");
            rangeHelp.setContentDescription(open?"收起试转与页码说明":"展开试转与页码说明");});
        coverFirstPage=new CheckBox(this);coverFirstPage.setText("将所选范围的第一页作为图片封面");
        coverFirstPage.setTextColor(AppColors.INK);coverFirstPage.setButtonTintList(android.content.res.ColorStateList.valueOf(AppColors.ACCENT_DARK));
        add(setup,coverFirstPage,8);
        deskew=new CheckBox(this);deskew.setText("扫描页自动去斜（可能增加处理时间）");deskew.setTextColor(AppColors.INK);
        deskew.setButtonTintList(android.content.res.ColorStateList.valueOf(AppColors.ACCENT_DARK));add(setup,deskew,6);
        add(setup,button("章节设置 · 自动识别 / 手动指定",this::editChapterDraft),8);
        LinearLayout actions=row();sample=button("先试转 3 页",()->start(true));convert=button("转换所选全部",()->start(false));
        actions.addView(sample,new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(convert,new LinearLayout.LayoutParams(0,dp(48),1));add(setup,actions,14);
        add(body,text("扫描页会自动逐页 OCR；整本扫描书可能需要较长时间，错字需人工核对。正文插图暂不嵌入。",12,AppColors.MUTED),12);
        LinearLayout task=card();add(body,task,18);
        status=text("填写信息后开始转换",13,AppColors.INK);add(task,status,0);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);add(task,progress,10);
        cancel=button("取消转换",this::askCancel);add(task,cancel,10);
        continueTask=button("继续中断任务",()->{if(session!=null&&session.resume())startService();});add(task,continueTask,8);
        add(task,button("打开导出中心",()->startActivity(new Intent(this,ExportCenterActivity.class))),8);
        warnings=text("",12,AppColors.MUTED);add(task,warnings,12);
        warningsToggle=button("查看需校订的页码",()->warningDetails.setVisibility(
                warningDetails.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        warningsToggle.setVisibility(View.GONE);
        add(task,warningsToggle,8);
        warningDetails=text("",12,AppColors.MUTED);warningDetails.setVisibility(View.GONE);add(task,warningDetails,8);
        LinearLayout outputs=row();save=button("保存 .epub",this::chooseSave);share=button("分享已保存文件",this::shareSaved);
        outputs.addView(save,new LinearLayout.LayoutParams(0,dp(48),1));outputs.addView(share,new LinearLayout.LayoutParams(0,dp(48),1));add(task,outputs,12);
        ocr=button("前往 OCR 校订",this::openOcr);add(task,ocr,9);
        LinearLayout reading=card();add(body,reading,18);
        add(reading,text("章节与正文预览",17,AppColors.INK),0);
        editChapters=button("修改标题与章节切分并重建",this::editAfterPreview);add(reading,editChapters,8);
        chapters=new Spinner(this);add(reading,chapters,8);
        LinearLayout font=row();smaller=button("A−",()->setPreviewSize(previewSp-2));larger=button("A+",()->setPreviewSize(previewSp+2));
        font.addView(smaller,new LinearLayout.LayoutParams(0,dp(44),1));font.addView(larger,new LinearLayout.LayoutParams(0,dp(44),1));add(reading,font,6);
        ScrollView chapterScroll=new ScrollView(this);chapterScroll.setFillViewport(false);
        previewText=text("转换完成后可在此浏览章节正文。",16,AppColors.INK);
        previewText.setLineSpacing(dp(4),1.18f);previewText.setTextIsSelectable(true);
        chapterScroll.addView(previewText);reading.addView(chapterScroll,new LinearLayout.LayoutParams(-1,dp(320)));
        add(reading,text("此处仅供核对转换文字与章节；阅读请使用电子书应用。",11,AppColors.MUTED),6);
        render(null);
    }
    private void updateRange(){if(from==null)return;boolean enabled=!allPages.isChecked();from.setEnabled(enabled);to.setEnabled(enabled);}
    private static final class ChapterRow {
        final EditText page,title,anchor;ChapterRow(EditText page,EditText title,EditText anchor){
            this.page=page;this.title=title;this.anchor=anchor;}
    }
    private void editChapterDraft(){showChapterEditor(false);}
    private void editAfterPreview(){if(session!=null&&session.snapshot().state==EpubConversionCoordinator.State.COMPLETED)
        showChapterEditor(true);}
    private void showChapterEditor(boolean rebuild){
        ScrollView scroller=new ScrollView(this);LinearLayout body=new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(16),dp(8),dp(16),dp(8));scroller.addView(body);
        add(body,text("每章填写原 PDF 页码和标题。可选填页内定位，指定从页面哪句开始。",13,AppColors.MUTED),0);
        LinearLayout rowsHost=new LinearLayout(this);rowsHost.setOrientation(LinearLayout.VERTICAL);add(body,rowsHost,9);
        ArrayList<ChapterRow> rows=new ArrayList<>();
        Button addRow=button("＋ 添加章节",()->appendChapterRow(rowsHost,rows,"","",""));add(body,addRow,8);
        add(body,button("清空章节 · 按单章导出",()->{rows.clear();rowsHost.removeAllViews();
            toast("已清空章节；保存后将按单章导出");}),6);
        String draft=chapterDraft;
        if(rebuild&&!manualChapters&&session.snapshot().report!=null){StringBuilder suggested=new StringBuilder();
            for(PdfEpubExporter.ChapterPreview chapter:session.snapshot().report.chapterPreviews)
                suggested.append(chapter.sourcePage+1).append('|').append(chapter.title).append('\n');
            draft=suggested.toString();}
        if(draft!=null)for(String line:draft.split("\\r?\\n")){if(line.trim().isEmpty())continue;
            String[] fields=line.split("\\|",-1);if(fields.length>=2)
                appendChapterRow(rowsHost,rows,fields[0],fields[1],fields.length>=3?fields[2]:"");}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(rebuild?"调整章节并重建":"章节设置")
                .setView(scroller).setNeutralButton("恢复自动目录",(d,w)->{
                    manualChapters=false;chapterDraft=null;if(rebuild)start(false);else toast("已恢复自动识别目录");})
                .setNegativeButton("取消",null).setPositiveButton(rebuild?"重建 EPUB":"保存章节",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            try{StringBuilder draftValue=new StringBuilder();for(ChapterRow row:rows){
                    String page=row.page.getText().toString().trim(),label=row.title.getText().toString().trim(),
                            anchor=row.anchor.getText().toString().trim();
                    if(label.contains("|")||anchor.contains("|"))throw new IllegalArgumentException("标题和定位文字不能包含竖线");
                    draftValue.append(page).append('|').append(label).append('|').append(anchor).append('\n');}
                int first=allPages.isChecked()?0:Integer.parseInt(from.getText().toString().trim())-1;
                int last=allPages.isChecked()?item.pages:Integer.parseInt(to.getText().toString().trim());
                parseHeadings(draftValue.toString(),first,last);
                manualChapters=true;chapterDraft=draftValue.toString();dialog.dismiss();
                if(rebuild)start(false);else toast(rows.isEmpty()?"已设置单章导出":"已保存章节设置");
            }catch(Exception bad){toast("请检查章节页码与标题："+bad.getMessage());}
        }));dialog.show();
    }
    private void appendChapterRow(LinearLayout host,List<ChapterRow> rows,String pageValue,String titleValue,String anchorValue){
        LinearLayout card=card();add(host,card,7);LinearLayout header=row();add(card,header,0);
        EditText page=pageField("原 PDF 页码");page.setText(pageValue);
        EditText label=field("章节标题");label.setText(titleValue);
        header.addView(page,new LinearLayout.LayoutParams(0,dp(48),.9f));
        header.addView(label,new LinearLayout.LayoutParams(0,dp(48),1.8f));
        EditText anchor=field("此章从页面哪句开始（可选）");anchor.setText(anchorValue);
        anchor.setVisibility(anchorValue.isEmpty()?View.GONE:View.VISIBLE);
        add(card,button("页内定位（高级，可选）",()->anchor.setVisibility(anchor.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE)),6);
        add(card,anchor,3);ChapterRow entry=new ChapterRow(page,label,anchor);rows.add(entry);
        add(card,button("移除此章",()->{rows.remove(entry);host.removeView(card);}),4);
    }
    private List<EpubBook.Heading> parseHeadings(int first,int last){return manualChapters?parseHeadings(chapterDraft,first,last):null;}
    private static List<EpubBook.Heading> parseHeadings(String draft,int first,int last){
        ArrayList<EpubBook.Heading> result=new ArrayList<>();if(draft==null||draft.trim().isEmpty())return result;
        int previous=-1;java.util.HashSet<String> cuts=new java.util.HashSet<>();
        for(String line:draft.split("\\r?\\n")){if(line.trim().isEmpty())continue;
            String[] parts=line.split("\\|",-1);if(parts.length<2||parts.length>3)throw new IllegalArgumentException("每行需写 页码|标题|可选定位文字");
            String number=parts[0].trim().replace("第","").replace("页","").trim();int page;
            try{page=Integer.parseInt(number)-1;}catch(NumberFormatException bad){throw new IllegalArgumentException("页码必须是数字");}
            String label=parts[1].trim(),anchor=parts.length==3?parts[2].trim():"";
            if(page<first||page>=last||page<previous)throw new IllegalArgumentException("章节页码须在所选范围内且递增");
            if(label.isEmpty()||label.length()>120||anchor.length()>120)throw new IllegalArgumentException("标题或定位文字长度无效");
            if(page==previous&&anchor.isEmpty())throw new IllegalArgumentException("同页的下一章须填写页内定位句");
            if(!cuts.add(page+"\n"+anchor))throw new IllegalArgumentException("同一页的章节定位不能重复");
            result.add(new EpubBook.Heading(label,page,anchor));previous=page;
            if(result.size()>2000)throw new IllegalArgumentException("章节过多");
        }return result;
    }
    private static String formatHeadings(List<EpubBook.Heading> headings){StringBuilder b=new StringBuilder();
        for(EpubBook.Heading h:headings){b.append(h.sourcePage+1).append('|').append(h.title);
            if(h.anchorText!=null&&!h.anchorText.isEmpty())b.append('|').append(h.anchorText);b.append('\n');}return b.toString();}
    private void startService(){try{Intent intent=new Intent(this,EpubExportService.class);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
        }catch(RuntimeException error){if(session!=null)session.interrupt();
            toast("后台服务不可用；任务已中断，可在导出中心继续");return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                !=android.content.pm.PackageManager.PERMISSION_GRANTED
                &&!getPreferences(MODE_PRIVATE).getBoolean("epubNotificationAsked",false)){
            getPreferences(MODE_PRIVATE).edit().putBoolean("epubNotificationAsked",true).apply();
            try{requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},906);}
            catch(RuntimeException ignored){/* Export remains visible in the center. */}
        }
    }
    private void start(boolean sampleOnly){
        if(session!=null&&session.snapshot().state==EpubConversionCoordinator.State.RUNNING){toast("当前转换仍在进行");return;}
        String bookTitle=title.getText().toString().trim();if(bookTitle.isEmpty()){title.setError("请填写标题");return;}
        int first=1,last=item.pages;
        if(!allPages.isChecked())try{first=Integer.parseInt(from.getText().toString().trim());last=Integer.parseInt(to.getText().toString().trim());
            if(first<1||last<first||last>item.pages)throw new NumberFormatException();
        }catch(NumberFormatException invalid){to.setError("请输入 1 到 "+item.pages+" 内的页码");return;}
        if(sampleOnly)last=Math.min(last,first+2);
        List<EpubBook.Heading> headings;
        try{headings=parseHeadings(first-1,last);}catch(IllegalArgumentException bad){toast(bad.getMessage());return;}
        try{
            EpubConversionCoordinator.Session next=EpubConversionCoordinator.start(getApplicationContext(),item.id,bookTitle,
                author.getText().toString(),first-1,last,
                sampleOnly?EpubConversionCoordinator.Kind.SAMPLE:EpubConversionCoordinator.Kind.FULL,
                coverFirstPage.isChecked(),deskew.isChecked(),headings);
            if(session!=null)session.detach(listener);
            if(saveJob!=null){saveJob.detach(saveListener);saveJob.releaseWhenDone();saveJob=null;saveJobId=null;}
            session=next;
            savedUri=null;savedSessionId=null;chapters.setAdapter(null);previewText.setText("正在转换…");
            warnings.setText("");warningDetails.setText("");warningDetails.setVisibility(View.GONE);
            warningsToggle.setVisibility(View.GONE);suggestedOcrPage=first-1;
            session.attach(listener);render(session.snapshot());
            startService();
        }catch(IllegalStateException|IllegalArgumentException busy){toast(busy.getMessage());}
    }
    private void changed(EpubConversionCoordinator.Session origin,EpubConversionCoordinator.Snapshot snapshot){
        if(destroyed||session!=origin)return;render(snapshot);
    }
    private void render(EpubConversionCoordinator.Snapshot snapshot){
        boolean running=snapshot!=null&&snapshot.state==EpubConversionCoordinator.State.RUNNING;
        boolean done=snapshot!=null&&snapshot.state==EpubConversionCoordinator.State.COMPLETED;
        getWindow().setFlags(running?WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON:0,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sample.setEnabled(!running);convert.setEnabled(!running);cancel.setVisibility(running?View.VISIBLE:View.GONE);
        progress.setVisibility(running?View.VISIBLE:View.GONE);
        continueTask.setVisibility(snapshot!=null&&(snapshot.state==EpubConversionCoordinator.State.INTERRUPTED
                ||snapshot.state==EpubConversionCoordinator.State.FAILED
                ||snapshot.state==EpubConversionCoordinator.State.CANCELED)?View.VISIBLE:View.GONE);
        continueTask.setText(snapshot!=null&&snapshot.state==EpubConversionCoordinator.State.CANCELED?"重新开始任务":"继续中断任务");
        editChapters.setEnabled(done);
        save.setEnabled(done&&snapshot.kind==EpubConversionCoordinator.Kind.FULL);
        if(done&&savedUri==null&&session!=null&&!session.savedUri().isEmpty()){
            savedSessionId=session.id();savedUri=Uri.parse(session.savedUri());}
        share.setEnabled(done&&snapshot.id.equals(savedSessionId)&&savedUri!=null);
        ocr.setEnabled(!running&&new File(getFilesDir(),item.id+".pdf").isFile());
        if(snapshot==null)return;
        if(running){
            int pct=snapshot.total<=0?0:(int)(100L*snapshot.completed/snapshot.total);
            progress.setProgress(Math.max(0,Math.min(100,pct)));
            status.setText((snapshot.cancelRequested?"正在取消…":snapshot.stage)+" · " + snapshot.completed+"/"+snapshot.total+
                    (snapshot.pageIndex>=0?" · 第 "+(snapshot.pageIndex+1)+" 页":""));
            return;
        }
        if(snapshot.state==EpubConversionCoordinator.State.CANCELED){status.setText("转换已取消；可重新试转或转换整本。");return;}
        if(snapshot.state==EpubConversionCoordinator.State.INTERRUPTED){status.setText(snapshot.stage);return;}
        if(snapshot.state==EpubConversionCoordinator.State.FAILED){
            status.setText("转换失败："+message(snapshot.error)+"。可重试；已完成的 OCR 页可复用。 ");return;
        }
        PdfEpubExporter.Report report=snapshot.report;
        status.setText((snapshot.kind==EpubConversionCoordinator.Kind.SAMPLE?"试转完成":"整本转换完成")+
                " · "+report.pages+" 页 · "+report.characters+" 字 · "+report.chapters+" 个目录项"+
                (snapshot.error==null?"":" · 任务记录未写入，请立即保存"));
        StringBuilder details=new StringBuilder();StringBuilder flagged=new StringBuilder();
        details.append("OCR ").append(report.ocrPages).append(" 页；低置信度 ").append(report.lowConfidencePages)
                .append(" 页；空白 ").append(report.blankPages).append(" 页。请核对扫描文字。");
        int images=0,unpreserved=0;boolean suggested=false;
        for(PdfEpubExporter.PageWarning warning:report.warnings){
            String warningKind=warning.kind.name();
            if("PAGE_IMAGE_PRESERVED".equals(warningKind)){images++;continue;}
            if(warning.kind==PdfEpubExporter.PageWarning.Kind.IMAGE_NOT_PRESERVED){unpreserved++;continue;}
            if(warning.kind==PdfEpubExporter.PageWarning.Kind.OCR_USED)continue;
            if(!suggested){suggestedOcrPage=warning.pageIndex;suggested=true;}
            flagged.append("第 ").append(warning.pageIndex+1).append(" 页：")
                    .append(warning.detail==null?warning.kind.name():warning.detail).append('\n');
        }
        if(images>0)details.append("\n").append(images).append(" 页图片保留为固定页面，不能像正文文字一样重排。");
        if(unpreserved>0)details.append("\n").append(unpreserved).append(" 页 PDF 插图未嵌入 EPUB。");
        if(flagged.length()>0)details.append("\n需核对的页码可展开查看，再前往 OCR 校订。");
        warnings.setText(details.toString());
        warningDetails.setText(flagged.length()==0?"暂无低置信度、空白或 OCR 失败页。":flagged.toString());
        warningsToggle.setVisibility(flagged.length()==0?View.GONE:View.VISIBLE);
        showChapters(snapshot);
    }
    private void showChapters(EpubConversionCoordinator.Snapshot snapshot){
        ArrayList<String> labels=new ArrayList<>();
        for(PdfEpubExporter.ChapterPreview chapter:snapshot.report.chapterPreviews)
            labels.add(chapter.title+" · 第 "+(chapter.sourcePage+1)+" 页");
        if(labels.isEmpty())labels.add("正文");
        List<String> entries=chapterEntries(snapshot.epub);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels);
        chapters.setAdapter(adapter);
        chapters.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){
                int generation=++chapterGeneration;
                if(position>=entries.size()){
                    if(position<snapshot.report.chapterPreviews.size())previewText.setText(snapshot.report.chapterPreviews.get(position).excerpt);
                    return;
                }
                previewText.setText("正在打开章节…");
                IO.execute(()->{
                    String html;
                    try{html=readChapter(snapshot.epub,entries.get(position));}
                    catch(IOException error){html="章节读取失败："+message(error);}
                    final String content=html;
                    MAIN.post(()->{if(destroyed||generation!=chapterGeneration||session==null||!snapshot.id.equals(session.id()))return;
                        previewText.setText(Build.VERSION.SDK_INT>=24?
                                Html.fromHtml(content,Html.FROM_HTML_MODE_LEGACY):Html.fromHtml(content));
                    });
                });
            }
        });
    }
    private static List<String> chapterEntries(File epub){
        ArrayList<String> names=new ArrayList<>();
        try(ZipFile zip=new ZipFile(epub)){
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()){
                String name=entries.nextElement().getName();String lower=name.toLowerCase(java.util.Locale.ROOT);
                if((lower.endsWith(".xhtml")||lower.endsWith(".html"))&&!lower.contains("nav")&&!lower.contains("toc")&&!lower.contains("cover"))names.add(name);
            }
        }catch(IOException ignored){}Collections.sort(names);return names;
    }
    private static String readChapter(File epub,String name)throws IOException{
        try(ZipFile zip=new ZipFile(epub)){
            ZipEntry entry=zip.getEntry(name);if(entry==null)throw new IOException("章节不存在");
            try(InputStream input=zip.getInputStream(entry);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){
                    if(out.size()+n>512*1024){out.write(buffer,0,512*1024-out.size());break;}out.write(buffer,0,n);
                }
                String html=new String(out.toByteArray(),"UTF-8");
                int body=html.toLowerCase(java.util.Locale.ROOT).indexOf("<body");
                if(body>=0){int start=html.indexOf('>',body);int end=html.toLowerCase(java.util.Locale.ROOT).indexOf("</body>",start);
                    if(start>=0)html=html.substring(start+1,end<0?html.length():end);
                }
                return html;
            }
        }
    }
    private void chooseSave(){
        EpubConversionCoordinator.Snapshot current=session==null?null:session.snapshot();
        if(current==null||current.state!=EpubConversionCoordinator.State.COMPLETED||current.kind!=EpubConversionCoordinator.Kind.FULL)return;
        pendingSaveSessionId=current.id;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/epub+zip");intent.putExtra(Intent.EXTRA_TITLE,safeFileName(session.title())+".epub");
        try{startActivityForResult(intent,CREATE_EPUB);}catch(ActivityNotFoundException error){toast("没有可用的系统文件选择器");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=CREATE_EPUB)return;
        String bound=pendingSaveSessionId;pendingSaveSessionId=null;
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        EpubConversionCoordinator.Session original=EpubConversionCoordinator.find(bound);
        EpubConversionCoordinator.Snapshot source=original==null?null:original.snapshot();
        if(!EpubConversionCoordinator.canSave(bound,source)){
            toast("原转换结果已失效，请重新转换后保存");return;
        }
        try{getContentResolver().takePersistableUriPermission(data.getData(),Intent.FLAG_GRANT_READ_URI_PERMISSION);}
        catch(SecurityException|IllegalArgumentException ignored){/* Provider may grant only a transient URI. */}
        if(saveJob!=null){saveJob.detach(saveListener);saveJob.forgetRecord();}
        try{saveJob=SaveJob.start(getApplicationContext(),source.id,source.epub,data.getData());}
        catch(IllegalStateException stale){toast("转换任务已失效，请从导出中心查看");return;}
        saveJobId=saveJob.id;
        saveJob.attach(saveListener);status.setText("正在保存电子书…");save.setEnabled(false);
    }
    private void saveChanged(SaveJob job){
        if(destroyed||saveJob!=job)return;
        if(session==null||!session.id().equals(job.sessionId)){if(job.finished)job.releaseWhenDone();return;}
        if(!job.finished)return;
        save.setEnabled(true);
        if(job.error!=null){status.setText("保存失败："+message(job.error)+"。私有转换结果仍在，可重新选择位置保存。");return;}
        savedSessionId=job.sessionId;savedUri=job.destination;
        if(session!=null)session.markSaved(savedUri.toString());
        status.setText(job.metadataSaved?"EPUB 已保存，可分享或重新保存一份。":"EPUB 已保存；任务记录写入失败，请保管已保存文件。");
        share.setEnabled(session!=null&&session.id().equals(savedSessionId));
    }
    private void shareSaved(){
        if(session==null||savedUri==null||!session.id().equals(savedSessionId))return;
        try(android.os.ParcelFileDescriptor probe=getContentResolver().openFileDescriptor(savedUri,"r")){
            if(probe==null)throw new IOException("文件不可访问");
            startActivity(Intent.createChooser(ReaderActions.share(savedUri,"application/epub+zip"),"分享 EPUB 电子书"));
        }catch(Exception error){toast("已保存文件不可访问，请重新保存一份");}
    }
    private void openOcr(){Intent intent=new Intent(this,OcrActivity.class);
        intent.putExtra(OcrActivity.EXTRA_DOC_ID,item.id);intent.putExtra(OcrActivity.EXTRA_PAGE,
                session==null?item.page:Math.max(0,suggestedOcrPage));startActivity(intent);
    }
    private void askCancel(){if(session==null)return;new AlertDialog.Builder(this).setTitle("取消转换？")
            .setMessage("将停止当前任务并清理临时 EPUB；已识别页的私有 OCR 缓存可供重试。")
            .setNegativeButton("继续转换",null).setPositiveButton("取消任务",(dialog,which)->session.cancel()).show();}
    @Override public void onBackPressed(){
        super.onBackPressed();
    }
    @Override protected void onSaveInstanceState(Bundle out){
        if(item==null){super.onSaveInstanceState(out);return;}
        out.putString("title",title.getText().toString());out.putString("author",author.getText().toString());
        out.putString("from",from.getText().toString());out.putString("to",to.getText().toString());
        out.putBoolean("all",allPages.isChecked());out.putBoolean("cover",coverFirstPage.isChecked());out.putFloat("previewSp",previewSp);
        out.putBoolean("deskew",deskew.isChecked());out.putString("chapterDraft",chapterDraft);out.putBoolean("manualChapters",manualChapters);
        out.putInt("suggestedOcrPage",suggestedOcrPage);
        if(session!=null)out.putString("sessionId",session.id());
        out.putString("pendingSaveSessionId",pendingSaveSessionId);out.putString("savedSessionId",savedSessionId);
        out.putParcelable("savedUri",savedUri);out.putString("saveJobId",saveJobId);super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy(){destroyed=true;chapterGeneration++;
        if(session!=null)session.detach(listener);if(saveJob!=null){saveJob.detach(saveListener);if(isFinishing())saveJob.releaseWhenDone();}
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);super.onDestroy();}
    private void releaseIfUnused(String id){
        if(id==null||id.equals(pendingSaveSessionId))return;
        if(saveJob!=null&&!saveJob.finished&&id.equals(saveJob.sessionId)){saveJob.releaseWhenDone();return;}
        EpubConversionCoordinator.releaseWhenDone(id);
    }
    private void setPreviewSize(float value){previewSp=Math.max(12,Math.min(28,value));previewText.setTextSize(previewSp);}
    private static String suggestTitle(String name){return name==null?"未命名电子书":name.replaceFirst("(?i)\\.pdf$","");}
    private static String safeFileName(String title){String name=title.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_");
        return name.isEmpty()?"晴阅电子书":name.length()>100?name.substring(0,100):name;}
    private static String message(Throwable error){String value=error==null?null:error.getMessage();return value==null||value.trim().isEmpty()?"请检查存储空间后重试":value;}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_SHORT).show();}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private TextView text(String value,int sp,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);return t;}
    private EditText field(String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);e.setTextColor(AppColors.INK);e.setHintTextColor(AppColors.MUTED);e.setTextSize(15);return e;}
    private EditText pageField(String hint){EditText e=field(hint);e.setInputType(InputType.TYPE_CLASS_NUMBER);return e;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
    private LinearLayout card(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setPadding(dp(16),dp(16),dp(16),dp(16));
        GradientDrawable bg=new GradientDrawable();bg.setColor(AppColors.SURFACE);bg.setCornerRadius(dp(18));bg.setStroke(dp(1),AppColors.BORDER);v.setBackground(bg);return v;}
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(AppColors.ON_ACCENT);
        GradientDrawable bg=new GradientDrawable();bg.setColor(AppColors.ACCENT);bg.setCornerRadius(dp(10));b.setBackground(bg);b.setOnClickListener(v->action.run());return b;}
    private void add(LinearLayout host,View child,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);host.addView(child,p);}

    /** A SAF write owns the exact successful session file selected before launching the picker. */
    private static final class SaveJob {
        interface Listener { void changed(SaveJob job); }
        private static final ConcurrentHashMap<String,SaveJob> JOBS=new ConcurrentHashMap<>();
        final String id=java.util.UUID.randomUUID().toString(),sessionId;
        final Uri destination;final File source;final android.content.Context app;
        final EpubConversionCoordinator.Session leasedSession;
        final ArrayList<WeakReference<Listener>> listeners=new ArrayList<>();
        volatile boolean finished;volatile Throwable error;volatile boolean metadataSaved=true;
        private volatile boolean releaseAfterFinish;
        private volatile boolean forgetRecordAfterFinish;
        private SaveJob(android.content.Context app,String sessionId,File source,Uri destination,EpubConversionCoordinator.Session leasedSession){
            this.app=app;this.sessionId=sessionId;this.source=source;this.destination=destination;this.leasedSession=leasedSession;
        }
        static SaveJob find(String id){return id==null?null:JOBS.get(id);}
        static SaveJob start(android.content.Context context,String sessionId,File source,Uri destination){
            EpubConversionCoordinator.Session session=EpubConversionCoordinator.find(sessionId);
            if(session==null||!session.acquireSaveLease())throw new IllegalStateException("任务不可保存");
            SaveJob job=new SaveJob(context.getApplicationContext(),sessionId,source,destination,session);
            JOBS.put(job.id,job);IO.execute(job::run);return job;
        }
        synchronized void attach(Listener listener){detach(listener);listeners.add(new WeakReference<>(listener));notifyOne(listener);}
        synchronized void detach(Listener listener){for(int i=listeners.size()-1;i>=0;i--){
            Listener old=listeners.get(i).get();if(old==null||old==listener)listeners.remove(i);
        }}
        synchronized void forgetRecord(){forgetRecordAfterFinish=true;if(finished)JOBS.remove(id,this);}
        synchronized void releaseWhenDone(){releaseAfterFinish=true;if(finished)release();}
        private void release(){JOBS.remove(id,this);EpubConversionCoordinator.releaseWhenDone(sessionId);}
        private void notifyOne(Listener listener){MAIN.post(()->listener.changed(this));}
        private void publish(){synchronized(this){for(int i=listeners.size()-1;i>=0;i--)if(listeners.get(i).get()==null)listeners.remove(i);
            for(WeakReference<Listener> ref:listeners){Listener listener=ref.get();if(listener!=null)notifyOne(listener);}}}
        private void run(){
            try{
                EpubConversionCoordinator.Session session=EpubConversionCoordinator.find(sessionId);
                if(session==null||!session.verifyOutput())throw new IOException("私有 EPUB 已变化，请继续任务重建");
                try(FileInputStream in=new FileInputStream(source);OutputStream out=app.getContentResolver().openOutputStream(destination,"w")){
                if(out==null)throw new IOException("无法写入所选位置");byte[] bytes=new byte[65536];int n;
                while((n=in.read(bytes))!=-1)out.write(bytes,0,n);out.flush();
                }
            }catch(Throwable failure){error=failure;try{app.getContentResolver().delete(destination,null,null);}catch(Exception ignored){}}
            finally{try{if(error==null)metadataSaved=leasedSession.markSaved(destination.toString());}
                catch(RuntimeException failure){error=failure;}
                finally{leasedSession.releaseSaveLease();}
                finished=true;publish();if(releaseAfterFinish)release();
                else if(forgetRecordAfterFinish)JOBS.remove(id,this);}
        }
    }
}
