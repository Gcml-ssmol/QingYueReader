package cn.qingyue.pdf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Paper choices and preferences share the same warm card style as the reader. */
public final class NoteDialogs {
    private NoteDialogs() { }
    public interface Created { void created(String name, String template, String size); }
    public interface PreferencesChanged { void changed(boolean stylusOnly, boolean twoFingerUndo); }

    public static Dialog create(Activity owner, Created callback) {
        Card card = new Card(owner);
        header(card, "新建笔记", "一张纸，装下新的想法", "note");
        gap(card.body, 18);
        EditText name = new EditText(owner);
        name.setSingleLine(true);
        name.setTextSize(16);
        name.setTextColor(AppColors.INK);
        name.setHintTextColor(AppColors.MUTED);
        name.setHint("笔记名称");
        name.setText("未命名笔记");
        name.setSelectAllOnFocus(true);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        name.setPadding(dp(owner, 14), dp(owner, 12), dp(owner, 14), dp(owner, 12));
        name.setBackground(shape(owner, AppColors.BACKGROUND, 13));
        card.body.addView(name, new LinearLayout.LayoutParams(-1, -2));
        gap(card.body, 22);
        card.body.addView(label(owner, "纸张底纹", 13, AppColors.MUTED));
        gap(card.body, 10);
        final String[] chosen = {"blank", "A4"};
        String[] templates = {"blank", "ruled", "grid"};
        String[] titles = {"全空", "横线", "网格"};
        LinearLayout templateRow = row(owner);
        View[] templateCards = new View[3];
        for (int i = 0; i < templates.length; i++) {
            final String value = templates[i];
            LinearLayout choice = column(owner);
            choice.setGravity(Gravity.CENTER);
            choice.setPadding(dp(owner, 7), dp(owner, 8), dp(owner, 7), dp(owner, 8));
            choice.addView(new PaperPreview(owner, value), new LinearLayout.LayoutParams(-1, dp(owner, 66)));
            TextView title = label(owner, titles[i], 13, AppColors.INK);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, dp(owner, 7), 0, dp(owner, 3));
            choice.addView(title, new LinearLayout.LayoutParams(-1, -2));
            choice.setContentDescription(titles[i] + "底纹");
            templateCards[i] = choice;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) params.leftMargin = dp(owner, 8);
            templateRow.addView(choice, params);
            choice.setOnClickListener(v -> {
                chosen[0] = value;
                for (int k = 0; k < templateCards.length; k++) selected(templateCards[k], templates[k].equals(value));
            });
            selected(choice, i == 0);
        }
        card.body.addView(templateRow, new LinearLayout.LayoutParams(-1, -2));
        gap(card.body, 22);
        card.body.addView(label(owner, "画布大小", 13, AppColors.MUTED));
        gap(card.body, 10);
        String[] keys = {"A3", "A4", "A5", "B4", "B5", "Letter", "infinite"};
        String[] sizes = {"297 × 420 mm", "210 × 297 mm", "148 × 210 mm", "250 × 353 mm", "176 × 250 mm", "216 × 279 mm", "宽度与长度自由延伸"};
        View[] sizeCards = new View[keys.length];
        for (int r = 0; r < 4; r++) {
            LinearLayout sizeRow = row(owner);
            for (int col = 0; col < 2 && r * 2 + col < keys.length; col++) {
                final int index = r * 2 + col;
                LinearLayout tile = column(owner);
                tile.setPadding(dp(owner, 13), dp(owner, 11), dp(owner, 13), dp(owner, 11));
                TextView title = label(owner, keys[index].equals("infinite") ? "∞  无限画布" : keys[index], 14, AppColors.INK);
                title.setTypeface(null, 1);
                tile.addView(title);
                TextView detail = label(owner, sizes[index], 10, AppColors.MUTED);
                detail.setPadding(0, dp(owner, 4), 0, 0);
                tile.addView(detail);
                sizeCards[index] = tile;
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
                if (col > 0) params.leftMargin = dp(owner, 8);
                sizeRow.addView(tile, params);
                tile.setOnClickListener(v -> {
                    chosen[1] = keys[index];
                    for (int k = 0; k < sizeCards.length; k++) selected(sizeCards[k], keys[k].equals(chosen[1]));
                });
                selected(tile, keys[index].equals(chosen[1]));
            }
            card.body.addView(sizeRow, new LinearLayout.LayoutParams(-1, -2));
            if (r < 3) gap(card.body, 8);
        }
        gap(card.body, 14);
        TextView hint = label(owner, "所有底纹均支持固定纸张与无限画布。双指缩放，随时移动到新的位置。", 12, AppColors.MUTED);
        hint.setLineSpacing(dp(owner, 3), 1f);
        card.body.addView(hint);
        gap(card.body, 20);
        Button submit = button(owner, "创建笔记", true);
        submit.setOnClickListener(v -> {
            String title = name.getText().toString().trim();
            if (title.isEmpty()) { name.setError("给笔记取个名字吧"); name.requestFocus(); return; }
            card.dismiss();
            if (!owner.isFinishing() && !owner.isDestroyed()) callback.created(title, chosen[0], chosen[1]);
        });
        card.body.addView(submit, new LinearLayout.LayoutParams(-1, -2));
        card.show();
        return card;
    }

    public static Dialog settings(Activity owner, boolean stylusOnly, boolean twoFingerUndo, PreferencesChanged changed, Runnable fit) {
        Card card = new Card(owner);
        header(card, "笔记设置", "让书写更顺手", "settings");
        final boolean[] values = {stylusOnly, twoFingerUndo};
        gap(card.body, 20);
        toggle(card, "仅手写笔绘制", "手指只移动画布，图片可用手指调整", stylusOnly, checked -> {
            values[0] = checked; changed.changed(values[0], values[1]);
        });
        gap(card.body, 10);
        toggle(card, "双指轻点撤销", "同时短按并抬起两根手指", twoFingerUndo, checked -> {
            values[1] = checked; changed.changed(values[0], values[1]);
        });
        gap(card.body, 20);
        Button reset = button(owner, "恢复画布位置与缩放", false);
        reset.setOnClickListener(v -> {card.dismiss(); fit.run();});
        card.body.addView(reset, new LinearLayout.LayoutParams(-1, -2));
        gap(card.body, 20);
        TextView help = label(owner, "图片：点击图片选择，拖动移动，拖动四角等比例缩放。选择后可用垃圾桶删除。\n\n图形：选择直线、圆形、矩形或箭头后，在画布按住拖动，松手完成。长按或再次点击工具可调节粗细与颜色。\n\n无限画布：上下左右均可平移延伸，底纹跟随画布。笔记修改会自动保存。", 12, AppColors.MUTED);
        help.setLineSpacing(dp(owner, 4), 1f);
        card.body.addView(help);
        gap(card.body, 20);
        Button done = button(owner, "完成", true);
        done.setOnClickListener(v -> card.dismiss());
        card.body.addView(done, new LinearLayout.LayoutParams(-1, -2));
        card.show();
        return card;
    }

    private interface Toggled { void changed(boolean checked); }
    private static void toggle(Card card, String title, String detail, boolean checked, Toggled callback) {
        LinearLayout group = row(card.owner);
        group.setPadding(dp(card.owner, 14), dp(card.owner, 12), dp(card.owner, 12), dp(card.owner, 12));
        group.setBackground(shape(card.owner, AppColors.BACKGROUND, 14));
        LinearLayout titles = column(card.owner);
        titles.addView(label(card.owner, title, 14, AppColors.INK));
        TextView subtitle = label(card.owner, detail, 11, AppColors.MUTED);
        subtitle.setPadding(0, dp(card.owner, 5), dp(card.owner, 8), 0);
        titles.addView(subtitle);
        group.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(card.owner);
        toggle.setContentDescription(title);
        toggle.setChecked(checked);
        toggle.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{AppColors.ACCENT, 0xffbaa694}));
        toggle.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{0xffffcc9f, AppColors.BORDER}));
        toggle.setOnCheckedChangeListener((button, value) -> callback.changed(value));
        group.addView(toggle, new LinearLayout.LayoutParams(-2, dp(card.owner, 48)));
        group.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
        card.body.addView(group, new LinearLayout.LayoutParams(-1, -2));
    }

    private static void header(Card card, String title, String subtitle, String kind) {
        LinearLayout header = row(card.owner);
        IconButton icon = new IconButton(card.owner, kind, "");
        icon.setTint(AppColors.ACCENT_DARK);
        icon.setBackground(shape(card.owner, AppColors.SOFT, 13));
        icon.setClickable(false); icon.setFocusable(false); icon.setOnLongClickListener(null);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(icon, new LinearLayout.LayoutParams(dp(card.owner, 44), dp(card.owner, 44)));
        LinearLayout titles = column(card.owner);
        titles.setPadding(dp(card.owner, 12), 0, dp(card.owner, 4), 0);
        TextView heading = label(card.owner, title, 20, AppColors.INK);
        heading.setTypeface(null, 1);
        titles.addView(heading);
        TextView detail = label(card.owner, subtitle, 11, AppColors.MUTED);
        detail.setPadding(0, dp(card.owner, 4), 0, 0);
        titles.addView(detail);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        IconButton close = new IconButton(card.owner, "close", "关闭");
        close.setOnClickListener(v -> card.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(card.owner, 40), dp(card.owner, 44)));
        card.body.addView(header, new LinearLayout.LayoutParams(-1, -2));
    }

    static int dp(Context context, float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    static LinearLayout column(Context context) { LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view; }
    static LinearLayout row(Context context) { LinearLayout view = new LinearLayout(context); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    static TextView label(Context context, String value, int size, int color) { TextView text = new TextView(context); text.setText(value); text.setTextSize(size); text.setTextColor(color); return text; }
    static GradientDrawable shape(Context context, int color, int radius) { GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(dp(context, radius)); return background; }
    static void gap(LinearLayout parent, int size) { parent.addView(new View(parent.getContext()), new LinearLayout.LayoutParams(1, dp(parent.getContext(), size))); }
    private static void selected(View view, boolean selected) {
        if (view == null) return;
        GradientDrawable background = shape(view.getContext(), selected ? 0xffffedde : AppColors.BACKGROUND, 13);
        background.setStroke(dp(view.getContext(), 1), selected ? AppColors.ACCENT : AppColors.BORDER);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ad4a14), background, null));
        view.setSelected(selected); view.setFocusable(true);
    }
    private static Button button(Activity owner, String title, boolean primary) {
        Button button = new Button(owner); button.setText(title); button.setTextSize(14); button.setAllCaps(false);
        button.setTextColor(primary ? AppColors.SURFACE : AppColors.ACCENT_DARK);
        button.setPadding(dp(owner, 14), dp(owner, 12), dp(owner, 14), dp(owner, 12));
        button.setMinHeight(dp(owner, 48)); button.setMinimumHeight(dp(owner, 48)); button.setStateListAnimator(null); button.setElevation(0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ad4a14), shape(owner, primary ? AppColors.ACCENT : AppColors.SOFT, 14), null));
        return button;
    }

    private static final class PaperPreview extends View {
        final String template; final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        PaperPreview(Context context, String template) { super(context); this.template = template; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            float left = getWidth() * .17f, right = getWidth() * .83f, top = dp(getContext(), 2), bottom = getHeight() - dp(getContext(), 2);
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE); canvas.drawRoundRect(new RectF(left, top, right, bottom), dp(getContext(), 4), dp(getContext(), 4), paint);
            paint.setStyle(Paint.Style.STROKE); paint.setColor(AppColors.BORDER); paint.setStrokeWidth(dp(getContext(), 1));
            canvas.drawRoundRect(new RectF(left, top, right, bottom), dp(getContext(), 4), dp(getContext(), 4), paint);
            if (!template.equals("blank")) {
                int save = canvas.save(); canvas.clipRect(left + dp(getContext(), 5), top + dp(getContext(), 6), right - dp(getContext(), 5), bottom - dp(getContext(), 6));
                float spacing = dp(getContext(), 8);
                for (float y = top + dp(getContext(), 12); y < bottom; y += spacing) canvas.drawLine(left, y, right, y, paint);
                if (template.equals("grid")) for (float x = left + dp(getContext(), 5); x < right; x += spacing) canvas.drawLine(x, top, x, bottom, paint);
                canvas.restoreToCount(save);
            }
        }
    }

    private static final class Card extends Dialog {
        final Activity owner; final LinearLayout body;
        ViewTreeObserver.OnGlobalLayoutListener listener; int width, height;
        Card(Activity owner) {
            super(owner, theme(owner)); this.owner = owner; requestWindowFeature(Window.FEATURE_NO_TITLE); setCanceledOnTouchOutside(true);
            body = column(owner); body.setPadding(dp(owner, 20), dp(owner, 20), dp(owner, 20), dp(owner, 22)); body.setFocusableInTouchMode(true);
            ScrollView scroll = new ScrollView(owner); scroll.setVerticalScrollBarEnabled(false); scroll.setBackground(shape(owner, AppColors.SURFACE, 22)); scroll.setClipToOutline(true); scroll.addView(body);
            setContentView(scroll, new ViewGroup.LayoutParams(-1, -1));
            Window window = getWindow(); if (window != null) { window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); window.setGravity(Gravity.CENTER); window.setDimAmount(.25f); window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN); }
        }
        @Override public void show() { super.show(); body.requestFocus(); if (getWindow() != null && listener == null) { listener = this::fit; getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(listener); } fit(); }
        void fit() {
            if (getWindow() == null) return;
            Rect visible = new Rect(); getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
            View host = owner.getWindow().getDecorView(); int[] position = new int[2]; host.getLocationOnScreen(position);
            Rect bounded = new Rect(visible); if (host.getWidth() > 0 && host.getHeight() > 0 && bounded.intersect(position[0], position[1], position[0] + host.getWidth(), position[1] + host.getHeight())) visible.set(bounded);
            if (visible.width() <= 0 || visible.height() <= 0) return;
            int nextWidth = Math.max(1, Math.min(dp(owner, 420), visible.width() - dp(owner, 28)));
            body.measure(View.MeasureSpec.makeMeasureSpec(nextWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int nextHeight = Math.max(1, Math.min(body.getMeasuredHeight(), visible.height() - dp(owner, 28)));
            if (nextWidth != width || nextHeight != height) { width = nextWidth; height = nextHeight; getWindow().setLayout(width, height); }
        }
        void release() { if (getWindow() != null && listener != null) { ViewTreeObserver observer = getWindow().getDecorView().getViewTreeObserver(); if (observer.isAlive()) observer.removeOnGlobalLayoutListener(listener); listener = null; } }
        @Override public void dismiss() { release(); super.dismiss(); }
        @Override protected void onStop() { release(); super.onStop(); }
        static int theme(Context context) { TypedValue value = new TypedValue(); context.getTheme().resolveAttribute(android.R.attr.alertDialogTheme, value, true); return value.resourceId; }
    }
}
