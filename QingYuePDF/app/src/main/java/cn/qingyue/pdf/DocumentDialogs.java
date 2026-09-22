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
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
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
import android.widget.TextView;

/** Compact document cards shared by the library's manage, rename and remove flows. */
public final class DocumentDialogs {
    private static final int REMOVE = 0xffb75448;
    private static final int REMOVE_SOFT = 0xffffeeea;
    private DocumentDialogs() { }

    public interface Rename { void changed(String name); }

    public static Dialog actions(Activity activity, String name, String metadata,
            int page1Based, int total, Runnable read, Runnable rename, Runnable remove) {
        return actions(activity,name,metadata,page1Based,total,false,"PDF",read,rename,remove);
    }
    public static Dialog actions(Activity activity, String name, String metadata,
            int page1Based, int total, boolean note, String format, Runnable read, Runnable rename, Runnable remove) {
        CardDialog dialog = new CardDialog(activity);
        LinearLayout body = dialog.body;
        LinearLayout heading = row(activity);
        TextView badge = text(activity, note?"笔记":format, 14, AppColors.ACCENT_DARK);
        badge.setTypeface(null, 1);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable cover = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xffffedda, 0xffffd7b6});
        cover.setCornerRadius(dp(activity, 13));
        badge.setBackground(cover);
        heading.addView(badge, new LinearLayout.LayoutParams(dp(activity, 54), dp(activity, 66)));

        LinearLayout document = column(activity);
        document.setPadding(dp(activity, 13), dp(activity, 3), dp(activity, 4), dp(activity, 3));
        TextView title = text(activity, safe(name), 17, AppColors.INK);
        title.setTypeface(null, 1);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        document.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView detail = text(activity, safe(metadata), 12, AppColors.MUTED);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(activity, 6);
        document.addView(detail, detailParams);
        heading.addView(document, new LinearLayout.LayoutParams(0, -2, 1));
        addClose(dialog, heading);
        body.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        gap(body, 22);

        int count = Math.max(1, total);
        int page = Math.max(1, Math.min(page1Based, count));
        LinearLayout reading = row(activity);
        TextView progressLabel = text(activity, "阅读至第 " + page + " / " + count + " 页", 12, AppColors.MUTED);
        reading.addView(progressLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView percent = text(activity, Math.round(page * 100f / count) + "%", 12, AppColors.ACCENT_DARK);
        reading.addView(percent, new LinearLayout.LayoutParams(-2, -2));
        if(!note)body.addView(reading);
        if(!note)gap(body, 8);
        ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgress(Math.round(page * 1000f / count));
        progress.setProgressTintList(ColorStateList.valueOf(AppColors.ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(AppColors.SOFT));
        if(!note)body.addView(progress, new LinearLayout.LayoutParams(-1, dp(activity, 4)));
        if(!note)gap(body, 19);

        LinearLayout continueButton = row(activity);
        continueButton.setGravity(Gravity.CENTER);
        continueButton.setPadding(dp(activity, 12), dp(activity, 9), dp(activity, 12), dp(activity, 9));
        continueButton.setMinimumHeight(dp(activity, 52));
        continueButton.addView(decoration(activity, "book", AppColors.SURFACE), new LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 30)));
        TextView continueLabel = text(activity, note?"继续记录":"继续阅读", 16, AppColors.SURFACE);
        continueLabel.setTypeface(null, 1);
        continueLabel.setPadding(dp(activity, 9), 0, 0, 0);
        continueButton.addView(continueLabel);
        makeAction(continueButton, AppColors.ACCENT, note?"继续记录":"继续阅读", () -> dialog.complete(read));
        body.addView(continueButton, new LinearLayout.LayoutParams(-1, -2));
        gap(body, 18);

        LinearLayout renameRow = operation(activity, "rename", "重命名", "修改文档显示名称", false);
        makeAction(renameRow, AppColors.BACKGROUND, "重命名，修改文档显示名称", () -> dialog.complete(rename));
        body.addView(renameRow, new LinearLayout.LayoutParams(-1, -2));
        gap(body, 10);
        String removeTitle=note?"删除笔记":"移除文档",removeHint=note?"同时删除笔迹、图形与插入的图片":"原始文档文件不受影响";
        LinearLayout removeRow = operation(activity, "trash", removeTitle, removeHint, true);
        makeAction(removeRow, REMOVE_SOFT, removeTitle+"，"+removeHint, () -> dialog.complete(remove));
        body.addView(removeRow, new LinearLayout.LayoutParams(-1, -2));
        dialog.show();
        return dialog;
    }

    public static Dialog rename(Activity activity, String oldName, Rename callback) {
        CardDialog dialog = new CardDialog(activity);
        addTitle(dialog, "重命名文档", "rename", AppColors.ACCENT_DARK);
        gap(dialog.body, 10);
        TextView hint = text(activity, "修改在晴阅中的显示名称，文档内容不会改变。", 13, AppColors.MUTED);
        hint.setLineSpacing(dp(activity, 3), 1f);
        dialog.body.addView(hint);
        gap(dialog.body, 22);
        dialog.body.addView(text(activity, "文档名称", 12, AppColors.MUTED));
        gap(dialog.body, 8);
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setTextSize(16);
        input.setTextColor(AppColors.INK);
        input.setHintTextColor(AppColors.MUTED);
        input.setHint("输入文档名称");
        input.setContentDescription("文档名称，最多 150 个字符");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(150)});
        input.setText(safe(oldName));
        input.setSelectAllOnFocus(true);
        input.setPadding(dp(activity, 14), dp(activity, 13), dp(activity, 14), dp(activity, 13));
        GradientDrawable inputBackground = shape(activity, AppColors.BACKGROUND, 14);
        inputBackground.setStroke(dp(activity, 1), AppColors.BORDER);
        input.setBackground(inputBackground);
        dialog.body.addView(input, new LinearLayout.LayoutParams(-1, -2));
        TextView length = text(activity, input.length() + " / 150", 11, AppColors.MUTED);
        length.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams lengthParams = new LinearLayout.LayoutParams(-1, -2);
        lengthParams.topMargin = dp(activity, 7);
        dialog.body.addView(length, lengthParams);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                length.setText(s.length() + " / 150");
                if (hasVisibleName(s.toString())) input.setError(null);
            }
            public void afterTextChanged(Editable text) { }
        });
        Runnable save = () -> {
            String name = input.getText().toString().trim();
            if (!hasVisibleName(name)) { input.setError("请输入文档名称"); input.requestFocus(); return; }
            dialog.complete(callback == null ? null : () -> callback.changed(name));
        };
        input.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) { save.run(); return true; }
            return false;
        });
        gap(dialog.body, 24);
        addButtons(dialog, "保存", AppColors.ACCENT, AppColors.SURFACE, save);
        dialog.show();
        input.requestFocus();
        input.post(() -> {
            if (!dialog.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
            InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
        return dialog;
    }

    public static Dialog remove(Activity activity, String name, Runnable confirmed) {
        return remove(activity,name,false,confirmed);
    }
    public static Dialog remove(Activity activity, String name, boolean note, Runnable confirmed) {
        CardDialog dialog = new CardDialog(activity);
        addTitle(dialog, note?"删除这本笔记？":"移除这份文档？", "trash", REMOVE);
        gap(dialog.body, 17);
        LinearLayout document = column(activity);
        document.setPadding(dp(activity, 15), dp(activity, 14), dp(activity, 15), dp(activity, 14));
        document.setBackground(shape(activity, AppColors.BACKGROUND, 14));
        TextView filename = text(activity, safe(name), 15, AppColors.INK);
        filename.setTypeface(null, 1);
        filename.setMaxLines(2);
        filename.setEllipsize(TextUtils.TruncateAt.END);
        document.addView(filename);
        dialog.body.addView(document, new LinearLayout.LayoutParams(-1, -2));
        gap(dialog.body, 17);
        TextView message = text(activity, note?"将永久删除这本笔记，包括笔迹、图形和插入的图片。":"将删除晴阅内的文档副本、手写批注和阅读进度。", 14, AppColors.INK);
        message.setLineSpacing(dp(activity, 4), 1f);
        dialog.body.addView(message);
        gap(dialog.body, 9);
        TextView original = text(activity, note?"此操作无法撤销。":"原始文档文件不受影响。", 13, AppColors.MUTED);
        dialog.body.addView(original);
        gap(dialog.body, 25);
        addButtons(dialog, note?"删除笔记":"移除文档", REMOVE_SOFT, REMOVE, () -> dialog.complete(confirmed));
        dialog.show();
        return dialog;
    }

    private static LinearLayout operation(Activity activity, String icon, String title, String subtitle, boolean remove) {
        LinearLayout row = row(activity);
        row.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 8), dp(activity, 12));
        row.setMinimumHeight(dp(activity, 74));
        IconButton symbol = decoration(activity, icon, remove ? REMOVE : AppColors.ACCENT_DARK);
        symbol.setBackground(shape(activity, remove ? 0xffffdfd7 : AppColors.SOFT, 12));
        row.addView(symbol, new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40)));
        LinearLayout label = column(activity);
        label.setPadding(dp(activity, 12), 0, dp(activity, 4), 0);
        TextView titleView = text(activity, title, 15, remove ? REMOVE : AppColors.INK);
        titleView.setTypeface(null, 1);
        label.addView(titleView);
        TextView subtitleView = text(activity, subtitle, 11, remove ? 0xff9b7066 : AppColors.MUTED);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(activity, 4);
        label.addView(subtitleView, subtitleParams);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(decoration(activity, "right", remove ? 0xffbe9388 : AppColors.MUTED), new LinearLayout.LayoutParams(dp(activity, 24), dp(activity, 30)));
        return row;
    }

    private static void addTitle(CardDialog dialog, String title, String kind, int color) {
        LinearLayout heading = row(dialog.owner);
        IconButton symbol = decoration(dialog.owner, kind, color);
        symbol.setBackground(shape(dialog.owner, color == REMOVE ? REMOVE_SOFT : AppColors.SOFT, 12));
        heading.addView(symbol, new LinearLayout.LayoutParams(dp(dialog.owner, 42), dp(dialog.owner, 42)));
        TextView label = text(dialog.owner, title, 18, AppColors.INK);
        label.setTypeface(null, 1);
        label.setPadding(dp(dialog.owner, 11), 0, 0, 0);
        heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        addClose(dialog, heading);
        dialog.body.addView(heading, new LinearLayout.LayoutParams(-1, -2));
    }

    private static void addClose(CardDialog dialog, LinearLayout heading) {
        IconButton close = new IconButton(dialog.owner, "close", "关闭");
        close.setTint(AppColors.MUTED);
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(dialog.owner, 40), dp(dialog.owner, 44));
        params.gravity = Gravity.TOP;
        heading.addView(close, params);
    }

    private static void addButtons(CardDialog dialog, String title, int background, int foreground, Runnable action) {
        LinearLayout buttons = row(dialog.owner);
        Button cancel = button(dialog.owner, "取消", AppColors.BACKGROUND, AppColors.MUTED);
        cancel.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        Button positive = button(dialog.owner, title, background, foreground);
        positive.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams positiveParams = new LinearLayout.LayoutParams(0, -2, 1);
        positiveParams.leftMargin = dp(dialog.owner, 12);
        buttons.addView(positive, positiveParams);
        dialog.body.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
    }

    private static Button button(Activity activity, String title, int background, int foreground) {
        Button button = new Button(activity);
        button.setText(title);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTextColor(foreground);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(activity, 48));
        button.setMinimumHeight(dp(activity, 48));
        button.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ad4a14), shape(activity, background, 14), null));
        return button;
    }

    private static void makeAction(LinearLayout view, int color, String label, Runnable action) {
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ad4a14), shape(view.getContext(), color, 15), null));
        view.setFocusable(true);
        view.setContentDescription(label);
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        view.setOnClickListener(v -> action.run());
    }

    private static IconButton decoration(Activity activity, String kind, int color) {
        IconButton icon = new IconButton(activity, kind, "");
        icon.setTint(color);
        icon.setOnLongClickListener(null);
        icon.setLongClickable(false);
        icon.setClickable(false);
        icon.setFocusable(false);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private static boolean hasVisibleName(String value) {
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp) && Character.getType(cp) != Character.FORMAT) return true;
            offset += Character.charCount(cp);
        }
        return false;
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
        return drawable;
    }
    private static void gap(LinearLayout parent, int height) {
        parent.addView(new View(parent.getContext()), new LinearLayout.LayoutParams(1, dp(parent.getContext(), height)));
    }

    /** Measures the whole card and scrolls it within the IME-adjusted visible window. */
    private static final class CardDialog extends Dialog {
        final Activity owner;
        final LinearLayout body;
        private final ScrollView scroll;
        private ViewTreeObserver.OnGlobalLayoutListener listener;
        private int width, height;

        CardDialog(Activity owner) {
            super(owner, resolveTheme(owner));
            this.owner = owner;
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            setCanceledOnTouchOutside(true);
            body = column(owner);
            body.setPadding(dp(owner, 20), dp(owner, 21), dp(owner, 20), dp(owner, 22));
            body.setFocusableInTouchMode(true);
            scroll = new ScrollView(owner);
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
            dismiss();
            if (!owner.isFinishing() && !owner.isDestroyed() && action != null) action.run();
        }

        private void fitWindow() {
            if (getWindow() == null) return;
            Rect visible = new Rect();
            getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
            if (visible.width() <= 0 || visible.height() <= 0) owner.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
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
