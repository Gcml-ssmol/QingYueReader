package cn.qingyue.pdf;

import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/** Independent display settings. No stored document colors are rewritten. */
public final class AppearanceActivity extends ThemedActivity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences prefs = getSharedPreferences("appearance", MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.BACKGROUND); root.setFitsSystemWindows(true);
        root.setPadding(dp(20), dp(10), dp(20), dp(20));
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this); back.setText("返回"); back.setOnClickListener(v -> finish()); bar.addView(back);
        TextView title = label("外观", 24); bar.addView(title); root.addView(bar);
        ScrollView scroll = new ScrollView(this); LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL); scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(label("界面主题", 19));
        RadioGroup modes = choices(new String[]{"跟随系统", "浅色 · 奶油白", "深色 · 暖灰"}, prefs.getInt("mode", 0), 100); content.addView(modes);
        content.addView(label("笔记纸张预览底色", 19));
        RadioGroup paper = choices(new String[]{"柔和米白", "纯白"}, prefs.getInt("paper", 0), 200); content.addView(paper);
        TextView hint = label("纸张预览与界面主题独立。外观设置不会改写笔迹颜色、原 PDF 或导出文件。", 14);
        hint.setTextColor(AppColors.MUTED); content.addView(hint);
        TextView sample = label("晴阅\n读一份文档，记下一点灵感。", 20);
        sample.setTextColor(0xff36291f); sample.setPadding(dp(20), dp(24), dp(20), dp(24));
        GradientDrawable sheet = new GradientDrawable(); sheet.setColor(AppColors.PAPER); sheet.setCornerRadius(dp(12)); sample.setBackground(sheet);
        LinearLayout.LayoutParams sampleParams = new LinearLayout.LayoutParams(-1, -2); sampleParams.topMargin = dp(24); content.addView(sample, sampleParams);
        modes.setOnCheckedChangeListener((g, id) -> update(prefs, "mode", id - 100));
        paper.setOnCheckedChangeListener((g, id) -> update(prefs, "paper", id - 200));
        setContentView(root);
    }
    private void update(SharedPreferences prefs, String key, int value) {
        if (value < 0 || prefs.getInt(key, 0) == value) return;
        prefs.edit().putInt(key, value).apply(); AppColors.apply(this); recreate();
    }
    private RadioGroup choices(String[] labels, int selected, int base) {
        RadioGroup group = new RadioGroup(this); group.setOrientation(RadioGroup.VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            RadioButton button = new RadioButton(this); button.setId(base + i); button.setText(labels[i]);
            button.setTextSize(16); button.setTextColor(AppColors.INK); button.setMinHeight(dp(50)); group.addView(button);
        }
        group.check(base + Math.max(0, Math.min(labels.length - 1, selected))); return group;
    }
    private TextView label(String value, int sp) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(sp); text.setTextColor(AppColors.INK);
        text.setPadding(dp(10), dp(18), dp(10), dp(12)); return text;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
