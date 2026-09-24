package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

/** Visible rows only; page tools never sit above the editor's toolbar. */
final class NotePageSidebar extends LinearLayout {
    interface Actions { void select(int page); void move(int from,int to); void insert(int page); void append(); void close(); default void manage(int page){insert(page);} }
    private final NoteDocument document;
    private final String noteId;
    private final Actions actions;
    private final ListView list;
    private final BaseAdapter adapter;
    private final TextView title,insertButton,appendButton;
    private final IconButton up,down;
    private int previousPage=-1,previousCount=-1;
    private boolean editingEnabled=true;
    NotePageSidebar(Context context,NoteDocument document,String noteId,Actions actions){
        super(context);this.document=document;this.noteId=noteId;this.actions=actions;setOrientation(VERTICAL);setPadding(dp(6),0,dp(6),0);setBackgroundColor(AppColors.SURFACE);
        LinearLayout heading=new LinearLayout(context);heading.setGravity(Gravity.CENTER_VERTICAL);title=label("页面",12);heading.addView(title,new LayoutParams(0,dp(44),1));IconButton close=new IconButton(context,"close","收起页面栏");close.setOnClickListener(v->actions.close());heading.addView(close,new LayoutParams(dp(44),dp(44)));addView(heading);
        list=new ListView(context);list.setDivider(null);list.setDividerHeight(dp(8));list.setFastScrollEnabled(false);list.setPadding(0,dp(4),0,dp(4));
        adapter=new BaseAdapter(){public int getCount(){return document.pageCount();}public Object getItem(int p){return p;}public long getItemId(int p){return p;}
            public View getView(int page,View recycled,ViewGroup parent){
                Row row=recycled==null?new Row():(Row)recycled.getTag();row.page=page;row.preview.bindPage(page);
                int height=Math.round(Math.max(72,getWidth()-dp(24))*1.35f);if(row.preview.getLayoutParams().height!=height)row.preview.setLayoutParams(new LayoutParams(-1,height));updateRow(row);return row.card;
            }};list.setAdapter(adapter);addView(list,new LayoutParams(-1,0,1));
        LinearLayout ordering=new LinearLayout(context);ordering.setGravity(Gravity.CENTER);up=new IconButton(context,"up","将当前页上移");down=new IconButton(context,"down","将当前页下移");up.setOnClickListener(v->{int page=document.currentPage();if(page>0)actions.move(page,page-1);});down.setOnClickListener(v->{int page=document.currentPage();if(page+1<document.pageCount())actions.move(page,page+1);});ordering.addView(up,new LayoutParams(0,dp(44),1));ordering.addView(down,new LayoutParams(0,dp(44),1));addView(ordering);
        insertButton=label(document.infinite()?"画布操作":"页面操作",12);insertButton.setTextColor(AppColors.ACCENT_DARK);insertButton.setOnClickListener(v->actions.manage(document.currentPage()));addView(insertButton,new LayoutParams(-1,dp(44)));
        appendButton=label(document.infinite()?"＋ 追加独立画布":"＋ 添加草稿纸",12);appendButton.setTextColor(AppColors.ACCENT_DARK);appendButton.setBackground(NoteDialogs.shape(context,AppColors.SOFT,12));appendButton.setOnClickListener(v->actions.append());addView(appendButton,new LayoutParams(-1,dp(44)));refresh(true);
    }
    private final class Row {
        final LinearLayout card=new LinearLayout(getContext());final NotePagePreviewView preview=new NotePagePreviewView(getContext(),document,noteId,0);
        final TextView number=label("",12);final GradientDrawable border=new GradientDrawable();int page;
        Row(){card.setTag(this);card.setOrientation(VERTICAL);card.setPadding(dp(5),dp(5),dp(5),dp(3));border.setCornerRadius(dp(9));card.setBackground(border);card.addView(preview,new LayoutParams(-1,dp(120)));card.addView(number,new LayoutParams(-1,dp(30)));card.setOnClickListener(v->actions.select(page));card.setOnLongClickListener(v->{actions.manage(page);return true;});}
    }
    private void updateRow(Row row){boolean selected=row.page==document.currentPage();row.border.setColor(selected?AppColors.SOFT:AppColors.BACKGROUND);row.border.setStroke(dp(selected?2:1),selected?AppColors.ACCENT:AppColors.BORDER);row.number.setText((selected?"● ":"")+(document.infinite()?"画布 ":"")+(row.page+1)+" / "+document.pageCount());row.card.setContentDescription((document.infinite()?"独立画布 "+(row.page+1):"第 "+(row.page+1)+" 页")+(selected?"，当前":"，点击跳转")+"，长按操作菜单");row.preview.invalidate();}
    void refresh(boolean force){
        int page=document.currentPage(),count=document.pageCount();title.setText((document.infinite()?"独立画布 · ":"页面 · ")+count);insertButton.setContentDescription((document.infinite()?"独立画布 "+(page+1):"当前第 "+(page+1)+" 页")+"的复制、清空和删除等操作");appendButton.setContentDescription(document.infinite()?"追加独立画布，不与当前画布拼接":"在末尾追加第 "+(count+1)+" 页草稿纸");up.setEnabled(editingEnabled&&page>0);down.setEnabled(editingEnabled&&page+1<count);appendButton.setEnabled(editingEnabled);
        if(force||count!=previousCount)adapter.notifyDataSetChanged();else for(int i=0;i<list.getChildCount();i++)updateRow((Row)list.getChildAt(i).getTag());
        if(page!=previousPage&&(page<list.getFirstVisiblePosition()||page>list.getLastVisiblePosition()))list.setSelection(page);previousPage=page;previousCount=count;
    }
    void refreshSelection(){
        int page=document.currentPage(),count=document.pageCount();
        title.setText((document.infinite()?"独立画布 · ":"页面 · ")+count);up.setEnabled(editingEnabled&&page>0);down.setEnabled(editingEnabled&&page+1<count);appendButton.setEnabled(editingEnabled);
        for(int i=0;i<list.getChildCount();i++){
            Row row=(Row)list.getChildAt(i).getTag();
            boolean selected=row.page==page;
            row.border.setColor(selected?AppColors.SOFT:AppColors.BACKGROUND);
            row.border.setStroke(dp(selected?2:1),selected?AppColors.ACCENT:AppColors.BORDER);
            row.number.setText((selected?"● ":"")+(document.infinite()?"画布 ":"")+(row.page+1)+" / "+count);
            row.card.setContentDescription((document.infinite()?"独立画布 "+(row.page+1):"第 "+(row.page+1)+" 页")+(selected?"，当前":"，点击跳转")+"，长按操作菜单");
        }
        if(page!=previousPage&&(page<list.getFirstVisiblePosition()||page>list.getLastVisiblePosition()))list.setSelection(page);
        previousPage=page;
    }
    void setEditingEnabled(boolean enabled){if(editingEnabled==enabled)return;editingEnabled=enabled;refreshSelection();}
    private TextView label(String value,int size){TextView label=new TextView(getContext());label.setText(value);label.setTextColor(AppColors.INK);label.setTextSize(size);label.setGravity(Gravity.CENTER);return label;}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
