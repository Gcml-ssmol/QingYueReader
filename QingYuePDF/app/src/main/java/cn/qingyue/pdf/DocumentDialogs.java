package cn.qingyue.pdf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/** Compact document cards shared by the library's manage, rename and remove flows. */
public final class DocumentDialogs {
    private static final int REMOVE = 0xffb75448;
    private static final int REMOVE_SOFT = 0xffffeeea;
    private DocumentDialogs() { }

    public interface Rename { void changed(String name); }
    public interface Organization { void changed(String folder, List<String> tags); }
    public interface ImportOption { void chosen(boolean keepOriginal); }
    public interface ImportOptionWithLayout {
        void chosen(boolean keepOriginal,DocumentImport.LayoutOptions options);
    }

    /** A lifecycle-replaceable task card. The worker is owned outside the Activity. */
    public static final class TaskDialog {
        private final CardDialog dialog;
        private final TextView stage, detail;
        private final ProgressBar progress;
        private final Button cancel;
        TaskDialog(CardDialog dialog, TextView stage, TextView detail, ProgressBar progress, Button cancel) {
            this.dialog=dialog;this.stage=stage;this.detail=detail;this.progress=progress;this.cancel=cancel;
        }
        public Dialog dialog(){return dialog;}
        public void update(String title,String message,int percent){
            stage.setText(safe(title));detail.setText(safe(message));
            progress.setIndeterminate(percent<0);if(percent>=0)progress.setProgress(Math.max(0,Math.min(100,percent)));
        }
        public void setCancellationEnabled(boolean enabled){if(cancel!=null){cancel.setEnabled(enabled);cancel.setVisibility(enabled?View.VISIBLE:View.GONE);}}
        public void dismiss(){if(dialog.isShowing())dialog.dismiss();}
    }

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
        continueButton.addView(decoration(activity, "book", AppColors.ON_ACCENT), new LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 30)));
        TextView continueLabel = text(activity, note?"继续记录":"继续阅读", 16, AppColors.ON_ACCENT);
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

    /** Full library actions. Removal means moving to the recoverable trash. */
    public static Dialog libraryActions(Activity activity,String name,String metadata,int page1Based,int total,
            boolean note,String format,boolean favorite,String folder,List<String> tags,Runnable read,
            Runnable rename,Runnable organize,Runnable toggleFavorite,Runnable moveToTrash){
        CardDialog dialog=new CardDialog(activity);LinearLayout body=dialog.body;
        LinearLayout heading=row(activity);
        TextView badge=text(activity,note?"笔记":safe(format),14,AppColors.ACCENT_DARK);badge.setTypeface(null,1);badge.setGravity(Gravity.CENTER);
        GradientDrawable cover=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xffffedda,0xffffd7b6});cover.setCornerRadius(dp(activity,13));badge.setBackground(cover);
        heading.addView(badge,new LinearLayout.LayoutParams(dp(activity,54),dp(activity,66)));
        LinearLayout copy=column(activity);copy.setPadding(dp(activity,13),dp(activity,2),dp(activity,4),0);
        TextView title=text(activity,safe(name),17,AppColors.INK);title.setTypeface(null,1);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);copy.addView(title);
        TextView meta=text(activity,safe(metadata),12,AppColors.MUTED);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,-2);mp.topMargin=dp(activity,5);copy.addView(meta,mp);
        String organization=(folder==null||folder.trim().isEmpty()?"未分类":folder.trim());if(tags!=null&&!tags.isEmpty())organization+=" · "+TextUtils.join(" / ",tags);
        TextView organized=text(activity,organization,11,AppColors.ACCENT_DARK);LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(-1,-2);op.topMargin=dp(activity,5);copy.addView(organized,op);
        heading.addView(copy,new LinearLayout.LayoutParams(0,-2,1));addClose(dialog,heading);body.addView(heading);gap(body,18);
        LinearLayout open=operation(activity,"book",note?"继续记录":"继续阅读",note?"打开可编辑笔记":"从上次位置继续",false);makeAction(open,AppColors.SOFT,note?"继续记录":"继续阅读",()->dialog.complete(read));body.addView(open);gap(body,9);
        LinearLayout fav=operation(activity,favorite?"check":"sun",favorite?"取消收藏":"加入收藏",favorite?"从收藏筛选中移除":"在收藏中快速找到",false);makeAction(fav,AppColors.BACKGROUND,favorite?"取消收藏":"加入收藏",()->dialog.complete(toggleFavorite));body.addView(fav);gap(body,9);
        LinearLayout org=operation(activity,"settings","整理分类","设置文件夹与标签",false);makeAction(org,AppColors.BACKGROUND,"设置文件夹与标签",()->dialog.complete(organize));body.addView(org);gap(body,9);
        LinearLayout renameRow=operation(activity,"rename","重命名","修改资料库显示名称",false);makeAction(renameRow,AppColors.BACKGROUND,"重命名",()->dialog.complete(rename));body.addView(renameRow);gap(body,9);
        LinearLayout trash=operation(activity,"trash","移入最近删除","可在回收站恢复或彻底删除",true);makeAction(trash,REMOVE_SOFT,"移入最近删除",()->dialog.complete(moveToTrash));body.addView(trash);
        dialog.show();return dialog;
    }

    public static Dialog organize(Activity activity,String currentFolder,List<String> currentTags,Organization callback){
        CardDialog dialog=new CardDialog(activity);addTitle(dialog,"整理文档","settings",AppColors.ACCENT_DARK);gap(dialog.body,12);
        dialog.body.addView(text(activity,"文件夹",12,AppColors.MUTED));gap(dialog.body,7);
        EditText folder=input(activity,"例如：高等数学",80);folder.setText(safe(currentFolder));dialog.body.addView(folder);
        gap(dialog.body,15);dialog.body.addView(text(activity,"标签",12,AppColors.MUTED));gap(dialog.body,7);
        EditText tags=input(activity,"用逗号分隔，例如：重点, 期末",240);tags.setSingleLine(false);tags.setMaxLines(3);tags.setText(currentTags==null?"":TextUtils.join(", ",currentTags));dialog.body.addView(tags);
        gap(dialog.body,8);TextView hint=text(activity,"文件夹可留空。标签自动去重，最多 20 个，每个最多 30 个字符。",11,AppColors.MUTED);hint.setLineSpacing(dp(activity,3),1f);dialog.body.addView(hint);gap(dialog.body,22);
        Runnable save=()->{String value=folder.getText().toString().trim();LinkedHashSet<String> unique=new LinkedHashSet<>();
            for(String raw:tags.getText().toString().split("[,，\\n]")){String tag=raw.trim();if(tag.isEmpty())continue;if(tag.length()>30){tags.setError("每个标签最多 30 个字符");return;}if(unique.size()<20)unique.add(tag);}
            dialog.complete(callback==null?null:()->callback.changed(value,new ArrayList<>(unique)));};
        addButtons(dialog,"保存整理",AppColors.ACCENT,AppColors.ON_ACCENT,save);dialog.show();return dialog;
    }

    public static Dialog importOptions(Activity activity,String name,boolean convertible,ImportOption callback){
        CardDialog dialog=new CardDialog(activity);addTitle(dialog,"导入文档","book",AppColors.ACCENT_DARK);gap(dialog.body,12);
        TextView filename=text(activity,safe(name),15,AppColors.INK);filename.setTypeface(null,1);filename.setMaxLines(2);filename.setEllipsize(TextUtils.TruncateAt.END);dialog.body.addView(filename);gap(dialog.body,10);
        TextView explanation=text(activity,convertible?"EPUB / Word / TXT 会按正文重排为可批注 PDF。EPUB 按章节顺序阅读，图片仅保留说明文字，暂不还原原字体和 CSS；不支持受 DRM 保护的正文。":"PDF 将复制到晴阅本地资料库。",13,AppColors.MUTED);explanation.setLineSpacing(dp(activity,4),1f);dialog.body.addView(explanation);
        CheckBox keep=new CheckBox(activity);keep.setText("同时保留原始文件附件");keep.setTextColor(AppColors.INK);keep.setTextSize(13);keep.setChecked(convertible);keep.setEnabled(convertible);keep.setPadding(0,dp(activity,12),0,dp(activity,8));dialog.body.addView(keep);
        gap(dialog.body,15);addButtons(dialog,"开始导入",AppColors.ACCENT,AppColors.ON_ACCENT,()->dialog.complete(callback==null?null:()->callback.chosen(keep.isChecked())));dialog.show();return dialog;
    }

    /** New inputs only: a fixed PDF is copied without reflow regardless of these controls. */
    public static Dialog importOptions(Activity activity,String name,boolean convertible,
            ImportOptionWithLayout callback){
        CardDialog dialog=new CardDialog(activity);addTitle(dialog,"导入文档","book",AppColors.ACCENT_DARK);gap(dialog.body,12);
        TextView filename=text(activity,safe(name),15,AppColors.INK);filename.setTypeface(null,1);filename.setMaxLines(2);filename.setEllipsize(TextUtils.TruncateAt.END);dialog.body.addView(filename);gap(dialog.body,10);
        TextView explanation=text(activity,convertible?"EPUB / Word / TXT 将生成新的可批注 PDF；此选项不修改已有 PDF 或批注。原字体、CSS 与图片版式不保证还原。":"PDF 会按原页复制到本地资料库，不重排正文。",13,AppColors.MUTED);
        explanation.setLineSpacing(dp(activity,4),1f);dialog.body.addView(explanation);
        DocumentImport.LayoutOptions defaults=DocumentImport.LayoutOptions.defaultFor(activity);
        RadioGroup pages=new RadioGroup(activity);pages.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton phone=radio(activity,"手机阅读",10101),a4=radio(activity,"标准 A4",10102);
        pages.addView(phone,new RadioGroup.LayoutParams(0,-2,1));pages.addView(a4,new RadioGroup.LayoutParams(0,-2,1));
        pages.check(defaults.page==DocumentImport.PagePreset.PHONE?phone.getId():a4.getId());
        RadioGroup sizes=new RadioGroup(activity);sizes.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton small=radio(activity,"小",10201),medium=radio(activity,"中",10202),large=radio(activity,"大",10203);
        sizes.addView(small,new RadioGroup.LayoutParams(0,-2,1));sizes.addView(medium,new RadioGroup.LayoutParams(0,-2,1));sizes.addView(large,new RadioGroup.LayoutParams(0,-2,1));sizes.check(medium.getId());
        if(convertible){gap(dialog.body,12);dialog.body.addView(text(activity,"新文档页面",13,AppColors.INK));dialog.body.addView(pages);gap(dialog.body,8);dialog.body.addView(text(activity,"正文字号",13,AppColors.INK));dialog.body.addView(sizes);}
        CheckBox keep=new CheckBox(activity);keep.setText("同时保留原始文件附件");keep.setTextColor(AppColors.INK);keep.setTextSize(13);keep.setChecked(convertible);keep.setEnabled(convertible);keep.setPadding(0,dp(activity,12),0,dp(activity,8));dialog.body.addView(keep);
        gap(dialog.body,15);addButtons(dialog,"开始导入",AppColors.ACCENT,AppColors.ON_ACCENT,()->{
            DocumentImport.PagePreset page=pages.getCheckedRadioButtonId()==a4.getId()?DocumentImport.PagePreset.A4:DocumentImport.PagePreset.PHONE;
            DocumentImport.FontSize font=sizes.getCheckedRadioButtonId()==small.getId()?DocumentImport.FontSize.SMALL:sizes.getCheckedRadioButtonId()==large.getId()?DocumentImport.FontSize.LARGE:DocumentImport.FontSize.MEDIUM;
            DocumentImport.LayoutOptions chosen=convertible?new DocumentImport.LayoutOptions(page,font):defaults;
            dialog.complete(callback==null?null:()->callback.chosen(keep.isChecked(),chosen));
        });dialog.show();return dialog;
    }
    private static RadioButton radio(Activity owner,String label,int id){
        RadioButton button=new RadioButton(owner);button.setId(id);button.setText(label);button.setTextColor(AppColors.INK);button.setTextSize(13);button.setMinHeight(dp(owner,48));return button;
    }

    public static TaskDialog task(Activity activity,String title,String message,Runnable cancel){
        CardDialog dialog=new CardDialog(activity);dialog.setCancelable(false);dialog.setCanceledOnTouchOutside(false);
        addTitle(dialog,title,"book",AppColors.ACCENT_DARK,false);gap(dialog.body,13);
        TextView stage=text(activity,message,14,AppColors.INK);stage.setTypeface(null,1);dialog.body.addView(stage);gap(dialog.body,7);
        TextView detail=text(activity,"准备中…",12,AppColors.MUTED);detail.setLineSpacing(dp(activity,3),1f);dialog.body.addView(detail);gap(dialog.body,14);
        ProgressBar bar=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);bar.setIndeterminate(true);bar.setProgressTintList(ColorStateList.valueOf(AppColors.ACCENT));bar.setProgressBackgroundTintList(ColorStateList.valueOf(AppColors.SOFT));dialog.body.addView(bar,new LinearLayout.LayoutParams(-1,dp(activity,4)));gap(dialog.body,18);
        Button stop=null;if(cancel!=null){stop=button(activity,"取消",AppColors.BACKGROUND,REMOVE);final Button cancelButton=stop;stop.setOnClickListener(v->{cancelButton.setEnabled(false);stage.setText("正在取消…");cancel.run();});dialog.body.addView(stop);}dialog.show();return new TaskDialog(dialog,stage,detail,bar,stop);
    }

    public static Dialog trashActions(Activity activity,String name,long deletedAt,Runnable restore,Runnable permanent){
        CardDialog dialog=new CardDialog(activity);addTitle(dialog,"最近删除","trash",REMOVE);gap(dialog.body,12);
        TextView title=text(activity,safe(name),16,AppColors.INK);title.setTypeface(null,1);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);dialog.body.addView(title);gap(dialog.body,8);
        dialog.body.addView(text(activity,"删除时间："+new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.CHINA).format(new java.util.Date(deletedAt)),12,AppColors.MUTED));gap(dialog.body,18);
        LinearLayout restoreRow=operation(activity,"undo","恢复到资料库","保留原文件夹、标签和批注",false);makeAction(restoreRow,AppColors.SOFT,"恢复到资料库",()->dialog.complete(restore));dialog.body.addView(restoreRow);gap(dialog.body,10);
        LinearLayout deleteRow=operation(activity,"trash","彻底删除","删除正文、批注、笔记图片与附件",true);makeAction(deleteRow,REMOVE_SOFT,"彻底删除",()->dialog.complete(permanent));dialog.body.addView(deleteRow);dialog.show();return dialog;
    }

    public static Dialog confirmPermanent(Activity activity,String name,Runnable confirmed){
        CardDialog dialog=new CardDialog(activity);addTitle(dialog,"彻底删除？","trash",REMOVE);gap(dialog.body,14);
        TextView message=text(activity,"“"+safe(name)+"”及其正文、批注、图片和附件将被永久删除。此操作无法撤销。",14,AppColors.INK);message.setLineSpacing(dp(activity,4),1f);dialog.body.addView(message);gap(dialog.body,22);
        addButtons(dialog,"彻底删除",REMOVE_SOFT,REMOVE,()->dialog.complete(confirmed));dialog.show();return dialog;
    }

    public static Dialog batchActions(Activity activity,int count,Runnable favorite,Runnable organize,Runnable trash){
        CardDialog dialog=new CardDialog(activity);addTitle(dialog,"已选择 "+count+" 项","check",AppColors.ACCENT_DARK);gap(dialog.body,15);
        LinearLayout fav=operation(activity,"sun","加入收藏","批量标记为收藏",false);makeAction(fav,AppColors.BACKGROUND,"批量加入收藏",()->dialog.complete(favorite));dialog.body.addView(fav);gap(dialog.body,9);
        LinearLayout org=operation(activity,"settings","移动与加标签","统一设置文件夹和标签",false);makeAction(org,AppColors.BACKGROUND,"批量整理",()->dialog.complete(organize));dialog.body.addView(org);gap(dialog.body,9);
        LinearLayout remove=operation(activity,"trash","移入最近删除","可稍后逐项恢复",true);makeAction(remove,REMOVE_SOFT,"批量移入最近删除",()->dialog.complete(trash));dialog.body.addView(remove);dialog.show();return dialog;
    }

    public static Dialog tools(Activity activity,Runnable backup,Runnable restore,Runnable trash,Runnable rebuild,
            Runnable globalSearch,Runnable sync,Runnable learning,Runnable ocr,Runnable appearance,Runnable faultReport,Runnable about){
        CardDialog dialog=new CardDialog(activity);addTitle(dialog,"设置","settings",AppColors.ACCENT_DARK);gap(dialog.body,14);
        String[] titles={"导出整库备份","从备份恢复","最近删除","扫描并修复资料库","全库搜索","同步","学习资源","OCR 识别","外观与主题","故障记录","关于晴阅"};
        String[] hints={"通过系统文件选择器保存 .qyb","严格校验后保留冲突副本","恢复或彻底删除","从本机 PDF 与笔记重建索引","搜索 PDF、笔记与文本","打开本地同步中心","打开学习与复习中心","识别扫描件与图片文字","调整浅色、深色与纸张显示","查看或复制本机诊断信息","查看当前版本号"};
        String[] icons={"book","undo","trash","settings","search","horizontal","note","image","sun","note","info"};
        Runnable[] actions={backup,restore,trash,rebuild,globalSearch,sync,learning,ocr,appearance,faultReport,about};
        for(int i=0;i<titles.length;i++){final Runnable action=actions[i];if(action==null)continue;LinearLayout item=operation(activity,icons[i],titles[i],hints[i],i==2);makeAction(item,i==2?REMOVE_SOFT:AppColors.BACKGROUND,titles[i],()->dialog.complete(action));dialog.body.addView(item);gap(dialog.body,8);}
        dialog.show();return dialog;
    }

    public static Dialog about(Activity activity) {
        String version = "暂时无法读取";
        try {
            // Read the installed APK so future releases never display a stale UI version.
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            if (!TextUtils.isEmpty(info.versionName)) version = info.versionName;
        } catch (PackageManager.NameNotFoundException ignored) { }
        CardDialog dialog = new CardDialog(activity);
        addTitle(dialog, "关于晴阅", "info", AppColors.ACCENT_DARK);
        gap(dialog.body, 22);
        TextView versionLabel = text(activity, "版本号：" + version, 18, AppColors.INK);
        versionLabel.setTextIsSelectable(true);
        dialog.body.addView(versionLabel, new LinearLayout.LayoutParams(-1, -2));
        gap(dialog.body, 24);
        Button close = button(activity, "知道了", AppColors.ACCENT, AppColors.ON_ACCENT);
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.body.addView(close, new LinearLayout.LayoutParams(-1, -2));
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
        addButtons(dialog, "保存", AppColors.ACCENT, AppColors.ON_ACCENT, save);
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
        addTitle(dialog,title,kind,color,true);
    }

    private static void addTitle(CardDialog dialog, String title, String kind, int color, boolean closable) {
        LinearLayout heading = row(dialog.owner);
        IconButton symbol = decoration(dialog.owner, kind, color);
        symbol.setBackground(shape(dialog.owner, color == REMOVE ? REMOVE_SOFT : AppColors.SOFT, 12));
        heading.addView(symbol, new LinearLayout.LayoutParams(dp(dialog.owner, 42), dp(dialog.owner, 42)));
        TextView label = text(dialog.owner, title, 18, AppColors.INK);
        label.setTypeface(null, 1);
        label.setPadding(dp(dialog.owner, 11), 0, 0, 0);
        heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        if(closable)addClose(dialog, heading);
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

    private static EditText input(Activity activity,String hint,int maxLength){
        EditText input=new EditText(activity);input.setSingleLine(true);input.setTextSize(15);input.setTextColor(AppColors.INK);input.setHintTextColor(AppColors.MUTED);input.setHint(hint);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});input.setPadding(dp(activity,14),dp(activity,12),dp(activity,14),dp(activity,12));
        GradientDrawable background=shape(activity,AppColors.BACKGROUND,14);background.setStroke(dp(activity,1),AppColors.BORDER);input.setBackground(background);return input;
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
        if (!AppColors.isClassic() && radius > 0)
            drawable.setStroke(dp(context, 1), color == AppColors.ACCENT ? AppColors.ACCENT : AppColors.BORDER);
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
        private boolean completed;

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
            if(completed||!isShowing())return;
            completed=true;
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
