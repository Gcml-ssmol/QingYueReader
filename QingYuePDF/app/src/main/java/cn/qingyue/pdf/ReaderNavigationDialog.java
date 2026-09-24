package cn.qingyue.pdf;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Native four-section navigator: outline, thumbnails, bookmarks and annotations. */
public final class ReaderNavigationDialog extends Dialog {
    public interface Selection {void selected(int page);}
    public interface MetadataChanged {void changed(ReaderDocument document);}
    private static final int OUTLINE=0,PAGES=1,BOOKMARKS=2,ANNOTATIONS=3;
    private final Activity owner;private final File pdf,directory;private final String docId;private final int pageCount,currentPage;
    private final Selection selection;private Selection insertion;private Button insertPage;private final MetadataChanged metadataChanged;private ReaderDocument document;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();private final Handler main=new Handler(Looper.getMainLooper());
    private PdfThumbnailProvider thumbnails;private PdfNavigationIndex navigation;private SortedSet<Integer> inkPages=Collections.unmodifiableSortedSet(new TreeSet<Integer>());private List<ReaderDocument.Bookmark> bookmarkRows=Collections.emptyList();private List<AnnotationRow> annotationRows=Collections.emptyList();
    private final ArrayList<Button> tabs=new ArrayList<>();private final int[] sectionPositions={0,0,0,0};private ListView list;private BaseAdapter adapter;private TextView status,empty;private Button addBookmark;private int section=OUTLINE;private boolean sectionShown,closed,navigationLoaded,navigationFailed;private Dialog bookmarkEditor;

