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
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Warm, compact cards for the reader's menus, settings and messages. */
public final class ReaderDialogs {
    private static final int WARNING = 0xffb75448;
    private static final int WARNING_SOFT = 0xffffeeea;

    private ReaderDialogs() { }

    public interface SettingsChanged { void changed(boolean horizontal, boolean twoFinger); }
    public interface PageSelection { void selected(int zeroBased); }
    public interface AdvancedToolSelection { void selected(int tool); }
    public interface BookmarkSaved { void save(String label); }

    public static Dialog menu(Activity owner, boolean horizontal, Runnable settings,
            Runnable fit, Runnable clear, Runnable help) {
        CardDialog dialog = new CardDialog(owner);
        title(dialog, "阅读工具", "为这次阅读做一点调整", "settings", false, true);
        gap(dialog.body, 20);
        action(dialog, "settings", "阅读设置", horizontal ? "左右翻页 · 手势与阅读偏好" : "上下滚动 · 手势与阅读偏好", false, settings);
        gap(dialog.body, 10);
        action(dialog, "fit", horizontal ? "恢复整页显示" : "恢复适合宽度", "重置页面缩放与位置", false, fit);
        gap(dialog.body, 10);
        action(dialog, "info", "操作说明", "翻页、手写与搜索的小提示", false, help);
        gap(dialog.body, 18);
        action(dialog, "eraser", "清空本页批注", "只清空当前页，可通过撤销恢复", true, clear);
        dialog.show();
        return dialog;
    }

