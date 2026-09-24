package cn.qingyue.pdf;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Local full-library search UI. Manifest registration and the library entry point are integrated separately. */
public final class GlobalSearchActivity extends ThemedActivity {
    /** MainActivity integration contract; page is zero based. */
    public static final String EXTRA_OPEN_DOCUMENT_ID = "docId";
    public static final String EXTRA_OPEN_PAGE = "page";
    public static final String EXTRA_OPEN_ANCHOR = "notePageId";

    private final ExecutorService indexWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService searchWorker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private GlobalSearchEngine engine;
    private GlobalSearchEngine.CancellationToken refreshCancellation;
    private Future<?> refreshFuture;
    private LinearLayout results;
    private EditText query;
    private TextView status;
    private ProgressBar progress;
    private Button cancel, rebuild;
    private int searchGeneration;
    private boolean destroyed;
    /** Persisted cache is never displayed until live sources have been checked this launch. */
    private boolean indexVerified;
    private String channelFilter = "";
    private final LinkedHashMap<String, Button> filterButtons = new LinkedHashMap<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        installBuiltInProviders();
        engine = new GlobalSearchEngine(new File(new File(getFilesDir(), "search"),
                "global-search-v1.bin"));
        buildUi(state == null ? "" : state.getString("query", ""));
        if (engine.loadWarning() != null) status.setText(engine.loadWarning());
        startRefresh();
    }

    private void buildUi(String restoredQuery) {
        LinearLayout root = column(); root.setBackgroundColor(AppColors.BACKGROUND);
        root.setPadding(dp(16), dp(10), dp(16), dp(12));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(16) + insets.getSystemWindowInsetLeft(),
                    dp(10) + insets.getSystemWindowInsetTop(),
                    dp(16) + insets.getSystemWindowInsetRight(),
                    dp(12) + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });

        LinearLayout header = row();
        Button back = smallButton("‹", false); back.setContentDescription("返回文档库");
        if (!AppColors.isClassic()) { back.setBackgroundColor(Color.TRANSPARENT); back.setTextSize(28); }
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout titles = column(); titles.setPadding(dp(10), 0, 0, 0);
        TextView title = label("全库搜索", AppColors.isClassic() ? 23 : 20, AppColors.INK); title.setTypeface(null, Typeface.BOLD);
        titles.addView(title); titles.addView(label("PDF 正文与文本框 · 笔记文本 · OCR", 11,
                AppColors.MUTED));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        rebuild = smallButton("更新索引", false); rebuild.setOnClickListener(view -> startRefresh());
        if (!AppColors.isClassic()) { rebuild.setText("更新"); rebuild.setContentDescription("更新本地搜索索引"); }
        header.addView(rebuild, new LinearLayout.LayoutParams(-2, dp(46)));
        root.addView(header); gap(root, 14);

        LinearLayout searchBox = row(); searchBox.setPadding(dp(12), 0, dp(12), 0);
        searchBox.setBackground(shape(AppColors.SURFACE, AppColors.isClassic() ? 17 : 26));
        if (AppColors.isClassic()) {
            TextView glass = label("⌕", 22, AppColors.ACCENT_DARK); glass.setGravity(Gravity.CENTER);
            searchBox.addView(glass, new LinearLayout.LayoutParams(dp(34), dp(52)));
        } else {
            IconButton glass = new IconButton(this, "search", ""); glass.setTint(AppColors.MUTED);
            glass.setClickable(false); glass.setFocusable(false); glass.setOnLongClickListener(null);
            glass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            searchBox.addView(glass, new LinearLayout.LayoutParams(dp(30), dp(52)));
        }
        query = new EditText(this); query.setSingleLine(true); query.setTextSize(15);
        query.setTextColor(AppColors.INK); query.setHintTextColor(AppColors.MUTED);
        query.setHint("搜索所有本地内容"); query.setBackgroundColor(Color.TRANSPARENT);
        query.setPadding(dp(4), 0, 0, 0); query.setText(restoredQuery);
        searchBox.addView(query, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(searchBox); gap(root, 10);
        if (!AppColors.isClassic()) addFilters(root);

        LinearLayout stateRow = row();
        status = label("准备本地索引…", 12, AppColors.MUTED);
        status.setSingleLine(true); status.setEllipsize(TextUtils.TruncateAt.END);
        stateRow.addView(status, new LinearLayout.LayoutParams(0, dp(38), 1));
        cancel = smallButton("取消", false); cancel.setVisibility(View.GONE);
        cancel.setOnClickListener(view -> cancelRefresh());
        stateRow.addView(cancel, new LinearLayout.LayoutParams(-2, dp(38)));
        root.addView(stateRow);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1); progress.setProgress(0); progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3))); gap(root, 8);

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        results = column(); results.setPadding(0, dp(4), 0, dp(22)); scroll.addView(results);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root); root.requestApplyInsets();

        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count,
                    int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before,
                    int count) { scheduleSearch(value.toString()); }
            @Override public void afterTextChanged(Editable value) { }
        });
        scheduleSearch(restoredQuery);
    }

    private void startRefresh() {
        cancelRefresh();
        indexVerified = false;
        scheduleSearch(query.getText().toString());
        final GlobalSearchEngine.CancellationToken token =
                new GlobalSearchEngine.CancellationToken();
        refreshCancellation = token;
        progress.setVisibility(View.VISIBLE); progress.setIndeterminate(true);
        cancel.setVisibility(View.VISIBLE); rebuild.setEnabled(false);
        status.setText("正在核对本地内容…");
        refreshFuture = indexWorker.submit(() -> {
            ArrayList<GlobalSearchSource> sources = new ArrayList<GlobalSearchSource>();
            GlobalSearchLibrarySource library = new GlobalSearchLibrarySource(this);
            sources.add(library);
            for (GlobalSearchContentProvider provider : GlobalSearchProviderRegistry.snapshot())
                sources.add(new GlobalSearchProviderSource(this, provider));
            try {
                GlobalSearchEngine.RefreshReport report = engine.refresh(sources, token,
                        (done, total, name, reused) -> runOnUiThread(() -> {
                            if (destroyed || refreshCancellation != token) return;
                            progress.setIndeterminate(false); progress.setMax(Math.max(1, total));
                            progress.setProgress(done);
                            status.setText("正在索引 " + done + " / " + total + " · " + name);
                        }));
                runOnUiThread(() -> {
                    if (destroyed || refreshCancellation != token) return;
                    indexVerified = true;
                    finishRefresh();
                    String message = "已索引 " + report.documents + " 份资料 · "
                            + report.fragments + " 个内容片段";
                    int skipped = library.skippedEntries();
                    if (report.failures.size() > 0 || skipped > 0)
                        message += " · " + (report.failures.size() + skipped) + " 项未读取";
                    status.setText(message); scheduleSearch(query.getText().toString());
                });
            } catch (InterruptedIOException cancelled) {
                runOnUiThread(() -> {
                    if (destroyed || refreshCancellation != token) return;
                    finishRefresh(); status.setText(indexVerified
                            ? "已取消更新，显示本次核验的索引"
                            : "已取消更新；未核验的旧索引暂不显示");
                });
            } catch (IOException failure) {
                runOnUiThread(() -> {
                    if (destroyed || refreshCancellation != token) return;
                    finishRefresh(); status.setText(indexVerified
                            ? "索引更新失败，显示本次核验的索引：" + failure.getMessage()
                            : "索引更新失败；未核验的旧索引暂不显示：" + failure.getMessage());
                });
            } catch (RuntimeException failure) {
                runOnUiThread(() -> {
                    if (destroyed || refreshCancellation != token) return;
                    finishRefresh(); status.setText(indexVerified
                            ? "索引更新失败，显示本次核验的索引"
                            : "索引更新失败；未核验的旧索引暂不显示");
                });
            }
        });
    }

    private void finishRefresh() {
        refreshCancellation = null; refreshFuture = null;
        progress.setVisibility(View.GONE); cancel.setVisibility(View.GONE); rebuild.setEnabled(true);
    }

    private void cancelRefresh() {
        GlobalSearchEngine.CancellationToken token = refreshCancellation;
        if (token != null) token.cancel();
        Future<?> running = refreshFuture;
        if (running != null) running.cancel(true);
        // A queued Future may never run, so it cannot deliver a cancellation callback.
        refreshCancellation = null;
        refreshFuture = null;
        if (cancel != null) {
            progress.setVisibility(View.GONE);
            cancel.setVisibility(View.GONE);
            rebuild.setEnabled(true);
            status.setText(indexVerified ? "已取消更新，显示本次核验的索引"
                    : "已取消更新；未核验的旧索引暂不显示");
            scheduleSearch(query == null ? "" : query.getText().toString());
        }
    }

    private void scheduleSearch(final String text) {
        final int generation = ++searchGeneration;
        final String channel = channelFilter;
        main.postDelayed(() -> {
            if (destroyed || generation != searchGeneration) return;
            searchWorker.submit(() -> {
                GlobalSearchEngine.SearchResponse response = engine.search(text, channel);
                runOnUiThread(() -> {
                    if (!destroyed && generation == searchGeneration) showResults(response);
                });
            });
        }, 160);
    }

    private void showResults(GlobalSearchEngine.SearchResponse response) {
        results.removeAllViews();
        if (!indexVerified) {
            empty("正在核对本地资料", "索引完成前暂不显示上次保存的搜索结果。");
            return;
        }
        if (response.normalizedQuery.length() == 0) {
            empty("输入关键词，查找文档名、PDF 正文与文本框、笔记文本及 OCR。",
                    "全部处理都在本机完成。扫描页需先完成本地 OCR。 ");
            return;
        }
        if (response.results.isEmpty()) {
            if (channelFilter.isEmpty())
                empty("没有找到“" + response.normalizedQuery + "”", "索引更新中时可稍后再试。");
            else empty("此分类暂无匹配内容", "可以切换到“全部”查看其他来源的结果。");
            return;
        }
        if (!AppColors.isClassic()) {
            TextView heading = label("搜索结果", 16, AppColors.INK);
            heading.setTypeface(null, Typeface.BOLD); results.addView(heading); gap(results, 3);
        }
        TextView count = label("找到 " + response.results.size() + " 项"
                + (response.limited ? "（仅显示前 200 项）" : ""), 13, AppColors.MUTED);
        results.addView(count); gap(results, 9);
        for (GlobalSearchEngine.Result result : response.results) {
            if (!AppColors.isClassic()) { addPaperResult(result); continue; }
            LinearLayout card = column(); card.setPadding(dp(15), dp(13), dp(15), dp(13));
            GradientDrawable background = shape(AppColors.SURFACE, 17);
            background.setStroke(dp(1), AppColors.BORDER); card.setBackground(background);
            TextView name = label(result.documentName, 16, AppColors.INK);
            name.setTypeface(null, Typeface.BOLD); name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END); card.addView(name);
            gap(card, 5);
            String meta = channelName(result.channel)
                    + (result.page >= 0 ? " · 第 " + (result.page + 1) + " 页" : "")
                    + (result.section.length() > 0 ? " · " + result.section : "");
            card.addView(label(meta, 11, AppColors.ACCENT_DARK)); gap(card, 7);
            TextView snippet = label(result.snippet, 13, AppColors.MUTED);
            snippet.setMaxLines(4); snippet.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(snippet); card.setContentDescription(name.getText() + "，" + meta);
            card.setOnClickListener(view -> open(result));
            results.addView(card); gap(results, 10);
        }
    }

    private void addFilters(LinearLayout root) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout filters = row();
        String[] keys = {"", GlobalSearchSource.CHANNEL_PDF_TEXT,
                GlobalSearchSource.CHANNEL_NOTE_TEXT, GlobalSearchSource.CHANNEL_OCR_TEXT};
        String[] names = {"全部", "PDF 正文", "笔记文本", "OCR"};
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            Button chip = smallButton(names[i], key.equals(channelFilter));
            chip.setMinWidth(0); chip.setMinimumWidth(0);
            chip.setOnClickListener(view -> {
                if (key.equals(channelFilter)) return;
                channelFilter = key; updateFilters();
                // Invalidate both queued and running searches before the next channel renders.
                scheduleSearch(query.getText().toString());
                results.removeAllViews();
                empty("正在搜索…", "");
            });
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, dp(38));
            size.rightMargin = dp(7); filters.addView(chip, size); filterButtons.put(key, chip);
        }
        scroll.addView(filters); root.addView(scroll); gap(root, 4); updateFilters();
    }

    private void updateFilters() {
        for (Map.Entry<String, Button> entry : filterButtons.entrySet()) {
            boolean active = entry.getKey().equals(channelFilter);
            entry.getValue().setSelected(active);
            entry.getValue().setTextColor(active ? AppColors.ON_ACCENT : AppColors.INK);
            entry.getValue().setBackground(shape(active ? AppColors.ACCENT : AppColors.SURFACE, 19));
        }
    }

    private void addPaperResult(GlobalSearchEngine.Result result) {
        LinearLayout card = row(); card.setPadding(dp(13), dp(14), dp(13), dp(14));
        card.setBackground(shape(AppColors.SURFACE, 15));
        boolean note = "note".equals(result.documentKind);
        LinearLayout badge = column(); badge.setGravity(Gravity.CENTER);
        badge.setBackground(shape(note ? AppColors.BACKGROUND : AppColors.SOFT, 10));
        TextView type = label(note ? "笔记" : GlobalSearchSource.CHANNEL_OCR_TEXT.equals(result.channel) ? "OCR" : "PDF",
                11, AppColors.ACCENT_DARK); type.setTypeface(null, Typeface.BOLD); badge.addView(type);
        IconButton symbol = new IconButton(this, note ? "note" : "page", "");
        symbol.setTint(AppColors.ACCENT); symbol.setClickable(false); symbol.setFocusable(false);
        symbol.setOnLongClickListener(null); symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        badge.addView(symbol, new LinearLayout.LayoutParams(dp(30), dp(30)));
        card.addView(badge, new LinearLayout.LayoutParams(dp(44), dp(74)));
        LinearLayout text = column(); text.setPadding(dp(12), 0, 0, 0);
        TextView name = label(result.documentName, 14, AppColors.INK);
        name.setTypeface(null, Typeface.BOLD); name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END); text.addView(name); gap(text, 5);
        TextView snippet = label(result.snippet, 12, AppColors.MUTED);
        snippet.setMaxLines(2); snippet.setEllipsize(TextUtils.TruncateAt.END);
        snippet.setLineSpacing(dp(2), 1f); text.addView(snippet); gap(text, 7);
        String meta = channelName(result.channel)
                + (result.page >= 0 ? " · 第 " + (result.page + 1) + " 页" : "")
                + (result.section.length() > 0 ? " · " + result.section : "")
                + (result.matches > 0 ? " · " + result.matches + " 处匹配" : "");
        TextView detail = label(meta, 10, AppColors.MUTED);
        detail.setMaxLines(2); detail.setEllipsize(TextUtils.TruncateAt.END); text.addView(detail);
        card.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        card.setContentDescription(result.documentName + "，" + meta);
        card.setFocusable(true); card.setOnClickListener(view -> open(result));
        results.addView(card); gap(results, 10);
    }

    private void empty(String title, String detail) {
        LinearLayout panel = column(); panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(22), dp(58), dp(22), dp(36));
        if (!AppColors.isClassic()) {
            panel.setBackground(shape(AppColors.SURFACE, 20));
            IconButton illustration = new IconButton(this, "search", "");
            illustration.setTint(AppColors.ACCENT); illustration.setClickable(false);
            illustration.setFocusable(false); illustration.setOnLongClickListener(null);
            illustration.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            illustration.setBackground(shape(AppColors.SOFT, 20));
            panel.addView(illustration, new LinearLayout.LayoutParams(dp(60), dp(60))); gap(panel, 18);
        }
        TextView heading = label(title, 16, AppColors.INK); heading.setGravity(Gravity.CENTER);
        panel.addView(heading); gap(panel, 8);
        TextView body = label(detail, 12, AppColors.MUTED); body.setGravity(Gravity.CENTER);
        panel.addView(body); results.addView(panel);
    }

    private void open(GlobalSearchEngine.Result result) {
        Intent intent;
        {
            intent = new Intent(this, MainActivity.class)
                    .putExtra(EXTRA_OPEN_DOCUMENT_ID, result.documentId)
                    .putExtra(EXTRA_OPEN_PAGE, Math.max(0, result.page))
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }
        if (result.anchor.length() > 0) intent.putExtra(EXTRA_OPEN_ANCHOR, result.anchor);
        startActivity(intent); finish();
    }

    /** Called here on every launch; no QingYueApplication registration is required. */
    public static void installBuiltInProviders() {
        GlobalSearchProviderRegistry.register(new RecognitionNoteTextProvider());
        GlobalSearchProviderRegistry.register(new RecognitionOcrSidecarProvider());
        GlobalSearchProviderRegistry.register(new RecognitionReaderTextProvider());
    }

    private String channelName(String channel) {
        if ("document_title".equals(channel)) return "文档名称";
        if (GlobalSearchSource.CHANNEL_PDF_TEXT.equals(channel)) return "PDF 文字";
        if (GlobalSearchSource.CHANNEL_NOTE_TEXT.equals(channel)) return "笔记文本";
        if (GlobalSearchSource.CHANNEL_OCR_TEXT.equals(channel)) return "OCR 文字";
        if ("reader_text".equals(channel)) return "阅读器文本框";
        if (GlobalSearchSource.CHANNEL_STUDY_SUMMARY.equals(channel)) return "学习摘要";
        return "本地内容";
    }

    private LinearLayout column() { LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this);
        view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private TextView label(String text, int size, int color) { TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(color); return view; }
    private Button smallButton(String text, boolean primary) { Button view = new Button(this);
        view.setText(text); view.setTextSize(13); view.setAllCaps(false);
        view.setTextColor(primary ? (AppColors.isClassic() ? AppColors.SURFACE : AppColors.ON_ACCENT) : AppColors.ACCENT_DARK);
        view.setBackground(shape(primary ? AppColors.ACCENT : AppColors.isClassic() ? AppColors.SOFT : AppColors.SURFACE,
                AppColors.isClassic() ? 13 : 19));
        if (!AppColors.isClassic()) { view.setStateListAnimator(null); view.setElevation(0); }
        view.setPadding(dp(12), 0, dp(12), 0); return view; }
    private GradientDrawable shape(int color, int radius) { GradientDrawable shape =
        new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius));
        if (!AppColors.isClassic()) shape.setStroke(dp(1), color == AppColors.ACCENT ? AppColors.ACCENT : AppColors.BORDER);
        return shape; }
    private void gap(LinearLayout parent, int height) { parent.addView(new View(this),
        new LinearLayout.LayoutParams(1, dp(height))); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putString("query", query.getText().toString());
    }

    @Override protected void onDestroy() {
        destroyed = true; cancelRefresh(); searchGeneration++; main.removeCallbacksAndMessages(null);
        indexWorker.shutdownNow(); searchWorker.shutdownNow(); super.onDestroy();
    }
}
