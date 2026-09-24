package cn.qingyue.pdf;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Display preferences never change stored ink or exported pages. */
public final class AppearanceActivity extends ThemedActivity {
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("appearance", MODE_PRIVATE);
        LinearLayout root = column();
        if (AppColors.isClassic()) root.setBackgroundColor(AppColors.BACKGROUND);
        else root.setBackground(new WarmBackdrop(false));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(10) + insets.getSystemWindowInsetTop(),
                    dp(20) + insets.getSystemWindowInsetRight(), dp(20) + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        IconButton back = new IconButton(this, "back", "返回");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text("外观", AppColors.isClassic() ? 24 : 21, AppColors.INK);
        title.setTypeface(null, 1);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.leftMargin = dp(8);
        bar.addView(title, titleParams);
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = column();
        content.setPadding(0, dp(12), 0, dp(12));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        heading(content, "界面风格", "选择你喜欢的晴阅，随时可以切换");
        LinearLayout styles = choiceRow(content, 2);
        tile(styles, "奶油 · 杏橙", "柔和留白，圆角卡片", "style", AppColors.STYLE_CREAM,
                AppColors.style(), "cream");
        tile(styles, "经典 · 原版", "保留原来的界面样式", "style", AppColors.STYLE_CLASSIC,
                AppColors.style(), "classic");
        heading(content, "界面主题", "选择阅读时的界面颜色");
        int mode = prefs.getInt("mode", 0);
        if (AppColors.isClassic()) {
            choice(content, "跟随系统", "自动适应设备的深浅色设置", "◐", 0, mode, "mode", false);
            choice(content, "浅色 · 奶油白", "柔和底色与杏橙点缀", "☀", 1, mode, "mode", false);
            choice(content, "深色 · 暖灰", "夜间阅读更舒适", "☾", 2, mode, "mode", true);
        } else {
            LinearLayout themes = choiceRow(content, 3);
            tile(themes, "跟随系统", "自动适应\n设备的深浅色", "mode", 0, mode, "system");
            tile(themes, "浅色 · 奶油白", "柔和底色\n与杏橙点缀", "mode", 1, mode, "light");
            tile(themes, "深色 · 暖灰", "夜间阅读\n更舒适", "mode", 2, mode, "dark");
        }
        heading(content, "笔记纸张", "纸张预览底色独立于界面主题");
        int paper = prefs.getInt("paper", 0);
        if (AppColors.isClassic()) {
            choice(content, "柔和米白", "温暖、低对比的纸张", "▤", 0, paper, "paper", false);
            choice(content, "纯白", "清晰明亮的纸张", "▤", 1, paper, "paper", false);
        } else {
            LinearLayout papers = choiceRow(content, 2);
            tile(papers, "柔和米白", "温暖、低对比的纸张", "paper", 0, paper, "ivory");
            tile(papers, "纯白", "清晰明亮的纸张", "paper", 1, paper, "white");
        }
        TextView note = text("外观设置不会改写笔迹颜色、原 PDF 或导出文件。", 13, AppColors.MUTED);
        note.setPadding(dp(4), dp(18), dp(4), dp(12));
        content.addView(note);
        if (AppColors.isClassic()) {
            TextView sample = text("晴阅\n读一份文档，记下一点灵感。", 17, 0xff36291f);
            sample.setLineSpacing(dp(6), 1f);
            sample.setPadding(dp(20), dp(20), dp(20), dp(20));
            sample.setBackground(card(AppColors.PAPER, 0xffe8ded1, 12));
            content.addView(sample, new LinearLayout.LayoutParams(-1, -2));
        } else {
            LinearLayout sample = column();
            sample.setPadding(dp(20), dp(20), dp(20), dp(20));
            sample.setBackground(card(AppColors.PAPER, 0xffe8ded1, 18));
            TextView sampleTag = text("纸张预览", 11, 0xff887567);
            sample.addView(sampleTag);
            TextView sampleTitle = text("把阅读，留给自己", 20, 0xff2d241e);
            sampleTitle.setTypeface(android.graphics.Typeface.create("serif", 1));
            sampleTitle.setPadding(0, dp(13), 0, dp(9));
            sample.addView(sampleTitle);
            TextView sampleCopy = text("读一份文档，记下一点灵感。\n在文字里，遇见更好的自己。", 14, 0xff887567);
            sampleCopy.setLineSpacing(dp(7), 1f);
            sample.addView(sampleCopy);
            content.addView(sample, new LinearLayout.LayoutParams(-1, -2));
        }
        setContentView(root);
        root.requestApplyInsets();
    }

    private void heading(LinearLayout parent, String title, String subtitle) {
        TextView label = text(title, AppColors.isClassic() ? 19 : 17, AppColors.INK);
        label.setTypeface(null, 1);
        label.setPadding(dp(4), dp(20), dp(4), dp(3));
        parent.addView(label);
        TextView hint = text(subtitle, 13, AppColors.MUTED);
        hint.setPadding(dp(4), 0, dp(4), dp(12));
        parent.addView(hint);
    }

    /** Compact cards become full-width options before large text can be clipped. */
    private LinearLayout choiceRow(LinearLayout parent, int count) {
        LinearLayout row = new LinearLayout(this);
        android.content.res.Configuration config = getResources().getConfiguration();
        boolean stacked = config.fontScale > 1.15f || config.screenWidthDp < (count == 3 ? 350 : 310);
        row.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private void tile(LinearLayout row, String title, String detail, String key,
                      int value, int selected, String previewKind) {
        boolean checked = value == selected;
        FrameLayout option = new FrameLayout(this);
        option.setBackground(card(AppColors.SURFACE, checked ? AppColors.ACCENT : AppColors.BORDER, 15));
        option.setClickable(true);
        option.setFocusable(true);
        option.setSelected(checked);
        option.setContentDescription(title + "，" + detail.replace('\n', ' ') + (checked ? "，已选择" : "，未选择"));
        option.setOnClickListener(v -> update(key, value));
        LinearLayout contents = column();
        contents.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        contents.setPadding(dp(12), dp(14), dp(12), dp(14));
        Preview preview = new Preview(previewKind);
        int previewHeight = key.equals("style") ? 62 : key.equals("paper") ? 64 : 47;
        contents.addView(preview, new LinearLayout.LayoutParams(-1, dp(previewHeight)));
        TextView name = text(title, key.equals("mode") ? 12 : 14, AppColors.INK);
        name.setTypeface(null, 1);
        name.setPadding(0, dp(9), 0, dp(4));
        contents.addView(name);
        TextView description = text(detail, 11, AppColors.MUTED);
        description.setLineSpacing(dp(3), 1f);
        contents.addView(description);
        option.addView(contents, new FrameLayout.LayoutParams(-1, -2));
        if (checked) {
            TextView indicator = text("✓", 11, AppColors.ON_ACCENT);
            indicator.setGravity(Gravity.CENTER);
            indicator.setBackground(card(AppColors.ACCENT, AppColors.ACCENT, 10));
            indicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams mark = new FrameLayout.LayoutParams(dp(17), dp(17), Gravity.TOP | Gravity.RIGHT);
            mark.topMargin = dp(7);
            mark.rightMargin = dp(7);
            option.addView(indicator, mark);
        }
        boolean stacked = row.getOrientation() == LinearLayout.VERTICAL;
        LinearLayout.LayoutParams layout = stacked ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -1, 1);
        if (row.getChildCount() > 0) {
            if (stacked) layout.topMargin = dp(10);
            else layout.leftMargin = dp(8);
        }
        row.addView(option, layout);
    }

    /** Theme samples are vector UI previews, so they stay sharp at any display density. */
    private final class Preview extends View {
        private final String kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        Preview(String kind) { super(AppearanceActivity.this); this.kind = kind; }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth(), height = getHeight();
            if (kind.equals("cream") || kind.equals("classic")) {
                boolean classic = kind.equals("classic");
                int background = AppColors.isDark() ? 0xff29231f : classic ? 0xfffff5e9 : 0xfffff8ef;
                int surface = AppColors.isDark() ? 0xff40332a : classic ? 0xfffffcf6 : 0xfffffcf7;
                int soft = AppColors.isDark() ? 0xff65422d : classic ? 0xffffe6cf : 0xffffe7d1;
                int accent = AppColors.isDark() ? 0xfff2a16b : classic ? 0xffd8702a : 0xffde5c16;
                round(canvas, 0, 0, width, height, 8, background);
                round(canvas, dp(7), dp(7), width * .4f, dp(11), 2, accent);
                round(canvas, dp(7), dp(17), width - dp(7), dp(35), classic ? 4 : 7, soft);
                round(canvas, dp(7), dp(41), width - dp(7), height - dp(6), classic ? 3 : 6, surface);
                round(canvas, dp(12), dp(45), dp(21), height - dp(10), 2, accent);
                round(canvas, dp(27), dp(46), width * .78f, dp(49), 1, AppColors.isDark() ? 0xffb99880 : 0xffbb9d83);
            } else if (kind.equals("ivory") || kind.equals("white")) {
                float right = Math.min(width, dp(66));
                round(canvas, 1, 1, right - 1, height - 1, 7, kind.equals("white") ? 0xffffffff : AppColors.EXPORT_PAPER);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1));
                paint.setColor(0xffecd8c7);
                box.set(1, 1, right - 1, height - 1);
                canvas.drawRoundRect(box, dp(7), dp(7), paint);
                for (int i = 0; i < 4; i++) canvas.drawLine(dp(10), dp(14 + i * 11), right - dp(10), dp(14 + i * 11), paint);
                paint.setStyle(Paint.Style.FILL);
            } else {
                boolean system = kind.equals("system"), night = kind.equals("dark");
                float center = Math.min(width * .5f, dp(34));
                round(canvas, center - dp(28), dp(3), center + dp(28), height - dp(3), 22,
                        night ? 0xffede7df : system ? 0xfff1eae0 : 0xfffff0de);
                if (system) {
                    sun(canvas, center - dp(12), height / 2, 0xff7c7065, 7);
                    moon(canvas, center + dp(12), height / 2, 0xfffffcf7, 0xfff1eae0, 10);
                } else if (night) moon(canvas, center, height / 2, 0xff72685e, 0xffede7df, 14);
                else sun(canvas, center, height / 2, 0xffde5c16, 10);
            }
        }
        private void round(Canvas canvas, float l, float t, float r, float b, int radius, int color) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            box.set(l, t, r, b);
            canvas.drawRoundRect(box, dp(radius), dp(radius), paint);
        }
        private void sun(Canvas canvas, float x, float y, int color, int radius) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1, dp(1)));
            canvas.drawCircle(x, y, dp(radius - 3), paint);
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * i / 4;
                canvas.drawLine(x + (float) Math.cos(angle) * dp(radius), y + (float) Math.sin(angle) * dp(radius),
                        x + (float) Math.cos(angle) * dp(radius + 3), y + (float) Math.sin(angle) * dp(radius + 3), paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
        private void moon(Canvas canvas, float x, float y, int color, int background, int radius) {
            paint.setColor(color);
            canvas.drawCircle(x, y, dp(radius), paint);
            paint.setColor(background);
            canvas.drawCircle(x + dp(radius / 2), y - dp(radius / 2), dp(radius - 1), paint);
        }
    }

    private void choice(LinearLayout parent, String title, String detail, String symbol,
                        int value, int selected, String key, boolean darkPreview) {
        boolean checked = value == selected;
        LinearLayout option = new LinearLayout(this);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setPadding(dp(14), dp(12), dp(14), dp(12));
        option.setBackground(card(checked ? AppColors.SOFT : AppColors.SURFACE,
                checked ? AppColors.ACCENT_DARK : AppColors.BORDER, 16));
        option.setClickable(true);
        option.setFocusable(true);
        option.setContentDescription(title + (checked ? "，已选择" : "，未选择"));
        option.setOnClickListener(v -> update(key, value));
        TextView preview = text(symbol, 24, darkPreview ? 0xfff4e9dd : 0xffad4a14);
        preview.setGravity(Gravity.CENTER);
        preview.setBackground(card(darkPreview ? 0xff342c27 : 0xfffff5e9,
                darkPreview ? 0xff6d5648 : 0xffefdcc9, 12));
        option.addView(preview, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout copy = column();
        TextView name = text(title, 16, AppColors.INK);
        name.setTypeface(null, 1);
        copy.addView(name);
        TextView description = text(detail, 12, AppColors.MUTED);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(3);
        copy.addView(description, descriptionParams);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1);
        copyParams.leftMargin = dp(13);
        option.addView(copy, copyParams);
        TextView indicator = text(checked ? "✓" : "", 16, AppColors.ON_ACCENT);
        indicator.setGravity(Gravity.CENTER);
        indicator.setBackground(card(checked ? AppColors.ACCENT : AppColors.SURFACE,
                checked ? AppColors.ACCENT : AppColors.BORDER, 12));
        option.addView(indicator, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(9);
        parent.addView(option, params);
    }

    private void update(String key, int value) {
        if (prefs.getInt(key, 0) == value) return;
        prefs.edit().putInt(key, value).apply();
        AppColors.apply(this);
        recreate();
    }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }
    private GradientDrawable card(int color, int border, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), border);
        return drawable;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
