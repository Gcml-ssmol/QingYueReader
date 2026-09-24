package cn.qingyue.pdf;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A compact action sheet with text that remains readable at large font scales. */
final class NoteActionMenu {
    private NoteActionMenu() { }

    static Dialog show(Context context, View anchor, int page, int count, Runnable sources,
                       Runnable pdf, Runnable png, Runnable append, Runnable settings) {
        return showActions(context, anchor, "笔记操作",
                new Action("↗", "查看来源", "打开笔记里的来源链接", sources),
                new Action("PDF", "导出 PDF", "导出全部 " + count + " 页", pdf),
                new Action("PNG", "导出 PNG", "仅导出当前第 " + page + " 页", png),
                new Action("+", "追加空白页", "在第 " + count + " 页之后继续书写", append),
                new Action("⚙", "笔记设置", "插页、页面方向与书写选项", settings));
    }

    static final class Action {
        final String symbol, heading, detail; final Runnable run;
        Action(String symbol, String heading, String detail, Runnable run) {
            this.symbol=symbol; this.heading=heading; this.detail=detail; this.run=run;
        }
    }
    static Dialog showActions(Context context, View anchor, String heading, Action... actions) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content = NoteDialogs.column(context);
        content.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
        content.setBackground(NoteDialogs.shape(context, AppColors.SURFACE, 22));
        TextView title = NoteDialogs.label(context, heading, 18, AppColors.INK);
        title.setTypeface(null, 1);
        title.setPadding(dp(context, 6), dp(context, 3), 0, dp(context, 9));
        content.addView(title);
        for(Action action: actions) row(context, content, dialog, action.symbol, action.heading, action.detail, action.run);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content);
        dialog.setContentView(scroll);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            Rect visible = new Rect();
            anchor.getWindowVisibleDisplayFrame(visible);
            if (visible.width() <= 0 || visible.height() <= 0)
                visible.set(0, 0, context.getResources().getDisplayMetrics().widthPixels,
                        context.getResources().getDisplayMetrics().heightPixels);
            int width = Math.max(1, Math.min(dp(context, 360), visible.width() - dp(context, 32)));
            int height = Math.max(1, Math.min(dp(context, 480), visible.height() - dp(context, 32)));
            window.setBackgroundDrawableResource(android.R.color.transparent);
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int measuredHeight = Math.min(height, content.getMeasuredHeight());
            window.setLayout(width, measuredHeight);
            int[] position = new int[2]; anchor.getLocationOnScreen(position);
            int[] placement = NoteActionPlacement.place(visible.left, visible.top, visible.right, visible.bottom,
                    position[0], position[1] + anchor.getHeight(), width, measuredHeight, dp(context, 8));
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.gravity = Gravity.TOP | Gravity.LEFT;
            attributes.x = placement[0]; attributes.y = placement[1];
            attributes.dimAmount = 0.18f;
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setAttributes(attributes);
        }
        return dialog;
    }

    private static void row(Context context, LinearLayout content, Dialog dialog, String symbol,
                            String heading, String detail, Runnable action) {
        LinearLayout line = NoteDialogs.row(context);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(context, 7), dp(context, 6), dp(context, 7), dp(context, 6));
        line.setBackground(NoteDialogs.shape(context, AppColors.SURFACE, 12));
        line.setClickable(action != null);
        line.setEnabled(action != null);
        line.setAlpha(action == null ? .42f : 1f);
        line.setFocusable(true);
        line.setContentDescription(heading + "，" + detail);
        if(action != null) line.setOnClickListener(v -> { dialog.dismiss(); action.run(); });
        TextView badge = NoteDialogs.label(context, symbol, symbol.length() > 1 ? 11 : 20, AppColors.ACCENT_DARK);
        badge.setTypeface(null, 1);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(NoteDialogs.shape(context, AppColors.SOFT, 12));
        line.addView(badge, new LinearLayout.LayoutParams(dp(context, 42), dp(context, 42)));
        LinearLayout labels = NoteDialogs.column(context);
        labels.setPadding(dp(context, 12), 0, 0, 0);
        TextView main = NoteDialogs.label(context, heading, 15, AppColors.INK);
        main.setTypeface(null, 1);
        labels.addView(main);
        TextView sub = NoteDialogs.label(context, detail, 12, AppColors.MUTED);
        labels.addView(sub);
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(line, new LinearLayout.LayoutParams(-1, -2));
    }

    private static int dp(Context context, float value) { return NoteDialogs.dp(context, value); }
}
