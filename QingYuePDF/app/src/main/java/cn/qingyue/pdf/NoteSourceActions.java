package cn.qingyue.pdf;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;

/** Inserts local reader links into notes without editing an open note behind its Activity. */
public final class NoteSourceActions {
    public static final String EXTRA_SOURCE="noteSourceUri";
    private NoteSourceActions(){}
    public static Dialog show(Activity activity,ReaderSourceLink source){
        if(activity==null||source==null)throw new IllegalArgumentException("Missing source");
        TextView heading=new TextView(activity);heading.setText("保存到笔记\n"+label(source)+"\n仅本机有效；长摘录建议使用无限画布");heading.setTextSize(16);heading.setGravity(Gravity.CENTER_VERTICAL);heading.setPadding(48,24,32,16);
        AlertDialog dialog=new AlertDialog.Builder(activity).setCustomTitle(heading)
                .setItems(new String[]{"新建无限画布笔记","新建 A4 笔记","插入已有笔记","复制来源链接"},(d,which)->{
                    if(which==0||which==1)create(activity,source,which==0?"infinite":"A4");
                    else if(which==2)chooseExisting(activity,source);
                    else {ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText("晴阅来源",source.toUri()));Toast.makeText(activity,"已复制来源链接",Toast.LENGTH_SHORT).show();}
                }).create();dialog.show();return dialog;
    }
    public static String label(ReaderSourceLink source){
        String name=source.documentName.trim().isEmpty()?"来源文档":source.documentName.replace('\n',' ').replace('\r',' ');
        if(name.length()>80)name=name.substring(0,80)+"…";
        String quote=source.quote.trim().replaceAll("\\s+"," ");if(quote.length()>220)quote=quote.substring(0,220)+"…";
        return "来源 · "+name+" · 第 "+(source.page+1)+" 页"+(quote.isEmpty()?"":"\n摘录："+quote);
    }
    public static String body(ReaderSourceLink source){
        String name=source.documentName.trim().isEmpty()?"来源文档":source.documentName.replace('\n',' ').replace('\r',' ');
        if(name.length()>120)name=name.substring(0,120)+"…";
        String quote=source.quote.trim();
        return "来源 · "+name+" · 第 "+(source.page+1)+" 页"+(quote.isEmpty()?"":"\n\n摘录：\n"+quote);
    }
    private static void create(Activity activity,ReaderSourceLink source,String paper){
        if("A4".equals(paper)){
            NoteDocument sheet=NoteDocument.create("blank","A4");double width=Math.min(360,sheet.width-48);
            if(NoteTextLayout.height(body(source),17,0xff36302b,width)>sheet.height-48){Toast.makeText(activity,"摘录在 A4 纸张中放不下，请选择无限画布",Toast.LENGTH_LONG).show();return;}
        }
        String id=UUID.randomUUID().toString(),name=source.documentName.trim().isEmpty()?"来源笔记":source.documentName+" · 笔记";
        if(name.length()>512)name=name.substring(0,512);
        boolean created=false,committed=false;
        try {
            NoteStore.create(activity,id,"blank",paper);created=true;
            LibraryItem item=new LibraryItem();item.id=id;item.name=name;item.originalName=name;item.type="note";item.format="NOTE";item.template="blank";item.paper=paper;item.pages=1;item.created=item.modified=item.opened=System.currentTimeMillis();
            boolean saved=false;
            for(int attempt=0;attempt<3&&!saved;attempt++){
                LibraryStore.State state=LibraryStore.load(activity);if(!state.allowSave)throw new IOException(state.warning);
                ArrayList<LibraryItem> next=new ArrayList<>(state.items);next.add(item);
                try{LibraryStore.saveIfRevision(activity,state.revision,next);saved=true;}catch(LibraryStore.StaleIndexException retry){if(attempt==2)throw retry;}
            }
            committed=true;open(activity,item,source);
        }catch(IOException|RuntimeException failure){if(created&&!committed)try{NoteStore.delete(activity,id);}catch(IOException ignored){}Toast.makeText(activity,"无法创建笔记："+failure.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private static void chooseExisting(Activity activity,ReaderSourceLink source){
        LibraryStore.State state=LibraryStore.load(activity);ArrayList<LibraryItem> notes=new ArrayList<>();
        for(LibraryItem item:state.items)if(item.isNote()&&!item.isTrashed())notes.add(item);
        if(notes.isEmpty()){Toast.makeText(activity,"还没有笔记，请先新建笔记",Toast.LENGTH_SHORT).show();return;}
        String[] names=new String[notes.size()];for(int i=0;i<names.length;i++)names[i]=notes.get(i).name;
        new AlertDialog.Builder(activity).setTitle("选择已有笔记").setItems(names,(d,which)->open(activity,notes.get(which),source)).setNegativeButton("取消",null).show();
    }
    private static void open(Activity activity,LibraryItem note,ReaderSourceLink source){
        activity.startActivity(new Intent(activity,NoteActivity.class).putExtra("id",note.id).putExtra("name",note.name).putExtra(EXTRA_SOURCE,source.toUri()));
    }
}