    private ReaderNavigationDialog(Activity owner,File pdf,File directory,String docId,int pageCount,int currentPage,ReaderDocument document,Selection selection,MetadataChanged changed){
        super(owner,theme(owner));requestWindowFeature(Window.FEATURE_NO_TITLE);this.owner=owner;this.pdf=pdf;this.directory=directory;this.docId=docId;this.pageCount=pageCount;this.currentPage=currentPage;this.document=document==null?new ReaderDocument():document.copy();this.selection=selection;this.metadataChanged=changed;refreshRows();
    }
    public static ReaderNavigationDialog show(Activity owner,File pdf,File directory,String docId,int pageCount,int currentPage,ReaderDocument document,Selection selection,MetadataChanged changed){
        CrashDiagnostics.markStage("导航创建");ReaderNavigationDialog dialog=new ReaderNavigationDialog(owner,pdf,directory,docId,pageCount,currentPage,document,selection,changed);
        try{dialog.show();return dialog;}catch(RuntimeException|LinkageError problem){dialog.dismiss();throw problem;}
    }
    @Override protected void onCreate(Bundle state){super.onCreate(state);thumbnails=new PdfThumbnailProvider(pdf);
        LinearLayout shell=column();shell.setPadding(dp(18),dp(16),dp(18),dp(14));shell.setBackground(shape(AppColors.SURFACE,22));
        LinearLayout title=row();IconButton icon=new IconButton(getContext(),"pages","PDF 导航");icon.setTint(AppColors.ACCENT_DARK);icon.setClickable(false);icon.setFocusable(false);title.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout words=column();words.setPadding(dp(10),0,0,0);TextView heading=text("PDF 导航",18,AppColors.INK);heading.setTypeface(null,1);words.addView(heading);status=text("正在读取目录与批注…",11,AppColors.MUTED);words.addView(status);title.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        IconButton close=new IconButton(getContext(),"close","关闭导航");close.setTint(AppColors.MUTED);close.setOnClickListener(v->dismiss());title.addView(close,new LinearLayout.LayoutParams(dp(44),dp(44)));shell.addView(title);
        LinearLayout tabRow=row();String[] names={"原文目录","缩略图","我的书签","批注"};for(int i=0;i<names.length;i++){final int target=i;Button button=new Button(getContext());button.setText(names[i]);button.setTextSize(12);button.setAllCaps(false);button.setMinWidth(0);button.setMinimumWidth(0);button.setOnClickListener(v->showSection(target));tabs.add(button);tabRow.addView(button,new LinearLayout.LayoutParams(0,dp(42),1));}shell.addView(tabRow);
        addBookmark=new Button(getContext());addBookmark.setText("＋ 为当前第 "+(currentPage+1)+" 页添加书签");addBookmark.setTextSize(13);addBookmark.setAllCaps(false);addBookmark.setTextColor(AppColors.ACCENT_DARK);addBookmark.setOnClickListener(v->addCurrentBookmark());shell.addView(addBookmark,new LinearLayout.LayoutParams(-1,dp(46)));
        insertPage=new Button(getContext());insertPage.setText("＋ 插入空白页 · 当前第 "+(currentPage+1)+" 页");insertPage.setTextSize(13);insertPage.setAllCaps(false);insertPage.setTextColor(AppColors.ACCENT_DARK);insertPage.setContentDescription("在当前第 "+(currentPage+1)+" 页之前或之后插入空白页");insertPage.setOnClickListener(v->requestInsertion(currentPage));insertPage.setVisibility(View.GONE);shell.addView(insertPage,new LinearLayout.LayoutParams(-1,dp(48)));
        list=new ListView(getContext());list.setDivider(new ColorDrawable(Color.TRANSPARENT));list.setDividerHeight(dp(8));list.setSelector(new ColorDrawable(Color.TRANSPARENT));list.setFastScrollEnabled(false);adapter=new NavigationAdapter();list.setAdapter(adapter);list.setOnItemClickListener((parent,view,position,id)->activate(position));shell.addView(list,new LinearLayout.LayoutParams(-1,0,1));empty=text("",13,AppColors.MUTED);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(20),dp(24),dp(20),dp(24));shell.addView(empty,new LinearLayout.LayoutParams(-1,0,1));list.setEmptyView(empty);setContentView(shell);setCanceledOnTouchOutside(true);
        Window window=getWindow();if(window!=null){window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));Rect frame=new Rect();owner.getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);window.setLayout(Math.min(dp(680),Math.max(dp(280),frame.width()-dp(24))),Math.min(dp(720),Math.max(dp(300),frame.height()-dp(24))));}
        showSection(OUTLINE);load();
    }
    private void load(){CrashDiagnostics.markStage("数据读取");worker.execute(()->{PdfNavigationIndex index=null;SortedSet<Integer> pages=Collections.unmodifiableSortedSet(new TreeSet<Integer>());String failure=null;try{index=PdfNavigationIndex.load(owner.getApplicationContext(),pdf);}catch(IOException|RuntimeException|LinkageError|OutOfMemoryError problem){CrashDiagnostics.record(owner,"目录读取失败",problem);failure="目录读取失败，仍可使用页码与书签";}try{pages=verifiedInkPages();}catch(RuntimeException|LinkageError|OutOfMemoryError problem){CrashDiagnostics.record(owner,"批注索引读取失败",problem);if(failure==null)failure="批注页读取失败，仍可使用目录、缩略图和书签";}
            final PdfNavigationIndex ready=index;final SortedSet<Integer> readyPages=pages;final String message=failure;main.post(()->{if(closed)return;CrashDiagnostics.markStage("导航就绪");navigation=ready;navigationLoaded=true;navigationFailed=ready==null;inkPages=readyPages;refreshAnnotationRows();status.setText(message==null?"原文目录与页码标签已读取；书签由你手动添加":message);adapter.notifyDataSetChanged();updateEmpty();});});}
    private SortedSet<Integer> verifiedInkPages(){TreeSet<Integer> result=new TreeSet<>();for(int page:InkStore.annotatedPages(directory,docId,pageCount))try{if(!InkStore.load(directory,docId+"-"+page).isEmpty())result.add(page);}catch(IOException ignored){}return Collections.unmodifiableSortedSet(result);}
    private void showSection(int value){if(closed||list==null||value<OUTLINE||value>ANNOTATIONS)return;if(sectionShown)sectionPositions[section]=Math.max(0,list.getFirstVisiblePosition());section=value;CrashDiagnostics.markStage("导航切换");if(!sectionShown&&section==PAGES)sectionPositions[PAGES]=currentPage;sectionShown=true;for(int i=0;i<tabs.size();i++){Button button=tabs.get(i);boolean active=i==section;button.setTextColor(active?AppColors.ON_ACCENT:AppColors.INK);button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff),shape(active?AppColors.ACCENT:AppColors.BACKGROUND,11),null));}addBookmark.setVisibility(section==BOOKMARKS?View.VISIBLE:View.GONE);if(insertPage!=null)insertPage.setVisibility(section==PAGES&&insertion!=null?View.VISIBLE:View.GONE);adapter.notifyDataSetChanged();updateEmpty();int position=section==PAGES&&sectionPositions[PAGES]==0?currentPage:sectionPositions[section];final int selectedSection=section;list.post(()->{if(!closed&&section==selectedSection&&count()>0)list.setSelection(Math.max(0,Math.min(position,count()-1)));});}
    private void updateEmpty(){if(empty==null)return;empty.setText(section==OUTLINE?(!navigationLoaded?"正在读取 PDF 内嵌目录…":navigationFailed?"目录读取失败。可使用缩略图跳页，或在“我的书签”添加书签。":"此 PDF 没有内嵌目录。可使用缩略图跳页，或在“我的书签”添加书签。") : section==BOOKMARKS?"还没有手动书签。点击上方按钮收藏当前页。":section==ANNOTATIONS?"当前文档还没有批注。":"没有可显示的页面。");}
    private void addCurrentBookmark(){if(closed||bookmarkEditor!=null)return;bookmarkEditor=editBookmarkName(owner,currentPage,"第 "+pageLabel(currentPage)+" 页",false,label->{if(closed)return;try{document.addBookmark(currentPage,label);}catch(IllegalStateException limit){Toast.makeText(owner,"书签数量已达上限",Toast.LENGTH_LONG).show();return;}saveMetadata();adapter.notifyDataSetChanged();});bookmarkEditor.setOnDismissListener(v->bookmarkEditor=null);}
    private void renameBookmark(ReaderDocument.Bookmark bookmark){if(closed||bookmarkEditor!=null)return;bookmarkEditor=editBookmarkName(owner,bookmark.page,bookmark.label,true,label->{if(closed)return;if(document.renameBookmark(bookmark.id,label)){saveMetadata();adapter.notifyDataSetChanged();}});bookmarkEditor.setOnDismissListener(v->bookmarkEditor=null);}
    static Dialog editBookmarkName(Activity owner,int page,String initial,boolean rename,ReaderDialogs.BookmarkSaved saved){
        EditText input=new EditText(owner);input.setSingleLine(true);input.setText(initial);input.setSelectAllOnFocus(true);input.setHint("例如：第三章重点");input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(240)});
        LinearLayout body=new LinearLayout(owner);int pad=Math.round(20*owner.getResources().getDisplayMetrics().density);body.setPadding(pad,pad/2,pad,pad/2);body.addView(input,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(owner).setTitle((rename?"重命名书签":"添加书签")+" · 第 "+(page+1)+" 页").setView(body).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String name;try{name=ReaderDocument.bookmarkName(input.getText().toString());}catch(IllegalArgumentException invalid){input.setError(invalid.getMessage());return;}dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);if(saved!=null)saved.save(name);dialog.dismiss();}));dialog.show();return dialog;
    }
    void showPages(){showSection(PAGES);}
    void setPageInsertion(Selection callback){insertion=callback;if(insertPage!=null)insertPage.setVisibility(section==PAGES&&insertion!=null?View.VISIBLE:View.GONE);if(adapter!=null)adapter.notifyDataSetChanged();}
    private void requestInsertion(int page){if(closed||insertion==null)return;Selection callback=insertion;dismiss();callback.selected(page);}
    private void saveMetadata(){ReaderDocument snapshot=document.copy();refreshRows();updateEmpty();if(metadataChanged!=null){metadataChanged.changed(snapshot);return;}worker.execute(()->{try{ReaderStore.save(directory,docId,snapshot);}catch(IOException problem){main.post(()->{if(!closed)Toast.makeText(owner,"书签保存失败，请检查存储空间",Toast.LENGTH_LONG).show();});}});}
    private int count(){if(section==PAGES)return pageCount;if(section==OUTLINE)return navigation==null?0:navigation.outline.size();if(section==BOOKMARKS)return bookmarkRows.size();return annotationRows.size();}
    private int pageAt(int position){if(position<0||position>=count())return -1;if(section==PAGES)return position;if(section==OUTLINE)return navigation.outline.get(position).page;if(section==BOOKMARKS)return bookmarkRows.get(position).page;return annotationRows.get(position).page;}
    private void activate(int position){if(position<0||position>=count())return;activatePage(pageAt(position));}
    private void activatePage(int page){if(closed||page<0||page>=pageCount)return;dismiss();if(selection!=null&&!owner.isFinishing()&&!owner.isDestroyed())selection.selected(page);}
    private void refreshRows(){ArrayList<ReaderDocument.Bookmark> rows=new ArrayList<>();for(ReaderDocument.Bookmark item:document.bookmarks())if(item.page<pageCount)rows.add(item);bookmarkRows=Collections.unmodifiableList(rows);refreshAnnotationRows();}
    private void refreshAnnotationRows(){ArrayList<AnnotationRow> rows=new ArrayList<>();for(int page:inkPages)rows.add(new AnnotationRow(page,"手写批注","本页含手写或手绘标记"));for(ReaderDocument.Markup markup:document.markups())if(markup.page<pageCount)rows.add(new AnnotationRow(markup.page,"文字高亮",markup.quote.length()==0?"已高亮选中文字":markup.quote));Collections.sort(rows,(a,b)->a.page!=b.page?Integer.compare(a.page,b.page):a.title.compareTo(b.title));annotationRows=Collections.unmodifiableList(rows);}
    private static final class AnnotationRow {final int page;final String title,detail;AnnotationRow(int page,String title,String detail){this.page=page;this.title=title;this.detail=detail;}}
    private String pageLabel(int page){return navigation==null?Integer.toString(page+1):navigation.label(page);}

    private final class NavigationAdapter extends BaseAdapter {
        public int getCount(){return count();}public Object getItem(int position){return position;}public long getItemId(int position){return pageAt(position);}
        public View getView(int position,View recycled,ViewGroup parent){
            LinearLayout card=column();card.setPadding(dp(12),dp(10),dp(12),dp(10));GradientDrawable background=shape(AppColors.BACKGROUND,14);background.setStroke(dp(1),AppColors.BORDER);card.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ad4a14),background,null));int page=pageAt(position);if(page<0||page>=pageCount)return card;card.setOnClickListener(v->activatePage(page));if(section==PAGES&&insertion!=null){card.setContentDescription("第 "+(page+1)+" 页，点击跳转，长按插入空白页");card.setOnLongClickListener(v->{requestInsertion(page);return true;});}
            LinearLayout line=row();if(section==PAGES){ImageView image=new ImageView(getContext());image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);image.setBackgroundColor(Color.WHITE);image.setTag(page);line.addView(image,new LinearLayout.LayoutParams(dp(82),dp(104)));thumbnails.request(page,dp(150),(ready,bitmap,error)->{if(!closed){if(image.getTag() instanceof Integer&&(Integer)image.getTag()==ready&&bitmap!=null&&!bitmap.isRecycled())image.setImageBitmap(bitmap);}});}
            LinearLayout labels=column();labels.setPadding(section==PAGES?dp(12):dp(4),0,0,0);String title,detail;
            if(section==OUTLINE){PdfNavigationIndex.Item item=navigation.outline.get(position);title=item.title;detail="第 "+item.label+" 页";labels.setPadding(dp(4+Math.min(8,item.depth)*12),0,0,0);}
            else if(section==PAGES){title="第 "+pageLabel(page)+" 页";detail=page==currentPage?"当前页面 · 点按返回":"点按跳转";}
            else if(section==BOOKMARKS){ReaderDocument.Bookmark item=bookmarkRows.get(position);title=item.label;detail="第 "+pageLabel(page)+" 页 · 自定义书签";}
            else{AnnotationRow item=annotationRows.get(position);title=item.title+" · 第 "+pageLabel(page)+" 页";detail=item.detail;}
            TextView primary=text(title,14,AppColors.INK);primary.setTypeface(null,1);primary.setMaxLines(2);labels.addView(primary);TextView secondary=text(detail,12,AppColors.MUTED);secondary.setMaxLines(3);labels.addView(secondary);line.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            if(section==BOOKMARKS){final ReaderDocument.Bookmark bookmark=bookmarkRows.get(position);labels.setOnClickListener(v->activatePage(bookmark.page));IconButton edit=new IconButton(getContext(),"pen","重命名书签");edit.setFocusable(false);edit.setOnClickListener(v->renameBookmark(bookmark));line.addView(edit,new LinearLayout.LayoutParams(dp(44),dp(44)));IconButton remove=new IconButton(getContext(),"trash","删除书签");remove.setFocusable(false);remove.setTint(0xffb75448);remove.setOnClickListener(v->{if(!closed&&document.remove(bookmark.id)){saveMetadata();notifyDataSetChanged();}});line.addView(remove,new LinearLayout.LayoutParams(dp(44),dp(44)));}
            card.addView(line);return card;
        }
    }
    @Override public void dismiss(){close();super.dismiss();}
    @Override protected void onStop(){close();super.onStop();}
    private void close(){if(closed)return;closed=true;if(bookmarkEditor!=null){bookmarkEditor.dismiss();bookmarkEditor=null;}CrashDiagnostics.markStage("阅读器");main.removeCallbacksAndMessages(null);worker.shutdown();if(thumbnails!=null)thumbnails.close();}
    private int dp(float value){return Math.round(value*owner.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout value=new LinearLayout(getContext());value.setOrientation(LinearLayout.VERTICAL);return value;}
    private LinearLayout row(){LinearLayout value=new LinearLayout(getContext());value.setGravity(Gravity.CENTER_VERTICAL);return value;}
    private TextView text(String value,int size,int color){TextView view=new TextView(getContext());view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setPadding(0,dp(3),0,dp(3));return view;}
    private GradientDrawable shape(int color,int radius){GradientDrawable drawable=new GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(dp(radius));return drawable;}
    private static int theme(Context context){android.util.TypedValue value=new android.util.TypedValue();context.getTheme().resolveAttribute(android.R.attr.alertDialogTheme,value,true);return value.resourceId;}
}
