package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Collection;
import java.util.Map;
import java.util.ArrayList;

/** Always-visible library navigation. Folder names are document categories, not disk paths. */
final class LibrarySidebar {
    static final String UNFILED="\u0000unfiled";
    interface Listener { void selected(String folder, boolean trash); }
    private final Context context;
    private final Listener listener;
    private final boolean wide;
    private final int density;

    LibrarySidebar(Context context, boolean wide, Listener listener) {
        this.context=context;this.wide=wide;this.listener=listener;
        density=(int)(context.getResources().getDisplayMetrics().density*1000);
    }
    private int dp(int value){return (value*density+500)/1000;}
    private GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(!AppColors.isClassic())d.setStroke(dp(1),color==AppColors.ACCENT?AppColors.ACCENT:AppColors.BORDER);return d;}
    View build(Collection<LibraryItem> docs,String selectedFolder,boolean trashMode){
        return build(docs,new ArrayList<>(),selectedFolder,trashMode);
    }
    View build(Collection<LibraryItem> docs,Collection<LibraryFolder> folders,String selectedFolder,boolean trashMode){
        LibrarySidebarModel counts=LibrarySidebarModel.from(docs,folders);
        LinearLayout items=new LinearLayout(context);items.setOrientation(wide?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        items.setGravity(wide?Gravity.TOP:Gravity.CENTER_VERTICAL);
        if(wide){items.setPadding(dp(10),dp(15),dp(10),dp(14));
            TextView title=new TextView(context);title.setText("资料库");title.setTextColor(AppColors.INK);title.setTextSize(17);title.setTypeface(null,Typeface.BOLD);title.setPadding(dp(12),0,0,dp(13));items.addView(title);
        }else items.setPadding(0,0,dp(6),0);
        add(items,"全部资料",counts.all,!trashMode&&selectedFolder.isEmpty(),()->listener.selected("",false));
        add(items,"未分类",counts.unfiled,!trashMode&&UNFILED.equals(selectedFolder),()->listener.selected(UNFILED,false));
        if(wide){TextView label=new TextView(context);label.setText("文件夹");label.setTextSize(12);label.setTypeface(null,Typeface.BOLD);label.setTextColor(AppColors.MUTED);label.setPadding(dp(12),dp(16),0,dp(8));items.addView(label);}
        for(LibraryFolder folder:counts.folderTree){
            if(!folder.parentId.isEmpty())continue;
            addFolder(items,folder,counts,selectedFolder,trashMode,0);
        }
        add(items,"最近删除",counts.trashed,trashMode,()->listener.selected("",true));
        if(wide){ScrollView scroll=new ScrollView(context);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);scroll.addView(items);scroll.setBackground(bg(AppColors.SURFACE,20));return scroll;}
        HorizontalScrollView scroll=new HorizontalScrollView(context);scroll.setHorizontalScrollBarEnabled(false);scroll.addView(items);return scroll;
    }
    private void addFolder(LinearLayout items,LibraryFolder folder,LibrarySidebarModel counts,String selectedFolder,boolean trashMode,int depth){
        if(depth>64)return;
        StringBuilder label=new StringBuilder();for(int i=0;i<depth;i++)label.append("  ");
        label.append(depth==0?(AppColors.isClassic()?"📁 ":"▱ "):"↳ ").append(folder.name);
        add(items,label.toString(),counts.folders.containsKey(folder.id)?counts.folders.get(folder.id):0,
                !trashMode&&folder.id.equals(selectedFolder),()->listener.selected(folder.id,false));
        for(LibraryFolder child:counts.folderTree)if(folder.id.equals(child.parentId))addFolder(items,child,counts,selectedFolder,trashMode,depth+1);
    }
    private void add(LinearLayout items,String name,int count,boolean active,Runnable action){
        TextView entry=new TextView(context);entry.setText(name+"  "+count);entry.setSingleLine(true);entry.setTextSize(wide?14:12);entry.setTypeface(null,active?Typeface.BOLD:Typeface.NORMAL);
        entry.setTextColor(active?AppColors.ON_ACCENT:AppColors.INK);entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setPadding(dp(wide?12:13),0,dp(wide?12:13),0);entry.setMinHeight(dp(wide?43:38));entry.setBackground(bg(active?AppColors.ACCENT:wide||!AppColors.isClassic()?AppColors.SURFACE:AppColors.SOFT,AppColors.isClassic()?13:wide?12:19));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(wide?-1:-2,dp(wide?43:38));
        if(wide)p.bottomMargin=dp(4);else p.rightMargin=dp(7);items.addView(entry,p);entry.setOnClickListener(v->action.run());
        entry.setContentDescription(name+"，"+count+" 项");
    }
}