    public static Dialog settings(Activity owner, boolean horizontal, boolean twoFinger,
            SettingsChanged callback) {
        CardDialog dialog = new CardDialog(owner);
        final boolean[] values = {horizontal, twoFinger};
        title(dialog, "阅读设置", "按自己的习惯阅读与批注", "settings", false, true);
        gap(dialog.body, 22);
        section(dialog.body, "翻页方式", "两种模式互斥，选择后立即生效");
        gap(dialog.body, 12);

        LinearLayout vertical = operation(owner, "vertical", "上下连续滚动", "页面平滑衔接，适合连续阅读", false, false);
        LinearLayout sideways = operation(owner, "horizontal", "左右翻页", "整页展示，滑动时平滑切换", false, false);
        IconButton verticalCheck = decoration(owner, "check", AppColors.ACCENT_DARK);
        IconButton horizontalCheck = decoration(owner, "check", AppColors.ACCENT_DARK);
        vertical.addView(verticalCheck, new LinearLayout.LayoutParams(dp(owner, 24), dp(owner, 30)));
        sideways.addView(horizontalCheck, new LinearLayout.LayoutParams(dp(owner, 24), dp(owner, 30)));
        Runnable refresh = () -> {
            choice(vertical, !values[0], "上下连续滚动，页面平滑衔接");
            choice(sideways, values[0], "左右翻页，整页展示并平滑切换");
            verticalCheck.setVisibility(values[0] ? View.INVISIBLE : View.VISIBLE);
            horizontalCheck.setVisibility(values[0] ? View.VISIBLE : View.INVISIBLE);
        };
        vertical.setOnClickListener(v -> {
            if (!values[0]) return;
            values[0] = false;
            refresh.run();
            if (callback != null) callback.changed(values[0], values[1]);
        });
        sideways.setOnClickListener(v -> {
            if (values[0]) return;
            values[0] = true;
            refresh.run();
            if (callback != null) callback.changed(values[0], values[1]);
        });
        refresh.run();
        dialog.body.addView(vertical, new LinearLayout.LayoutParams(-1, -2));
        gap(dialog.body, 10);
        dialog.body.addView(sideways, new LinearLayout.LayoutParams(-1, -2));
        gap(dialog.body, 24);
        section(dialog.body, "快捷手势", null);
        gap(dialog.body, 12);

        LinearLayout gesture = operation(owner, "undo", "双指轻点撤销", "撤销当前页上一步编辑", false, false);
        gesture.setBackground(ripple(owner, AppColors.BACKGROUND, 15));
        Switch toggle = new Switch(owner);
        toggle.setChecked(values[1]);
        toggle.setText("");
        toggle.setContentDescription("双指轻点撤销");
        toggle.setMinimumWidth(dp(owner, 48));
        toggle.setMinimumHeight(dp(owner, 48));
        toggle.setPadding(dp(owner, 5), 0, 0, 0);
        toggle.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{AppColors.ACCENT, 0xffbaa694}));
        toggle.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xffffcc9f, AppColors.BORDER}));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            values[1] = checked;
            if (callback != null) callback.changed(values[0], values[1]);
        });
        gesture.addView(toggle, new LinearLayout.LayoutParams(-2, dp(owner, 48)));
        gesture.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
        dialog.body.addView(gesture, new LinearLayout.LayoutParams(-1, -2));
        gap(dialog.body, 10);
        TextView note = text(owner, "两根手指同时短按并抬起即可撤销。双指移动仍用于缩放与平移。", 12, AppColors.MUTED);
        note.setLineSpacing(dp(owner, 3), 1f);
        dialog.body.addView(note, new LinearLayout.LayoutParams(-1, -2));
        gap(dialog.body, 24);
        singleButton(dialog, "完成", dialog::dismiss);
        dialog.show();
        return dialog;
    }

    public static Dialog confirmClear(Activity owner, int page1Based, Runnable confirmed) {
        CardDialog dialog = new CardDialog(owner);
        title(dialog, "清空本页批注？", "只影响当前页面的手写内容", "eraser", true, true);
        gap(dialog.body, 20);
        LinearLayout page = row(owner);
        page.setPadding(dp(owner, 14), dp(owner, 15), dp(owner, 14), dp(owner, 15));
        page.setBackground(shape(owner, AppColors.BACKGROUND, 14));
        page.addView(decoration(owner, "page", AppColors.ACCENT_DARK), new LinearLayout.LayoutParams(dp(owner, 28), dp(owner, 32)));
        TextView label = text(owner, "第 " + Math.max(1, page1Based) + " 页", 16, AppColors.INK);
        label.setTypeface(null, 1);
        label.setPadding(dp(owner, 10), 0, 0, 0);
        page.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        dialog.body.addView(page, new LinearLayout.LayoutParams(-1, -2));
        gap(dialog.body, 17);
        TextView note = text(owner, "清空后可立即撤销；即使离开页面，也可恢复最近一次清空前的内容。其他页面不受影响。", 13, AppColors.MUTED);
        note.setLineSpacing(dp(owner, 4), 1f);
        dialog.body.addView(note);
        gap(dialog.body, 24);
        buttons(dialog, "清空批注", WARNING_SOFT, WARNING, () -> dialog.complete(confirmed));
        dialog.show();
        return dialog;
    }

    /** Expanded menu used by ReaderActions integrations; the legacy overload stays source compatible. */
    public static Dialog menu(Activity owner,boolean horizontal,Runnable settings,Runnable fit,
            Runnable navigate,Runnable advanced,Runnable export,boolean canRestore,Runnable restore,
            Runnable clear,Runnable help){
        return menu(owner,horizontal,settings,fit,null,navigate,advanced,null,null,export,canRestore,restore,clear,help);
    }

    public static Dialog menu(Activity owner,boolean horizontal,Runnable settings,Runnable fit,
            Runnable navigate,Runnable advanced,Runnable selectText,Runnable source,Runnable export,
            boolean canRestore,Runnable restore,Runnable clear,Runnable help){
        return menu(owner,horizontal,settings,fit,null,navigate,advanced,selectText,source,export,canRestore,restore,clear,help);
    }

    public static Dialog menu(Activity owner,boolean horizontal,Runnable settings,Runnable fit,Runnable zoom,
            Runnable navigate,Runnable advanced,Runnable selectText,Runnable source,Runnable export,
            boolean canRestore,Runnable restore,Runnable clear,Runnable help){
        return menu(owner,horizontal,settings,fit,zoom,navigate,advanced,selectText,source,null,export,canRestore,restore,clear,help);
    }

    public static Dialog menu(Activity owner,boolean horizontal,Runnable settings,Runnable fit,Runnable zoom,
            Runnable navigate,Runnable advanced,Runnable selectText,Runnable source,Runnable recognize,Runnable export,
            boolean canRestore,Runnable restore,Runnable clear,Runnable help){
        CardDialog dialog=new CardDialog(owner);title(dialog,"阅读工具","导航、批注整理与可靠导出","settings",false,true);gap(dialog.body,16);
        action(dialog,"book","目录、缩略图与书签","浏览原文目录、页缩略图、手动书签和批注",false,navigate);gap(dialog.body,8);
        action(dialog,"pen","批注增强工具","圆珠笔、铅笔、局部橡皮、套索与规则图形",false,advanced);gap(dialog.body,8);
        if(selectText!=null){action(dialog,"select","选择 PDF 文字","拖动文字后复制、标亮或摘录到笔记",false,selectText);gap(dialog.body,8);}
        if(recognize!=null){action(dialog,"text","识别扫描文字（OCR）","离线识别中英文，导出可搜索 PDF 副本",false,recognize);gap(dialog.body,8);}
        if(source!=null){action(dialog,"note","摘录到笔记","保存当前页或选中文字，从笔记返回原页",false,source);gap(dialog.body,8);}
        action(dialog,"note","导出与分享","保留文字层的带批注 PDF，或导出当前页 PNG",false,export);gap(dialog.body,8);
        action(dialog,"settings","阅读设置",horizontal?"左右翻页 · 手势与阅读偏好":"上下滚动 · 手势与阅读偏好",false,settings);gap(dialog.body,8);
        if(zoom!=null){action(dialog,"fit","缩放比例","50%—300%，可输入数值或拖动滑条",false,zoom);gap(dialog.body,8);}
        action(dialog,"fit",horizontal?"恢复整页显示":"恢复适合宽度","重置页面缩放与位置",false,fit);gap(dialog.body,8);
        action(dialog,"info","操作说明","翻页、搜索、文字选择与批注提示",false,help);
        if(canRestore){gap(dialog.body,14);action(dialog,"undo","恢复最近清空","合并清空前内容与清空后新增笔迹",false,restore);}gap(dialog.body,14);
        action(dialog,"eraser","清空本页批注","保留最近一次清空恢复快照",true,clear);dialog.show();return dialog;
    }

    public static Dialog textSelection(Activity owner,String quote,Runnable copy,Runnable highlight,Runnable source){
        CardDialog dialog=new CardDialog(owner);title(dialog,"已选择文字","复制、标亮，或带页码摘录到笔记","select",false,true);gap(dialog.body,16);
        TextView preview=text(owner,safe(quote),14,AppColors.INK);preview.setMaxLines(5);preview.setLineSpacing(dp(owner,3),1f);preview.setPadding(dp(owner,13),dp(owner,12),dp(owner,13),dp(owner,12));preview.setBackground(shape(owner,AppColors.BACKGROUND,13));dialog.body.addView(preview,new LinearLayout.LayoutParams(-1,-2));gap(dialog.body,14);
        action(dialog,"note","复制文字","复制到系统剪贴板",false,copy);gap(dialog.body,8);
        action(dialog,"marker","文字高亮","保存为可定位的文字层高亮，与手绘荧光笔分开",false,highlight);gap(dialog.body,8);
        action(dialog,"book","摘录到笔记","保留文档、页码与原文，可从笔记返回此处",false,source);dialog.show();return dialog;
    }

    public static Dialog bookmark(Activity owner,int page1Based,String initial,BookmarkSaved callback){
        CardDialog dialog=new CardDialog(owner);title(dialog,"添加书签","第 "+Math.max(1,page1Based)+" 页","book",false,true);gap(dialog.body,16);
        EditText input=new EditText(owner);input.setSingleLine(true);input.setText(safe(initial));input.setSelectAllOnFocus(true);input.setHint("例如：第三章重点");input.setTextSize(15);input.setTextColor(AppColors.INK);input.setHintTextColor(AppColors.MUTED);GradientDrawable field=shape(owner,AppColors.BACKGROUND,12);field.setStroke(dp(owner,1),AppColors.BORDER);input.setBackground(field);input.setPadding(dp(owner,12),0,dp(owner,12),0);dialog.body.addView(input,new LinearLayout.LayoutParams(-1,dp(owner,50)));gap(dialog.body,18);
        buttons(dialog,"保存书签",AppColors.ACCENT,AppColors.ON_ACCENT,()->{String value=input.getText().toString().trim();if(value.length()==0){input.setError("请输入书签名称");return;}dialog.complete(callback==null?null:()->callback.save(value.length()>240?value.substring(0,240):value));});dialog.show();return dialog;
    }

    public static Dialog advancedTools(Activity owner,boolean hasSelection,AdvancedToolSelection callback,Runnable copy,Runnable delete){
        CardDialog dialog=new CardDialog(owner);title(dialog,"批注增强工具","多种文具、局部擦除、套索与规则图形","pen",false,true);gap(dialog.body,16);
        int[] tools={PdfReaderView.TOOL_BALLPOINT,PdfReaderView.TOOL_PENCIL,PdfReaderView.TOOL_LOCAL_ERASER,PdfReaderView.TOOL_LASSO,PdfReaderView.TOOL_LINE,PdfReaderView.TOOL_RECTANGLE,PdfReaderView.TOOL_ELLIPSE,PdfReaderView.TOOL_ARROW};
        String[] icons={"ballpoint","pencil","eraser","select","line","rectangle","circle","arrow"},names={"圆珠笔","铅笔","局部橡皮","套索选择","直线","矩形","椭圆","箭头"},details={"固定线宽，不随笔压变化","柔和透明笔迹，支持笔压","只擦除笔迹经过的局部，不删除整笔","圈选后再次拖动即可移动","拖动起点与终点绘制","拖动两角绘制","拖动外接框绘制","拖动绘制带箭头直线"};
        for(int i=0;i<tools.length;i++){final int tool=tools[i];action(dialog,icons[i],names[i],details[i],false,()->{if(callback!=null)callback.selected(tool);});if(i+1<tools.length)gap(dialog.body,8);}
        if(hasSelection){gap(dialog.body,16);section(dialog.body,"当前套索选择","移动由页面拖动完成");gap(dialog.body,8);action(dialog,"plus","复制所选笔迹","复制后轻微偏移，仍可继续移动",false,copy);gap(dialog.body,8);action(dialog,"trash","删除所选笔迹","可使用撤销恢复",true,delete);}
        dialog.show();return dialog;
    }

    public static Dialog jump(Activity owner, int current1Based, int total, PageSelection callback) {
        CardDialog dialog = new CardDialog(owner);
        int count = Math.max(1, total);
        int current = Math.max(1, Math.min(current1Based, count));
        title(dialog, "跳转页面", "直接前往想读的那一页", "page", false, true);
        gap(dialog.body, 20);
        LinearLayout status = row(owner);
        status.setPadding(dp(owner, 13), dp(owner, 11), dp(owner, 13), dp(owner, 11));
        status.setBackground(shape(owner, AppColors.BACKGROUND, 12));
        status.addView(text(owner, "当前第 " + current + " 页", 13, AppColors.ACCENT_DARK), new LinearLayout.LayoutParams(0, -2, 1));
        status.addView(text(owner, "共 " + count + " 页", 12, AppColors.MUTED));
        dialog.body.addView(status, new LinearLayout.LayoutParams(-1, -2));
        gap(dialog.body, 20);
        dialog.body.addView(text(owner, "目标页码", 12, AppColors.MUTED));
        gap(dialog.body, 8);
        EditText input = new EditText(owner);
        input.setSingleLine(true);
        input.setTextSize(18);
        input.setTextColor(AppColors.INK);
        input.setHintTextColor(AppColors.MUTED);
        input.setHint("输入 1 – " + count);
        input.setText(String.valueOf(current));
        input.setSelectAllOnFocus(true);
        input.setContentDescription("目标页码，1 到 " + count);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(10)});
        input.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setPadding(dp(owner, 14), dp(owner, 13), dp(owner, 14), dp(owner, 13));
        GradientDrawable inputShape = shape(owner, AppColors.BACKGROUND, 14);
        inputShape.setStroke(dp(owner, 1), AppColors.BORDER);
        input.setBackground(inputShape);
        input.setMinimumHeight(dp(owner, 52));
        dialog.body.addView(input, new LinearLayout.LayoutParams(-1, -2));
        TextView error = text(owner, "", 12, WARNING);
        error.setVisibility(View.GONE);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(-1, -2);
        errorParams.topMargin = dp(owner, 8);
        dialog.body.addView(error, errorParams);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int before, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int after) { error.setVisibility(View.GONE); }
            public void afterTextChanged(Editable s) { }
        });
        Runnable go = () -> {
            try {
                int selected = Integer.parseInt(input.getText().toString().trim());
                if (selected < 1 || selected > count) throw new NumberFormatException();
                dialog.complete(callback == null ? null : () -> callback.selected(selected - 1));
            } catch (NumberFormatException invalid) {
                error.setText("请输入 1 到 " + count + " 之间的页码");
                error.setVisibility(View.VISIBLE);
                input.requestFocus();
            }
        };
        input.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) {
                go.run();
                return true;
            }
            return false;
        });
        gap(dialog.body, 24);
        buttons(dialog, "前往", AppColors.ACCENT, AppColors.ON_ACCENT, go);
        dialog.show();
        input.requestFocus();
        input.post(() -> {
            if (!dialog.isShowing() || owner.isFinishing() || owner.isDestroyed()) return;
            InputMethodManager keyboard = (InputMethodManager) owner.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
        return dialog;
    }

    public static Dialog help(Activity owner) {
        CardDialog dialog = new CardDialog(owner);
        title(dialog, "阅读与手写", "几个顺手的小操作", "info", false, true);
        gap(dialog.body, 20);
        helpCard(dialog, "book", "选择阅读方式", "在阅读设置中切换上下连续滚动或左右翻页。底部页码可直接跳转。两种翻页模式互斥。");
        gap(dialog.body, 10);
        helpCard(dialog, "down", "留出更多阅读空间", "滑动阅读时标题栏会自动收起，画笔工具行保留。点击最右侧箭头再次展开。");
        gap(dialog.body, 10);
        helpCard(dialog, "stylus", "手写与调色", "默认仅手写笔绘制，单指用于浏览。长按画笔、荧光笔或橡皮，也可再次点击已选中的工具，调整颜色和粗细。");
        gap(dialog.body, 10);
        helpCard(dialog, "undo", "双指快捷操作", "两根手指同时轻点页面，撤销本页上一步编辑。可在阅读设置中关闭。双指移动用于缩放与平移。");
        gap(dialog.body, 10);
        helpCard(dialog, "search", "搜索 PDF 文字", "点击工具行右侧、下拉箭头左边的放大镜。输入文字后点击结果跳页。纯图片扫描件暂不支持文字搜索。");
        gap(dialog.body, 10);
        helpCard(dialog, "page", "保存与恢复", "批注自动保存在晴阅内。工具栏可撤销或重做本页编辑，清空本页批注后也可通过撤销恢复。");
        gap(dialog.body, 22);
        singleButton(dialog, "知道了", dialog::dismiss);
        dialog.show();
        return dialog;
    }

    public static Dialog message(Activity owner, String title, String message, boolean error) {
        CardDialog dialog = new CardDialog(owner);
        title(dialog, safe(title), error ? "请根据提示重试" : null, error ? "info" : "check", error, true);
        gap(dialog.body, 20);
        LinearLayout detail = column(owner);
        detail.setPadding(dp(owner, 15), dp(owner, 14), dp(owner, 15), dp(owner, 14));
        detail.setBackground(shape(owner, error ? WARNING_SOFT : AppColors.BACKGROUND, 14));
        TextView content = text(owner, safe(message), 14, AppColors.INK);
        content.setLineSpacing(dp(owner, 4), 1f);
        detail.addView(content, new LinearLayout.LayoutParams(-1, -2));
        dialog.body.addView(detail, new LinearLayout.LayoutParams(-1, -2));
        gap(dialog.body, 24);
        singleButton(dialog, "知道了", dialog::dismiss);
        dialog.show();
        return dialog;
    }

    public static Dialog progress(Activity owner, String title, String message) {
        CardDialog dialog = new CardDialog(owner);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        title(dialog, safe(title), "请稍候", "book", false, false);
        gap(dialog.body, 22);
        ProgressBar progress = new ProgressBar(owner, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(AppColors.ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(AppColors.SOFT));
        progress.setContentDescription(safe(title));
        dialog.body.addView(progress, new LinearLayout.LayoutParams(-1, dp(owner, 6)));
        gap(dialog.body, 15);
        TextView detail = text(owner, safe(message), 13, AppColors.MUTED);
        detail.setLineSpacing(dp(owner, 3), 1f);
        dialog.body.addView(detail, new LinearLayout.LayoutParams(-1, -2));
        dialog.show();
        return dialog;
    }

    private static void title(CardDialog dialog, String title, String subtitle, String kind, boolean warning, boolean closable) {
        Activity owner = dialog.owner;
        LinearLayout heading = row(owner);
        IconButton icon = decoration(owner, kind, warning ? WARNING : AppColors.ACCENT_DARK);
        icon.setBackground(shape(owner, warning ? WARNING_SOFT : AppColors.SOFT, 12));
        heading.addView(icon, new LinearLayout.LayoutParams(dp(owner, 42), dp(owner, 42)));
        LinearLayout labels = column(owner);
        labels.setPadding(dp(owner, 11), 0, dp(owner, 3), 0);
        TextView name = text(owner, title, 18, AppColors.INK);
        name.setTypeface(null, 1);
        labels.addView(name);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView detail = text(owner, subtitle, 11, AppColors.MUTED);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = dp(owner, 5);
            labels.addView(detail, params);
        }
        heading.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (closable) {
            IconButton close = new IconButton(owner, "close", "关闭");
            close.setTint(AppColors.MUTED);
            close.setOnClickListener(v -> dialog.dismiss());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(owner, 44), dp(owner, 44));
            params.gravity = Gravity.TOP;
            heading.addView(close, params);
        }
        dialog.body.addView(heading, new LinearLayout.LayoutParams(-1, -2));
    }

    private static void section(LinearLayout body, String title, String subtitle) {
        TextView label = text(body.getContext(), title, 13, AppColors.INK);
        label.setTypeface(null, 1);
        body.addView(label);
        if (subtitle != null) {
            gap(body, 5);
            body.addView(text(body.getContext(), subtitle, 11, AppColors.MUTED));
        }
    }

    private static void action(CardDialog dialog, String icon, String title, String subtitle, boolean warning, Runnable action) {
        LinearLayout row = operation(dialog.owner, icon, title, subtitle, warning, true);
        row.setBackground(ripple(dialog.owner, warning ? WARNING_SOFT : AppColors.BACKGROUND, 15));
        row.setFocusable(true);
        row.setContentDescription(title + "，" + subtitle);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        row.setOnClickListener(v -> dialog.complete(action));
        dialog.body.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private static LinearLayout operation(Activity owner, String icon, String title, String subtitle, boolean warning, boolean arrow) {
        LinearLayout row = row(owner);
        row.setPadding(dp(owner, 12), dp(owner, 12), dp(owner, 10), dp(owner, 12));
        row.setMinimumHeight(dp(owner, 74));
        IconButton symbol = decoration(owner, icon, warning ? WARNING : AppColors.ACCENT_DARK);
        symbol.setBackground(shape(owner, warning ? 0xffffdfd7 : AppColors.SOFT, 12));
        row.addView(symbol, new LinearLayout.LayoutParams(dp(owner, 40), dp(owner, 40)));
        LinearLayout labels = column(owner);
        labels.setPadding(dp(owner, 12), 0, dp(owner, 6), 0);
        TextView heading = text(owner, title, 15, warning ? WARNING : AppColors.INK);
        heading.setTypeface(null, 1);
        labels.addView(heading);
        TextView detail = text(owner, subtitle, 11, AppColors.MUTED);
        detail.setLineSpacing(dp(owner, 2), 1f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(owner, 4);
        labels.addView(detail, params);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (arrow) row.addView(decoration(owner, "right", warning ? 0xffbe9388 : AppColors.MUTED), new LinearLayout.LayoutParams(dp(owner, 24), dp(owner, 30)));
        return row;
    }

    private static void choice(LinearLayout view, boolean selected, String description) {
        GradientDrawable background = shape(view.getContext(), selected ? (AppColors.isClassic() ? 0xffffedde : AppColors.SOFT) : AppColors.BACKGROUND, 15);
        background.setStroke(dp(view.getContext(), 1), selected ? AppColors.ACCENT : AppColors.BORDER);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ad4a14), background, null));
        view.setSelected(selected);
        view.setFocusable(true);
        view.setContentDescription(description);
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.RadioButton");
                info.setCheckable(true);
                info.setChecked(host.isSelected());
            }
        });
    }

    private static void helpCard(CardDialog dialog, String kind, String title, String explanation) {
        Activity owner = dialog.owner;
        LinearLayout card = column(owner);
        card.setPadding(dp(owner, 14), dp(owner, 12), dp(owner, 14), dp(owner, 14));
        card.setBackground(shape(owner, AppColors.BACKGROUND, 15));
        LinearLayout heading = row(owner);
        IconButton icon = decoration(owner, kind, AppColors.ACCENT_DARK);
        icon.setBackground(shape(owner, AppColors.SOFT, 10));
        heading.addView(icon, new LinearLayout.LayoutParams(dp(owner, 34), dp(owner, 34)));
        TextView label = text(owner, title, 14, AppColors.INK);
        label.setTypeface(null, 1);
        label.setPadding(dp(owner, 10), 0, 0, 0);
        heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        gap(card, 9);
        TextView content = text(owner, explanation, 12, AppColors.MUTED);
        content.setLineSpacing(dp(owner, 3), 1f);
        card.addView(content, new LinearLayout.LayoutParams(-1, -2));
        dialog.body.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    private static void buttons(CardDialog dialog, String title, int background, int foreground, Runnable action) {
        LinearLayout row = row(dialog.owner);
        Button cancel = button(dialog.owner, "取消", AppColors.BACKGROUND, AppColors.MUTED);
        cancel.setOnClickListener(v -> dialog.dismiss());
        row.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        Button submit = button(dialog.owner, title, background, foreground);
        submit.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.leftMargin = dp(dialog.owner, 12);
        row.addView(submit, params);
        dialog.body.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private static void singleButton(CardDialog dialog, String title, Runnable action) {
        Button button = button(dialog.owner, title, AppColors.ACCENT, AppColors.ON_ACCENT);
        button.setOnClickListener(v -> action.run());
        dialog.body.addView(button, new LinearLayout.LayoutParams(-1, -2));
    }

    private static Button button(Activity owner, String title, int background, int foreground) {
        Button button = new Button(owner);
        button.setText(title);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTextColor(foreground);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(owner, 48));
        button.setMinimumHeight(dp(owner, 48));
        button.setPadding(dp(owner, 12), dp(owner, 10), dp(owner, 12), dp(owner, 10));
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setBackground(ripple(owner, background, 14));
        return button;
    }

    private static IconButton decoration(Activity owner, String kind, int color) {
        IconButton icon = new IconButton(owner, kind, "");
        icon.setTint(color);
        icon.setOnLongClickListener(null);
        icon.setLongClickable(false);
        icon.setClickable(false);
        icon.setFocusable(false);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static int dp(Context context, float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static LinearLayout column(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private static LinearLayout row(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private static TextView text(Context context, String value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }
    private static GradientDrawable shape(Context context, int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radius));
        if (!AppColors.isClassic() && radius > 0)
            drawable.setStroke(dp(context, 1), color == AppColors.ACCENT ? AppColors.ACCENT : AppColors.BORDER);
        return drawable;
    }
    private static RippleDrawable ripple(Context context, int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x22ad4a14), shape(context, color, radius), null);
    }
    private static void gap(LinearLayout parent, int height) {
        parent.addView(new View(parent.getContext()), new LinearLayout.LayoutParams(1, dp(parent.getContext(), height)));
    }

    /** The natural card size, constrained to the activity and the keyboard's visible frame. */
    private static final class CardDialog extends Dialog {
        final Activity owner;
        final LinearLayout body;
        private ViewTreeObserver.OnGlobalLayoutListener listener;
        private int width, height;
        private boolean completed;

        CardDialog(Activity owner) {
            super(owner, resolveTheme(owner));
            this.owner = owner;
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            setCanceledOnTouchOutside(true);
            body = column(owner);
            body.setPadding(dp(owner, 20), dp(owner, 21), dp(owner, 20), dp(owner, 22));
            body.setFocusableInTouchMode(true);
            ScrollView scroll = new ScrollView(owner);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setFillViewport(false);
            scroll.setBackground(shape(owner, AppColors.SURFACE, 22));
            scroll.setClipToOutline(true);
            scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
            setContentView(scroll, new ViewGroup.LayoutParams(-1, -1));
            Window window = getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setGravity(Gravity.CENTER);
                window.setDimAmount(0.25f);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            }
        }

        @Override public void show() {
            super.show();
            body.requestFocus();
            if (getWindow() != null && listener == null) {
                listener = this::fitWindow;
                getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(listener);
            }
            fitWindow();
        }

        void complete(Runnable action) {
            if(completed||!isShowing())return;
            completed=true;
            dismiss();
            if (!owner.isFinishing() && !owner.isDestroyed() && action != null) action.run();
        }

        private void fitWindow() {
            if (getWindow() == null) return;
            Rect visible = new Rect();
            getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
            View host = owner.getWindow().getDecorView();
            if (visible.width() <= 0 || visible.height() <= 0) host.getWindowVisibleDisplayFrame(visible);
            int[] origin = new int[2];
            host.getLocationOnScreen(origin);
            Rect bounded = new Rect(visible);
            if (host.getWidth() > 0 && host.getHeight() > 0 && bounded.intersect(origin[0], origin[1], origin[0] + host.getWidth(), origin[1] + host.getHeight())) visible.set(bounded);
            if (visible.width() <= 0 || visible.height() <= 0) return;
            int nextWidth = Math.max(1, Math.min(dp(owner, 390), visible.width() - dp(owner, 32)));
            body.measure(View.MeasureSpec.makeMeasureSpec(nextWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int nextHeight = Math.max(1, Math.min(body.getMeasuredHeight(), visible.height() - dp(owner, 32)));
            if (nextWidth != width || nextHeight != height) {
                width = nextWidth;
                height = nextHeight;
                getWindow().setLayout(width, height);
            }
        }

        private void release() {
            if (getWindow() != null && listener != null) {
                ViewTreeObserver observer = getWindow().getDecorView().getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(listener);
                listener = null;
            }
            View focused = getCurrentFocus();
            if (focused instanceof EditText) {
                InputMethodManager keyboard = (InputMethodManager) owner.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            }
        }

        @Override public void dismiss() { release(); super.dismiss(); }
        @Override protected void onStop() { release(); super.onStop(); }

        private static int resolveTheme(Context context) {
            TypedValue value = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.alertDialogTheme, value, true);
            return value.resourceId;
        }
    }
}
