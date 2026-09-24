package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.File;

/** A virtualized thumbnail rail below the tools. Each attachment owns its render queue. */
final class PdfPageSidebar extends LinearLayout implements AutoCloseable {
    interface Selection { void selected(int page); }
    private final File file;
    private final int pageCount;
    private final Selection selection, insertion;
    private final ListView list;
    private final BaseAdapter adapter;
    private final TextView insertButton;
    private PdfThumbnailProvider provider;
    private int currentPage, bindingGeneration;
    private boolean closed;

    PdfPageSidebar(Context context, File file, int pageCount, int currentPage,
                   Selection selection, Selection insert, Runnable navigate, Runnable collapse) {
        super(context); this.file=file; this.pageCount=pageCount; this.currentPage=currentPage; this.selection=selection;this.insertion=insert;
        setOrientation(VERTICAL); setBackgroundColor(AppColors.SURFACE); setPadding(dp(6),0,dp(6),0);
        LinearLayout title=new LinearLayout(context);title.setGravity(Gravity.CENTER_VERTICAL);
        TextView label=text("页面",12);title.addView(label,new LayoutParams(0,dp(44),1));
        IconButton close=new IconButton(context,"close","收起页面栏");close.setOnClickListener(v->collapse.run());title.addView(close,new LayoutParams(dp(44),dp(44)));addView(title);
        TextView index=text("目录 / 书签",12);index.setTextColor(AppColors.ACCENT_DARK);index.setOnClickListener(v->navigate.run());addView(index,new LayoutParams(-1,dp(44)));
        list=new ListView(context);list.setDivider(null);list.setDividerHeight(dp(8));list.setClipToPadding(false);list.setPadding(0,dp(4),0,dp(8));list.setFastScrollEnabled(false);
        adapter=new BaseAdapter(){
            public int getCount(){return pageCount;}public Object getItem(int p){return p;}public long getItemId(int p){return p;}
            public View getView(int page,View recycled,ViewGroup parent){
                Row row=recycled==null?new Row():(Row)recycled.getTag();
                if(row.page!=page){row.image.setImageDrawable(null);row.binding.clear();}row.page=page;
                int width=Math.max(72,getWidth()-dp(24));int height=Math.round(width*1.35f);
                if(row.image.getLayoutParams().height!=height)row.image.setLayoutParams(new LayoutParams(-1,height));
                row.image.setContentDescription("第 "+(page+1)+" 页缩略图");updateRow(row);requestThumbnail(row,width);
                return row.card;
            }
        };list.setAdapter(adapter);list.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView view,int state){if(state==SCROLL_STATE_IDLE)refreshVisible();}public void onScroll(AbsListView view,int first,int visible,int total){if(provider!=null&&visible>0)provider.cancelOutside(first,first+visible-1);}});
        addView(list,new LayoutParams(-1,0,1));
        insertButton=text("＋ 插入空白页",12);insertButton.setTextColor(AppColors.ACCENT_DARK);insertButton.setOnClickListener(v->{if(!closed)insert.selected(PdfPageSidebar.this.currentPage);});addView(insertButton,new LayoutParams(-1,dp(48)));updateInsertionLabel();
    }
    private final class Row {
        final LinearLayout card=new LinearLayout(getContext());final ImageView image=new ImageView(getContext());
        final TextView number=text("",12);final GradientDrawable border=new GradientDrawable();final ThumbnailBinding binding=new ThumbnailBinding();int page=-1;
        Row(){card.setTag(this);card.setOrientation(VERTICAL);card.setPadding(dp(5),dp(5),dp(5),dp(3));border.setCornerRadius(dp(9));card.setBackground(border);
            image.setBackgroundColor(Color.WHITE);image.setScaleType(ImageView.ScaleType.FIT_CENTER);card.addView(image,new LayoutParams(-1,dp(120)));card.addView(number,new LayoutParams(-1,dp(30)));
            card.setOnClickListener(v->{if(!closed&&page>=0)selection.selected(page);});card.setOnLongClickListener(v->{if(!closed&&page>=0)insertion.selected(page);return true;});}
    }
    private void updateRow(Row row){boolean selected=row.page==currentPage;row.border.setColor(selected?AppColors.SOFT:AppColors.BACKGROUND);row.border.setStroke(dp(selected?2:1),selected?AppColors.ACCENT:AppColors.BORDER);row.number.setText((selected?"● ":"")+(row.page+1)+" / "+pageCount);row.card.setContentDescription("第 "+(row.page+1)+" 页"+(selected?"，当前页":"，点击跳转")+"，长按插入空白页");}
    private void requestThumbnail(Row row,int width){
        PdfThumbnailProvider source=provider;final int page=row.page,generation=bindingGeneration;
        if(source==null){row.binding.clear();row.image.setImageDrawable(null);return;}
        if(row.image.getDrawable()!=null)return;
        final long token=row.binding.bind(page,generation);
        source.request(page,width,(ready,bitmap,error)->{if(!closed&&bindingGeneration==generation&&provider==source&&row.binding.accepts(ready,generation,token)&&bitmap!=null&&!bitmap.isRecycled())row.image.setImageBitmap(bitmap);});
    }
    void activate(){if(closed||provider!=null)return;bindingGeneration++;provider=new PdfThumbnailProvider(file);adapter.notifyDataSetChanged();list.post(()->{if(!closed&&provider!=null)list.setSelection(currentPage);});}
    void deactivate(){if(provider==null)return;bindingGeneration++;provider.close();provider=null;adapter.notifyDataSetChanged();}
    private void refreshVisible(){if(provider!=null&&!closed)for(int i=0;i<list.getChildCount();i++){Row row=(Row)list.getChildAt(i).getTag();if(row.image.getDrawable()==null)requestThumbnail(row,Math.max(72,getWidth()-dp(24)));}}
    void setCurrentPage(int page){if(closed||page<0||page>=pageCount||currentPage==page)return;currentPage=page;updateInsertionLabel();for(int i=0;i<list.getChildCount();i++)updateRow((Row)list.getChildAt(i).getTag());if(provider!=null&&(page<list.getFirstVisiblePosition()||page>list.getLastVisiblePosition()))list.setSelection(page);}
    private void updateInsertionLabel(){insertButton.setContentDescription("在当前第 "+(currentPage+1)+" 页之前或之后插入空白页");}
    @Override public void close(){if(closed)return;closed=true;deactivate();list.setAdapter(null);}
    private TextView text(String value,int size){TextView label=new TextView(getContext());label.setText(value);label.setTextSize(size);label.setTextColor(AppColors.INK);label.setGravity(Gravity.CENTER);return label;}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
