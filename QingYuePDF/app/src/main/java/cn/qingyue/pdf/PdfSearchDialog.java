package cn.qingyue.pdf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An explicitly submitted, cancellable document search. Closing releases its worker. */
public final class PdfSearchDialog extends Dialog {
    public interface Selection { void selected(int page); }
    public interface HitSelection { void selected(PdfTextSearch.Result result,int hitIndex); }
    public static final class Session {
        private String query="",message="";private final ArrayList<PdfTextSearch.Result> results=new ArrayList<>();
        private boolean complete,limited;
        public String query(){return query;}
        public List<PdfTextSearch.Result> results(){return Collections.unmodifiableList(new ArrayList<>(results));}
        public boolean complete(){return complete;}
        public boolean limited(){return limited;}
    }
    private static final LinkedHashMap<String,Session> SAVED=new LinkedHashMap<String,Session>(6,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Session> entry){return size()>4;}
    };

    private final Activity owner;
    private final File file;
    private final Selection selection;
    private final HitSelection hitSelection;
    private final Session session;
    private final PdfTextSearch searcher;
    private final ArrayList<PdfTextSearch.Result> results = new ArrayList<>();
    private final String initialQuery;
    private EditText queryField;
    private TextView status, empty;
    private Button stop;
    private ProgressBar progress;
    private LinearLayout header, content;
    private IconButton compactClose;
    private ListView resultList;
    private BaseAdapter adapter;
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    private boolean closed, running;
    private int generation, layoutWidth, layoutHeight;
    private String submittedQuery = "";

    private PdfSearchDialog(Activity owner, File file, String previousQuery, Selection selection,HitSelection hitSelection,Session supplied) {
        super(owner, dialogTheme(owner));
        this.owner = owner;
        this.file = file;
        this.selection = selection;
        this.hitSelection=hitSelection;
        this.session=supplied==null?saved(file):supplied;
        String requested=previousQuery==null?"":previousQuery.trim();
        this.initialQuery=requested.length()==0?session.query:requested;
        this.searcher = new PdfTextSearch(owner.getApplicationContext());
    }

    public static PdfSearchDialog show(Activity owner, File file, String previousQuery, Selection selection) {
        PdfSearchDialog dialog = new PdfSearchDialog(owner, file, previousQuery, selection,null,null);
        dialog.show();
        return dialog;
    }

    public static PdfSearchDialog showDetailed(Activity owner,File file,Session session,HitSelection selection){
        PdfSearchDialog dialog=new PdfSearchDialog(owner,file,session==null?"":session.query,null,selection,session==null?new Session():session);dialog.show();return dialog;
    }

    public Session getSession(){return session;}
    /** Process-scoped bounded session used when an Activity is recreated. */
    public static Session retainedSession(File file){return saved(file);}

    /** The current field value, including an unsubmitted edit, for the next opening. */
    public String getQuery() {
        return queryField == null ? initialQuery : queryField.getText().toString().trim();
    }

    private static int dialogTheme(Context context) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.alertDialogTheme, value, true);
        return value.resourceId;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setCanceledOnTouchOutside(true);
        content = new LinearLayout(owner);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(16));
        content.setBackground(background(AppColors.SURFACE, 22));
        content.setClipToOutline(true);
        content.setFocusableInTouchMode(true);

        header = new LinearLayout(owner);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(16));
        IconButton symbol = decoration("search", AppColors.ACCENT_DARK);
        symbol.setBackground(background(AppColors.SOFT, 12));
        header.addView(symbol, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout heading = new LinearLayout(owner);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(dp(11), 0, dp(6), 0);
        TextView title = text("搜索文档", 18, AppColors.INK);
        title.setTypeface(null, 1);
        heading.addView(title);
        TextView subtitle = text("查找文字，跳转到对应页面", 12, AppColors.MUTED);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(4);
        heading.addView(subtitle, subtitleParams);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        IconButton close = new IconButton(owner, "close", "关闭搜索");
        close.setTint(AppColors.MUTED);
        close.setOnClickListener(v -> dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        content.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout inputRow = new LinearLayout(owner);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable inputBackground = background(AppColors.BACKGROUND, 14);
        inputBackground.setStroke(dp(1), AppColors.BORDER);
        inputRow.setBackground(inputBackground);
        inputRow.setPadding(dp(14), dp(3), dp(3), dp(3));
        queryField = new EditText(owner);
        queryField.setTextColor(AppColors.INK);
        queryField.setHintTextColor(AppColors.MUTED);
        queryField.setTextSize(15);
        queryField.setSingleLine(true);
        queryField.setHint("输入文档中的关键词");
        queryField.setContentDescription("PDF 内容关键词");
        queryField.setBackgroundColor(Color.TRANSPARENT);
        queryField.setPadding(0, dp(9), dp(6), dp(9));
        queryField.setMinimumHeight(dp(46));
        // Keep the PDF/search dialog visible when a landscape keyboard would use extraction UI.
        queryField.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        queryField.setText(initialQuery);
        queryField.setSelection(queryField.length());
        inputRow.addView(queryField, new LinearLayout.LayoutParams(0, -2, 1));
        IconButton search = new IconButton(owner, "search", "搜索 PDF 内容");
        search.setTint(AppColors.ACCENT_DARK);
        search.setBackground(background(AppColors.SOFT, 11));
        search.setOnClickListener(v -> startSearch());
        inputRow.addView(search, new LinearLayout.LayoutParams(dp(48), dp(48)));
        compactClose = new IconButton(owner, "close", "关闭搜索");
        compactClose.setTint(AppColors.MUTED);
        compactClose.setOnClickListener(v -> dismiss());
        compactClose.setVisibility(View.GONE);
        inputRow.addView(compactClose, new LinearLayout.LayoutParams(dp(44), dp(48)));
        content.addView(inputRow, new LinearLayout.LayoutParams(-1, -2));
        queryField.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) { startSearch(); return true; }
            return false;
        });

        LinearLayout statusRow = new LinearLayout(owner);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(9), 0, dp(8));
        status = text("输入关键词后点击放大镜搜索", 12, AppColors.MUTED);
        status.setMaxLines(3);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        stop = new Button(owner);
        stop.setText("停止");
        stop.setTextSize(12);
        stop.setAllCaps(false);
        stop.setTextColor(AppColors.ACCENT_DARK);
        stop.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ad4a14), background(AppColors.SOFT, 11), null));
        stop.setMinWidth(0);
        stop.setMinimumWidth(0);
        stop.setMinHeight(0);
        stop.setMinimumHeight(0);
        stop.setPadding(dp(10), 0, dp(10), 0);
        stop.setVisibility(View.GONE);
        stop.setOnClickListener(v -> stopSearch());
        stop.setMinimumHeight(dp(44));
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(-2, -2);
        stopParams.leftMargin = dp(8);
        statusRow.addView(stop, stopParams);
        content.addView(statusRow, new LinearLayout.LayoutParams(-1, -2));

        progress = new ProgressBar(owner, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(AppColors.ACCENT));
        progress.setIndeterminateTintList(ColorStateList.valueOf(AppColors.ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(AppColors.SOFT));
        progress.setVisibility(View.INVISIBLE);
        content.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        FrameLayout resultArea = new FrameLayout(owner);
        resultList = new ListView(owner);
        resultList.setDivider(new ColorDrawable(Color.TRANSPARENT));
        resultList.setDividerHeight(dp(9));
        resultList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        resultList.setVerticalScrollBarEnabled(false);
        resultList.setClipToPadding(false);
        resultList.setPadding(0, dp(8), 0, dp(8));
        adapter = new ResultAdapter();
        resultList.setAdapter(adapter);
        resultList.setOnItemClickListener((parent, view, position, id) -> {
            // Editing the query clears rows immediately, but guard a queued click as well.
            if (closed || position < 0 || position >= results.size()
                    || !getQuery().equals(submittedQuery)) return;
            PdfTextSearch.Result result=results.get(position);int page = result.page;
            dismiss();
            if (!owner.isFinishing() && !owner.isDestroyed()){
                if(hitSelection!=null)hitSelection.selected(result,result.hits.isEmpty()?-1:0);else if(selection != null) selection.selected(page);
            }
        });
        resultArea.addView(resultList, new FrameLayout.LayoutParams(-1, -1));
        empty = text("查找关键词，点按结果即可跳转到对应页面。\n\n仅搜索 PDF 的文字内容，扫描图片暂不支持文字识别。", 13, AppColors.MUTED);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(18), dp(16), dp(18), dp(16));
        empty.setLineSpacing(dp(4), 1f);
        ScrollView emptyArea = new ScrollView(owner);
        emptyArea.setFillViewport(true);
        emptyArea.setVerticalScrollBarEnabled(false);
        emptyArea.addView(empty, new ScrollView.LayoutParams(-1, -2));
        resultArea.addView(emptyArea, new FrameLayout.LayoutParams(-1, -1));
        resultList.setEmptyView(emptyArea);
        content.addView(resultArea, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(content);
        content.requestFocus();

        if(initialQuery.equals(session.query)&&!session.results.isEmpty()){
            results.addAll(session.results);submittedQuery=session.query;adapter.notifyDataSetChanged();
            status.setText(session.message.length()==0?"已保留上次搜索的 "+results.size()+" 页结果":session.message);
        }

        queryField.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (closed) return;
                generation++;
                searcher.cancel();
                setRunning(false);
                results.clear();
                adapter.notifyDataSetChanged();
                status.setText(SearchText.normalize(s.toString()).isEmpty() ? "输入关键词后点击放大镜搜索" : "关键词已修改，点击放大镜搜索");
                empty.setText("输入关键词后点击搜索，或使用键盘上的搜索键。\n\n扫描图片暂不支持文字识别。");
            }
            public void afterTextChanged(Editable editable) { }
        });

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            window.setGravity(Gravity.CENTER);
            layoutListener = this::resizeToVisibleWindow;
            window.getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
            resizeToVisibleWindow();
        }
    }

    private void startSearch() {
        if (closed) return;
        String query = getQuery();
        if (SearchText.normalize(query).isEmpty()) {
            queryField.setError("请输入要搜索的内容");
            queryField.requestFocus();
            return;
        }
        queryField.setError(null);
        hideKeyboard();
        final int token = ++generation;
        submittedQuery = query;
        session.query=query;session.complete=false;session.limited=false;session.message="";session.results.clear();
        results.clear();
        adapter.notifyDataSetChanged();
        empty.setText("正在查找文档中的文字…");
        status.setText("正在准备搜索…");
        progress.setIndeterminate(true);
        setRunning(true);
        searcher.start(file, query, new PdfTextSearch.Listener() {
            @Override public void onProgress(int done, int total, List<PdfTextSearch.Result> batch) {
                if (!accept(token)) return;
                if (batch != null && !batch.isEmpty()) {
                    results.addAll(batch);
                    session.results.addAll(batch);
                    adapter.notifyDataSetChanged();
                }
                progress.setIndeterminate(total <= 0);
                progress.setMax(Math.max(1, total));
                progress.setProgress(done);
                status.setText("正在搜索 · " + done + " / " + total + " 页 · 找到 " + results.size() + " 页");
            }
            @Override public void onComplete(int totalPages, int searchablePages, boolean limited, String error) {
                if (!accept(token)) return;
                setRunning(false);
                if (error != null && !error.isEmpty()) {
                    status.setText(results.isEmpty() ? "搜索未完成" : "搜索未完成 · 已找到 " + results.size() + " 页");
                    empty.setText(error + "\n\n可重新点击放大镜搜索。");
                    if (!results.isEmpty()) status.setText((limited ? "已达到 " + results.size() + " 页结果上限" : "搜索未完整完成 · 已找到 " + results.size() + " 页") + "\n" + error);
                } else if (limited) {
                    status.setText("已显示 " + results.size() + " 页结果，请缩小关键词范围");
                } else if (results.isEmpty() && searchablePages == 0) {
                    status.setText("未发现可搜索的文字");
                    empty.setText("这份 PDF 没有可提取的文字，可能是扫描件。\n\n当前版本暂不支持图片文字识别（OCR）。");
                } else if (results.isEmpty()) {
                    status.setText("已搜索 " + totalPages + " 页 · 没有匹配结果");
                    empty.setText("没有找到“" + submittedQuery + "”。\n\n试试更短的词语，或检查输入内容。");
                } else {
                    status.setText("找到 " + results.size() + " 页 · 点按结果跳转");
                }
                session.complete=true;session.limited=limited;session.message=status.getText().toString();
            }
        });
    }

    private boolean accept(int token) {
        return !closed && token == generation && !owner.isFinishing() && !owner.isDestroyed();
    }

    private void stopSearch() {
        if (closed || !running) return;
        generation++;
        searcher.cancel();
        setRunning(false);
        status.setText("搜索已停止 · 保留 " + results.size() + " 页结果");
        session.message=status.getText().toString();session.complete=false;
        empty.setText("搜索已停止，尚未找到匹配页面。\n\n点击放大镜可重新搜索。");
    }

    private void setRunning(boolean value) {
        running = value;
        stop.setVisibility(value ? View.VISIBLE : View.GONE);
        progress.setVisibility(value ? View.VISIBLE : View.INVISIBLE);
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) owner.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null && queryField != null) keyboard.hideSoftInputFromWindow(queryField.getWindowToken(), 0);
        if (queryField != null) queryField.clearFocus();
    }

    private void resizeToVisibleWindow() {
        if (closed || getWindow() == null) return;
        Rect visible = new Rect();
        getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
        if (visible.width() <= 0 || visible.height() <= 0) owner.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
        if (visible.width() <= 0 || visible.height() <= 0) return;
        int width = Math.max(1, Math.min(dp(560), visible.width() - dp(24)));
        int height = Math.max(1, Math.min(dp(620), visible.height() - dp(24)));
        // The result area retains the remaining space; short landscape/IME windows
        // keep a close button beside the search action instead of a tall heading.
        boolean compact = height < dp(300) || height < dp(210) * owner.getResources().getConfiguration().fontScale;
        header.setVisibility(compact ? View.GONE : View.VISIBLE);
        compactClose.setVisibility(compact ? View.VISIBLE : View.GONE);
        status.setMaxLines(compact ? 2 : 3);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        int side = width < dp(360) ? dp(14) : dp(20);
        content.setPadding(side, dp(compact ? 10 : 18), side, dp(compact ? 8 : 16));
        if (width != layoutWidth || height != layoutHeight) {
            layoutWidth = width;
            layoutHeight = height;
            getWindow().setLayout(width, height);
        }
    }

    private void release() {
        if (closed) return;
        closed = true;
        generation++;
        hideKeyboard();
        searcher.close();
        if (getWindow() != null && layoutListener != null) {
            ViewTreeObserver observer = getWindow().getDecorView().getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(layoutListener);
        }
        layoutListener = null;
        synchronized(SAVED){SAVED.put(key(file),session);}
    }

    private static Session saved(File file){synchronized(SAVED){Session value=SAVED.get(key(file));return value==null?new Session():value;}}
    private static String key(File file){try{return file==null?"":file.getCanonicalPath()+"#"+file.length()+"#"+file.lastModified();}catch(java.io.IOException ignored){return file==null?"":file.getAbsolutePath()+"#"+file.length()+"#"+file.lastModified();}}

    @Override public void dismiss() { release(); super.dismiss(); }
    @Override protected void onStop() { release(); super.onStop(); }

    private int dp(float value) { return Math.round(value * owner.getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(owner);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private IconButton decoration(String kind, int color) {
        IconButton icon = new IconButton(owner, kind, "");
        icon.setTint(color);
        icon.setOnLongClickListener(null);
        icon.setLongClickable(false);
        icon.setClickable(false);
        icon.setFocusable(false);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private static final class ResultViews {
        TextView page, snippet, matches;
    }

    private final class ResultAdapter extends BaseAdapter {
        @Override public int getCount() { return results.size(); }
        @Override public Object getItem(int position) { return results.get(position); }
        @Override public long getItemId(int position) { return results.get(position).page; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            ResultViews views;
            if (recycled instanceof LinearLayout && recycled.getTag() instanceof ResultViews) {
                row = (LinearLayout) recycled;
                views = (ResultViews) row.getTag();
            } else {
                row = new LinearLayout(owner);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(13), dp(12), dp(13), dp(13));
                GradientDrawable card = background(AppColors.BACKGROUND, 15);
                card.setStroke(dp(1), AppColors.BORDER);
                row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ad4a14), card, null));
                LinearLayout metadata = new LinearLayout(owner);
                metadata.setGravity(Gravity.CENTER_VERTICAL);
                views = new ResultViews();
                views.page = text("", 12, AppColors.ACCENT_DARK);
                views.page.setTypeface(null, 1);
                views.page.setPadding(dp(8), dp(4), dp(8), dp(4));
                views.page.setBackground(background(AppColors.SOFT, 8));
                views.page.setMaxLines(2);
                views.page.setEllipsize(android.text.TextUtils.TruncateAt.END);
                FrameLayout pageSlot = new FrameLayout(owner);
                pageSlot.addView(views.page, new FrameLayout.LayoutParams(-2, -2));
                metadata.addView(pageSlot, new LinearLayout.LayoutParams(0, -2, 1));
                views.matches = text("", 11, AppColors.MUTED);
                views.matches.setGravity(Gravity.RIGHT);
                views.matches.setPadding(dp(8), 0, 0, 0);
                views.matches.setMaxLines(2);
                views.matches.setEllipsize(android.text.TextUtils.TruncateAt.END);
                metadata.addView(views.matches, new LinearLayout.LayoutParams(0, -2, 1));
                metadata.addView(decoration("right", AppColors.ACCENT_DARK), new LinearLayout.LayoutParams(dp(22), dp(28)));
                row.addView(metadata, new LinearLayout.LayoutParams(-1, -2));
                views.snippet = text("", 14, AppColors.INK);
                views.snippet.setMaxLines(3);
                views.snippet.setEllipsize(android.text.TextUtils.TruncateAt.END);
                views.snippet.setLineSpacing(dp(3), 1f);
                LinearLayout.LayoutParams snippetParams = new LinearLayout.LayoutParams(-1, -2);
                snippetParams.topMargin = dp(9);
                row.addView(views.snippet, snippetParams);
                row.setTag(views);
            }
            PdfTextSearch.Result result = results.get(position);
            views.page.setText("第 " + (result.page + 1) + " 页");
            views.matches.setText(result.matches + " 处匹配 · 跳转");
            views.snippet.setText(result.snippet);
            row.setContentDescription("第 " + (result.page + 1) + " 页，" + result.matches + " 处匹配。" + result.snippet + "。点按跳转");
            return row;
        }
    }
}
