package cn.qingyue.pdf;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.*;

/** Compact private export queue; refresh updates rows in place without moving scroll or focus. */
public final class ExportCenterActivity extends ThemedActivity {
    private static final class Row {
        final TextView status,count;final ProgressBar bar;
        Row(TextView status,TextView count,ProgressBar bar){this.status=status;this.count=count;this.bar=bar;}
    }
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<String,Row> rows=new HashMap<>();
    private ScrollView scroll;private LinearLayout list;private Button[] tabs;
    private int filter;private String layoutKey="";private boolean visible;
    private final Runnable refresh=new Runnable(){@Override public void run(){if(!visible)return;render();handler.postDelayed(this,1200);}};
    @Override protected void onCreate(Bundle state){super.onCreate(state);EpubConversionCoordinator.initialize(this);
        if(state!=null)filter=state.getInt("filter",0);build();}
    @Override protected void onResume(){super.onResume();visible=true;handler.post(refresh);}
    @Override protected void onPause(){visible=false;handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("filter",filter);super.onSaveInstanceState(out);}
    private void build(){scroll=new ScrollView(this);scroll.setBackgroundColor(AppColors.BACKGROUND);
        LinearLayout body=column();body.setPadding(dp(18),dp(18),dp(18),dp(24));scroll.addView(body);
        setContentView(scroll);UiSafeArea.install(this,scroll,false);
        TextView heading=text("导出中心",23,AppColors.INK);heading.setTypeface(null,1);body.addView(heading);
        add(body,text("本机导出任务 · 离开页面后仍可查看",12,AppColors.MUTED),5);
        LinearLayout tabRow=horizontal();tabs=new Button[4];String[] names={"全部","进行中","已完成","需处理"};
        for(int i=0;i<4;i++){final int selected=i;Button tab=button(names[i],false,()->{
            filter=selected;layoutKey="";styleTabs();render();});tab.setTextSize(12);tabs[i]=tab;
            tabRow.addView(tab,new LinearLayout.LayoutParams(0,dp(42),1));}
        add(body,tabRow,17);styleTabs();list=column();add(body,list,10);render();}
    private void styleTabs(){for(int i=0;i<tabs.length;i++)style(tabs[i],i==filter);}
    private void render(){if(list==null)return;List<EpubConversionCoordinator.Session> visibleSessions=new ArrayList<>();
        StringBuilder key=new StringBuilder().append(filter);
        for(EpubConversionCoordinator.Session session:EpubConversionCoordinator.sessions()){
            EpubConversionCoordinator.Snapshot s=session.snapshot();int bucket=s.state==EpubConversionCoordinator.State.RUNNING?1:
                    s.state==EpubConversionCoordinator.State.COMPLETED?2:3;
            if(filter!=0&&filter!=bucket)continue;visibleSessions.add(session);
            key.append('|').append(session.id()).append(':').append(s.state.name());
        }
        if(!key.toString().equals(layoutKey)){
            int oldY=scroll.getScrollY();list.removeAllViews();rows.clear();layoutKey=key.toString();
            if(visibleSessions.isEmpty())add(list,text(filter==0?"暂无导出任务":"这一组暂无任务",14,AppColors.MUTED),25);
            for(EpubConversionCoordinator.Session session:visibleSessions)makeCard(session);
            scroll.post(()->scroll.scrollTo(0,oldY));
        }
        for(EpubConversionCoordinator.Session session:visibleSessions){Row row=rows.get(session.id());if(row==null)continue;
            EpubConversionCoordinator.Snapshot s=session.snapshot();row.status.setText(type(s)+" · "+status(s));
            row.count.setText("已处理 "+s.completed+" / "+s.total+" 页"+
                    (s.pageIndex>=0?" · 原 PDF 第 "+(s.pageIndex+1)+" 页":""));
            row.bar.setMax(Math.max(1,s.total));row.bar.setProgress(Math.max(0,Math.min(s.total,s.completed)));
        }
    }
    private void makeCard(EpubConversionCoordinator.Session session){EpubConversionCoordinator.Snapshot s=session.snapshot();
        LinearLayout card=column();card.setPadding(dp(15),dp(14),dp(15),dp(13));
        GradientDrawable bg=new GradientDrawable();bg.setColor(AppColors.SURFACE);bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1),AppColors.BORDER);card.setBackground(bg);add(list,card,8);
        TextView title=text(session.title(),16,AppColors.INK);title.setTypeface(null,1);add(card,title,0);
        TextView status=text("",12,AppColors.ACCENT_DARK);add(card,status,4);
        TextView count=text("",12,AppColors.MUTED);add(card,count,8);
        ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);add(card,bar,7);
        rows.put(session.id(),new Row(status,count,bar));
        if(s.error!=null)add(card,text(s.error.getMessage()==null?"请查看任务详情":s.error.getMessage(),12,AppColors.MUTED),6);
        LinearLayout actions=horizontal();add(card,actions,9);
        action(actions,"查看",true,()->open(session,false));
        if(s.state==EpubConversionCoordinator.State.COMPLETED&&s.kind==EpubConversionCoordinator.Kind.FULL){
            action(actions,"保存",false,()->open(session,true));
            if(!session.savedUri().isEmpty())action(actions,"分享",false,()->share(session));
        }else if(s.state==EpubConversionCoordinator.State.INTERRUPTED||s.state==EpubConversionCoordinator.State.FAILED
                ||s.state==EpubConversionCoordinator.State.CANCELED){
            action(actions,s.state==EpubConversionCoordinator.State.CANCELED?"重新开始":"继续",false,
                    ()->{try{if(session.resume())startService();}catch(RuntimeException bad){showError(bad.getMessage());}render();});
        }else if(s.state==EpubConversionCoordinator.State.RUNNING){
            action(actions,"取消",false,()->new AlertDialog.Builder(this).setMessage("取消这个导出任务？")
                    .setNegativeButton("返回",null).setPositiveButton("取消任务",(d,w)->{
                        try{session.cancel();}catch(RuntimeException bad){showError(bad.getMessage());}render();}).show());
        }
        Button more=button("更多",false,()->{});more.setTextSize(12);actions.addView(more,new LinearLayout.LayoutParams(0,dp(39),.8f));
        more.setOnClickListener(v->{PopupMenu menu=new PopupMenu(this,more);
            if(s.state==EpubConversionCoordinator.State.COMPLETED&&s.kind==EpubConversionCoordinator.Kind.FULL)
                menu.getMenu().add("保存另一份").setOnMenuItemClickListener(i->{open(session,true);return true;});
            if(s.terminal())menu.getMenu().add("删除任务与私有文件").setOnMenuItemClickListener(i->{delete(session);return true;});
            menu.show();});
        card.setOnClickListener(v->open(session,false));
    }
    private void delete(EpubConversionCoordinator.Session session){new AlertDialog.Builder(this)
        .setMessage("删除此任务的源快照和私有 EPUB？已保存到外部的文件不受影响。")
        .setNegativeButton("保留",null).setPositiveButton("删除",(d,w)->{
            if(!EpubConversionCoordinator.forget(session.id()))showError("任务仍在运行、保存或文件不可删除，请稍后重试。");
            render();}).show();}
    private void share(EpubConversionCoordinator.Session session){try{
            Uri uri=Uri.parse(session.savedUri());
            try(android.os.ParcelFileDescriptor probe=getContentResolver().openFileDescriptor(uri,"r")){
                if(probe==null)throw new java.io.IOException("文件不可访问");}
            startActivity(Intent.createChooser(ReaderActions.share(uri,"application/epub+zip"),"分享 EPUB 电子书"));}
        catch(Exception stale){showError("已保存的文件不可访问，请重新保存一份。");}}
    private void open(EpubConversionCoordinator.Session s,boolean save){Intent intent=new Intent(this,PdfEpubActivity.class);
        intent.putExtra(PdfEpubActivity.EXTRA_DOC_ID,s.documentId());intent.putExtra(PdfEpubActivity.EXTRA_SESSION_ID,s.id());
        intent.putExtra(PdfEpubActivity.EXTRA_SAVE_NOW,save);startActivity(intent);}
    private void startService(){try{Intent i=new Intent(this,EpubExportService.class);
        if(android.os.Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
        catch(RuntimeException denied){for(EpubConversionCoordinator.Session s:EpubConversionCoordinator.sessions())
            if(s.snapshot().state==EpubConversionCoordinator.State.RUNNING)s.interrupt();
            showError("后台服务不可用，任务已中断，可稍后继续。");render();}}
    private String type(EpubConversionCoordinator.Snapshot s){return s.kind==EpubConversionCoordinator.Kind.SAMPLE?"试转":"整本";}
    private String status(EpubConversionCoordinator.Snapshot s){switch(s.state){
        case RUNNING:return s.cancelRequested?"正在取消":s.stage;
        case COMPLETED:return s.error==null?"已完成":"已完成 · 记录写入失败，请立即保存";
        case INTERRUPTED:return "已中断 · 可继续";
        case CANCELED:return "已取消 · 可重新开始";default:return "转换失败 · 可继续";}}
    private void showError(String message){new AlertDialog.Builder(this).setMessage(message==null?"操作失败，请重试":message)
            .setPositiveButton("知道了",null).show();}
    private void action(LinearLayout row,String label,boolean primary,Runnable run){Button b=button(label,primary,run);
        b.setTextSize(12);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(39),1);p.rightMargin=dp(5);
        row.addView(b,p);}
    private Button button(String label,boolean primary,Runnable run){Button b=new Button(this);b.setText(label);b.setAllCaps(false);
        style(b,primary);b.setOnClickListener(v->run.run());return b;}
    private void style(Button button,boolean primary){button.setTextColor(primary?AppColors.ON_ACCENT:AppColors.ACCENT_DARK);
        GradientDrawable bg=new GradientDrawable();bg.setColor(primary?AppColors.ACCENT:AppColors.SOFT);
        bg.setCornerRadius(dp(10));button.setBackground(bg);}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout horizontal(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);return v;}
    private void add(LinearLayout host,View child,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.topMargin=dp(top);host.addView(child,p);}
}
