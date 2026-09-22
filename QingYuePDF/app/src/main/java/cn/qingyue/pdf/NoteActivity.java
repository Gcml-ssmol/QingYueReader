package cn.qingyue.pdf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native note editor. The canvas owns drawing, history and durable note data. */
public final class NoteActivity extends Activity {
    private static final int PICK_IMAGE = 31;
    private static final ExecutorService IMAGES = Executors.newSingleThreadExecutor();
    private static final ConcurrentHashMap<String, ImageJob> IMAGE_JOBS = new ConcurrentHashMap<>();
    private String noteId, name, tool = "pen";
    private NoteDocument document;
    private NoteCanvasView canvas;
    private LinearLayout root, header;
    private TextView status;
    private IconButton undo, redo, trash, stylus, imageButton, headerToggle;
    private ColorSwatch colorButton;
    private final LinkedHashMap<String, IconButton> tools = new LinkedHashMap<>();
    private int color = 0xff36291f;
    private float penSize = 2f, markerSize = 18f, eraserSize = 30f;
    private boolean stylusOnly, twoFingerUndo, resumed, destroyed, headerCollapsed;
    private PopupWindow popup;
    private Dialog dialog;
    private SharedPreferences common, preferences;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        common = getSharedPreferences("MainActivity", MODE_PRIVATE);
        preferences = getSharedPreferences("note_tools", MODE_PRIVATE);
        noteId = getIntent().getStringExtra("id"); name = getIntent().getStringExtra("name");
        if (name == null || name.trim().isEmpty()) name = "未命名笔记";
        stylusOnly = common.getBoolean("stylusOnly", true);
        twoFingerUndo = common.getBoolean("twoFingerUndo", true);
        color = preferences.getInt("color", common.getInt("inkColor", 0xff36291f)) | 0xff000000;
        penSize = clamp(preferences.getFloat("pen", 2f), .5f, 20f);
        markerSize = clamp(preferences.getFloat("marker", 18f), 4f, 60f);
        eraserSize = clamp(preferences.getFloat("eraser", 30f), 8f, 100f);
        if (state != null) { tool = state.getString("tool", "pen"); headerCollapsed = state.getBoolean("collapsed", false); }
        getWindow().setStatusBarColor(AppColors.BACKGROUND);
        getWindow().setNavigationBarColor(android.os.Build.VERSION.SDK_INT >= 27 ? AppColors.BACKGROUND : AppColors.INK);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (android.os.Build.VERSION.SDK_INT >= 27 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        root = NoteDialogs.column(this); root.setBackgroundColor(AppColors.BACKGROUND); root.setFitsSystemWindows(true); setContentView(root);
        try { document = NoteStore.load(this, noteId); }
        catch (Exception failure) {
            status = NoteDialogs.label(this, "笔记暂时无法打开", 18, AppColors.INK);
            status.setGravity(Gravity.CENTER); root.addView(status, new LinearLayout.LayoutParams(-1, -1));
            showDialog(ReaderDialogs.message(this, "无法打开笔记", "笔记文件不可用或已损坏，请返回文档库。", true));
            return;
        }
        makeHeader(); makeToolbar();
        canvas = new NoteCanvasView(this, document, noteId, new NoteCanvasView.Host() {
            @Override public void changed() { updateTools(); }
            @Override public void readingScrolled() { if (!headerCollapsed) setCollapsed(true); }
            @Override public void error(String message) { if (!destroyed) { if (status != null) {status.setText("保存或操作失败"); status.setTextColor(0xffb75448);} if (resumed) showDialog(ReaderDialogs.message(NoteActivity.this, "笔记提示", message, true)); else toast(message); } }
        });
        root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1));
        canvas.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {if (right > left && bottom > top) consumeImage();});
        setCollapsed(headerCollapsed); applyTool();
    }

    private void makeHeader() {
        header = NoteDialogs.row(this); header.setPadding(dp(7), dp(4), dp(7), dp(4));
        addIcon(header, icon("back", "返回文档库", v -> finish()));
        LinearLayout titles = NoteDialogs.column(this); titles.setPadding(dp(5), 0, dp(5), 0);
        TextView title = NoteDialogs.label(this, name, 16, AppColors.INK); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); title.setTypeface(null, 1); titles.addView(title);
        status = NoteDialogs.label(this, paperDescription() + " · 自动保存", 10, AppColors.MUTED); status.setSingleLine(true); status.setEllipsize(android.text.TextUtils.TruncateAt.END); status.setPadding(0, dp(3), 0, 0); titles.addView(status);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        addIcon(header, icon("settings", "笔记设置", v -> settings()));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(59)));
    }

    private String paperDescription() {
        String template = "ruled".equals(document.template) ? "横线" : "grid".equals(document.template) ? "网格" : "全空";
        return ("infinite".equals(document.size) ? "无限画布" : document.size) + " · " + template;
    }

    private void makeToolbar() {
        LinearLayout strip = NoteDialogs.row(this); strip.setPadding(dp(6), 0, dp(5), 0);
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false); scroll.setFillViewport(false);
        LinearLayout bar = NoteDialogs.row(this);
        String[] kinds = {"hand", "pen", "marker", "eraser", "select", "line", "circle", "rectangle", "arrow"};
        String[] labels = {"移动画布", "画笔", "荧光笔", "橡皮", "选择与调整图片", "直线", "圆形", "矩形", "箭头"};
        for (int i = 0; i < kinds.length; i++) {
            final String kind = kinds[i];
            IconButton button = icon(kind, labels[i], v -> {
                if (kind.equals(tool) && adjustable(kind)) { toolSettings(kind, v); return; }
                tool = kind; applyTool();
                if (kind.equals("select")) toast("点击图片后可拖动，拖动四角调整大小");
            });
            if (adjustable(kind)) button.setOnLongClickListener(v -> {tool = kind; applyTool(); toolSettings(kind, v); return true;});
            tools.put(kind, button); addIcon(bar, button);
        }
        divider(bar);
        undo = icon("undo", "撤销上一步", v -> {if (canvas != null) canvas.undo();});
        redo = icon("redo", "重做上一步", v -> {if (canvas != null) canvas.redo();}); addIcon(bar, undo); addIcon(bar, redo);
        colorButton = new ColorSwatch(this, color, "颜色与调色盘");
        colorButton.setOnClickListener(v -> toolSettings(adjustable(tool) && !tool.equals("eraser") ? tool : "pen", v));
        bar.addView(colorButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        divider(bar);
        imageButton = icon("image", "插入图片", v -> pickImage()); addIcon(bar, imageButton);
        trash = icon("trash", "删除选中的图片", v -> {if (canvas != null) canvas.deleteSelected();}); addIcon(bar, trash);
        stylus = icon("stylus", "仅手写笔绘制", v -> {
            stylusOnly = !stylusOnly; common.edit().putBoolean("stylusOnly", stylusOnly).apply(); applyTool();
            toast(stylusOnly ? "仅手写笔绘制；手指移动画布" : "已允许手指绘制；双指移动画布");
        }); addIcon(bar, stylus);
        addIcon(bar, icon("fit", "恢复画布位置与缩放", v -> {if (canvas != null) canvas.fit();}));
        scroll.addView(bar); strip.addView(scroll, new LinearLayout.LayoutParams(0, dp(48), 1));
        divider(strip); headerToggle = icon("down", "收起标题栏", v -> setCollapsed(!headerCollapsed)); addIcon(strip, headerToggle);
        root.addView(strip, new LinearLayout.LayoutParams(-1, dp(48)));
        View edge = new View(this); edge.setBackgroundColor(AppColors.BORDER); root.addView(edge, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void setCollapsed(boolean collapsed) {
        headerCollapsed = collapsed; if (header == null || headerToggle == null) return;
        dismissPopup(); header.setVisibility(collapsed ? View.GONE : View.VISIBLE); headerToggle.setRotation(collapsed ? 0 : 180);
        headerToggle.setContentDescription(collapsed ? "展开笔记标题与设置" : "收起标题栏");
    }
    private void settings() {
        if (canvas == null) return;
        dismissPopup(); canvas.finishGesture();
        showDialog(NoteDialogs.settings(this, stylusOnly, twoFingerUndo, (pen, undoEnabled) -> {
            stylusOnly = pen; twoFingerUndo = undoEnabled;
            common.edit().putBoolean("stylusOnly", pen).putBoolean("twoFingerUndo", undoEnabled).apply(); applyTool();
        }, () -> {if (canvas != null) canvas.fit();}));
    }
    private boolean adjustable(String kind) { return !"hand".equals(kind) && !"select".equals(kind); }
    private float size(String kind) { return "marker".equals(kind) ? markerSize : "eraser".equals(kind) ? eraserSize : penSize; }
    private void applyTool() {
        if (!tools.containsKey(tool)) tool = "pen";
        if (canvas != null) {canvas.setTool(tool, color, size(tool), stylusOnly); canvas.setTwoFingerUndoEnabled(twoFingerUndo);}
        preferences.edit().putInt("color", color).putFloat("pen", penSize).putFloat("marker", markerSize).putFloat("eraser", eraserSize).apply();
        updateTools();
    }
    private void updateTools() {
        for (Map.Entry<String, IconButton> entry : tools.entrySet()) entry.getValue().setActive(entry.getKey().equals(tool));
        if (colorButton != null) colorButton.setColor(color);
        if (stylus != null) {stylus.setActive(stylusOnly); stylus.setContentDescription("仅手写笔绘制，" + (stylusOnly ? "已开启" : "已关闭"));}
        if (canvas != null) {undo.setEnabled(canvas.canUndo()); redo.setEnabled(canvas.canRedo()); trash.setEnabled(canvas.hasSelection());}
        if (imageButton != null) {imageButton.setEnabled(!IMAGE_JOBS.containsKey(noteId)); imageButton.setContentDescription(IMAGE_JOBS.containsKey(noteId) ? "正在导入图片" : "插入图片");}
    }

    private void toolSettings(String kind, View anchor) {
        if (canvas == null) return;
        dismissPopup(); canvas.finishGesture();
        final boolean erasing = kind.equals("eraser");
        LinearLayout shell = NoteDialogs.column(this); shell.setPadding(dp(7), dp(5), dp(7), dp(8)); shell.setFocusableInTouchMode(true);
        LinearLayout heading = NoteDialogs.row(this); heading.setPadding(dp(6), 0, 0, 0);
        IconButton decoration = icon(kind, "", null); decoration.setTint(AppColors.ACCENT_DARK); decoration.setBackground(NoteDialogs.shape(this, AppColors.SOFT, 12)); decoration.setOnLongClickListener(null); decoration.setClickable(false); decoration.setFocusable(false); addIcon(heading, decoration);
        TextView title = NoteDialogs.label(this, erasing ? "橡皮" : kind.equals("marker") ? "荧光笔" : "笔迹与图形", 17, AppColors.INK); title.setTypeface(null, 1); title.setPadding(dp(10), 0, 0, 0); heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); addIcon(heading, icon("close", "关闭工具设置", v -> dismissPopup())); shell.addView(heading);
        ScrollView scrolling = new ScrollView(this); scrolling.setVerticalScrollBarEnabled(false);
        LinearLayout panel = NoteDialogs.column(this); panel.setPadding(dp(12), dp(12), dp(12), dp(8)); scrolling.addView(panel); shell.addView(scrolling, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView widthLabel = NoteDialogs.label(this, "", 12, AppColors.MUTED); panel.addView(widthLabel); NoteDialogs.gap(panel, 8);
        SizePreviewView preview = new SizePreviewView(this); panel.addView(preview, new LinearLayout.LayoutParams(-1, dp(88)));
        Runnable refresh = () -> {
            widthLabel.setText((erasing ? "擦除范围" : "线条粗细") + String.format(Locale.CHINA, "  ·  %.1f pt", size(kind)));
            preview.setPreview(kind.equals("marker") ? (color & 0xffffff) | 0x55000000 : color, canvas.previewDiameter(size(kind)), erasing);
        };
        float minimum = erasing ? 8f : kind.equals("marker") ? 4f : .5f;
        float maximum = erasing ? 100f : kind.equals("marker") ? 60f : 20f;
        SeekBar seek = new SeekBar(this); seek.setMax(100); seek.setProgress(Math.round(100 * (float)(Math.log(size(kind) / minimum) / Math.log(maximum / minimum)))); seek.setContentDescription(erasing ? "橡皮范围大小" : "笔画粗细");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar view) { }
            @Override public void onStopTrackingTouch(SeekBar view) { }
            @Override public void onProgressChanged(SeekBar view, int progress, boolean user) {
                if (!user) return;
                float width = (float)(minimum * Math.pow(maximum / minimum, progress / 100f));
                if (erasing) eraserSize = width; else if (kind.equals("marker")) markerSize = width; else penSize = width;
                refresh.run(); applyTool();
            }
        }); panel.addView(seek, new LinearLayout.LayoutParams(-1, dp(44)));
        EditText hex = new EditText(this); hex.setTextColor(AppColors.INK); hex.setTextSize(14); hex.setSingleLine(true); hex.setSelectAllOnFocus(true); hex.setText(hex(color)); hex.setHint("#RRGGBB"); hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)}); hex.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        if (erasing) {
            NoteDialogs.gap(panel, 8); panel.addView(NoteDialogs.label(this, "圆圈表示擦除范围，触及的笔画或图形会被移除。图片请用选择工具调整或删除。", 12, AppColors.MUTED));
        } else {
            NoteDialogs.gap(panel, 12); panel.addView(NoteDialogs.label(this, "常用颜色", 12, AppColors.MUTED));
            int[] colors = {0xff000000, 0xff666666, 0xffaaaaaa, 0xffffffff, 0xffe53935, 0xffff7043, 0xfff6c343, 0xff81c784, 0xff16805b, 0xff00acc1, 0xff2464d6, 0xff3949ab, 0xff8e44ad, 0xffd94f9b, 0xff8d6e63, 0xffffcdd2, 0xffc8e6c9, 0xffbbdefb};
            ArrayList<ColorSwatch> swatches = new ArrayList<>();
            ColorPickerView picker = new ColorPickerView(this); picker.setColor(color);
            final boolean[] syncing = {false};
            ColorPickerView.Listener choose = chosen -> {
                color = chosen | 0xff000000;
                syncing[0] = true; hex.setText(hex(color)); hex.setError(null); picker.setColor(color); syncing[0] = false;
                for (int i = 0; i < colors.length; i++) swatches.get(i).setActive(colors[i] == color);
                refresh.run(); applyTool();
            };
            for (int r = 0; r < 3; r++) {
                LinearLayout row = NoteDialogs.row(this);
                for (int col = 0; col < 6; col++) {
                    final int value = colors[r * 6 + col];
                    ColorSwatch swatch = new ColorSwatch(this, value, hex(value)); swatch.setActive(value == color); swatch.setOnClickListener(v -> choose.changed(value)); swatches.add(swatch); row.addView(swatch, new LinearLayout.LayoutParams(0, dp(42), 1));
                }
                panel.addView(row);
            }
            NoteDialogs.gap(panel, 12); panel.addView(NoteDialogs.label(this, "自由调色", 12, AppColors.MUTED)); panel.addView(picker, new LinearLayout.LayoutParams(-1, dp(160)));
            picker.setListener(choose);
            LinearLayout codeRow = NoteDialogs.row(this); codeRow.addView(NoteDialogs.label(this, "HEX", 11, AppColors.MUTED)); LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(0, dp(46), 1); codeParams.leftMargin = dp(12); codeRow.addView(hex, codeParams); panel.addView(codeRow);
            hex.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void afterTextChanged(Editable value) { }
                @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                    if (syncing[0]) return;
                    String text = value.toString();
                    if (text.matches("#?[0-9a-fA-F]{6}")) {
                        color = Color.parseColor(text.startsWith("#") ? text : "#" + text); picker.setColor(color); hex.setError(null);
                        for (int i = 0; i < colors.length; i++) swatches.get(i).setActive(colors[i] == color);
                        refresh.run(); applyTool();
                    } else if (text.length() >= 6) hex.setError("请输入 6 位十六进制色号");
                }
            });
        }
        refresh.run();
        PopupWindow window = new PopupWindow(shell, dp(356), dp(erasing ? 300 : 610), true); window.setBackgroundDrawable(NoteDialogs.shape(this, AppColors.SURFACE, 20)); window.setElevation(dp(12)); window.setOutsideTouchable(true); window.setClippingEnabled(true); window.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED); window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); popup = window;
        Runnable position = () -> {
            if (popup != window || !anchor.isAttachedToWindow()) return;
            Rect visible = new Rect(); (shell.isAttachedToWindow() ? shell : root).getWindowVisibleDisplayFrame(visible);
            View decor = root.getRootView(); int[] decorPosition = new int[2]; decor.getLocationOnScreen(decorPosition); Rect bounded = new Rect(visible);
            if (decor.getWidth() > 0 && decor.getHeight() > 0 && bounded.intersect(decorPosition[0], decorPosition[1], decorPosition[0] + decor.getWidth(), decorPosition[1] + decor.getHeight())) visible.set(bounded);
            if (visible.width() <= 0 || visible.height() <= 0) return;
            int[] at = new int[2], screen = new int[2], inWindow = new int[2]; anchor.getLocationOnScreen(at); root.getLocationOnScreen(screen); root.getLocationInWindow(inWindow);
            int[] box = PopupPlacement.place(visible.left, visible.top, visible.right, visible.bottom, at[0], at[1], at[0] + anchor.getWidth(), at[1] + anchor.getHeight(), dp(356), dp(erasing ? 300 : 610), dp(10), dp(7), dp(180));
            int x = box[0] - screen[0] + inWindow[0], y = box[1] - screen[1] + inWindow[1];
            if (window.isShowing()) window.update(x, y, box[2], box[3]); else {window.setWidth(box[2]); window.setHeight(box[3]); window.showAtLocation(root, Gravity.TOP | Gravity.LEFT, x, y);}
        };
        ViewTreeObserver.OnGlobalLayoutListener listener = position::run; root.getViewTreeObserver().addOnGlobalLayoutListener(listener); shell.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        window.setOnDismissListener(() -> {
            if (root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
            if (shell.getViewTreeObserver().isAlive()) shell.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
            if (popup == window) popup = null;
            android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null && hex.hasFocus()) keyboard.hideSoftInputFromWindow(hex.getWindowToken(), 0);
        });
        hex.setOnEditorActionListener((view, action, event) -> {if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE); if (keyboard != null) keyboard.hideSoftInputFromWindow(hex.getWindowToken(), 0); shell.requestFocus(); return true;} return false;});
        shell.requestFocus(); position.run();
    }

    private void pickImage() {
        if (canvas == null || IMAGE_JOBS.containsKey(noteId)) return;
        dismissPopup(); canvas.finishGesture(); canvas.save();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {startActivityForResult(intent, PICK_IMAGE);} catch (android.content.ActivityNotFoundException exception) {showDialog(ReaderDialogs.message(this, "无法选择图片", "设备没有可用的文件选择器。", true));}
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_IMAGE || result != RESULT_OK || data == null || data.getData() == null || canvas == null) return;
        final Uri uri = data.getData(); final Context app = getApplicationContext();
        ImageJob job = new ImageJob(noteId); job.owner = new WeakReference<>(this);
        if (IMAGE_JOBS.putIfAbsent(noteId, job) != null) return;
        updateTools(); toast("正在插入图片…");
        IMAGES.execute(() -> {
            try {if (!job.cancelled) prepareImage(app, uri, job);} catch (OutOfMemoryError memory) {job.error = "图片太大，内存不足。请尝试尺寸较小的图片。";} catch (Exception failure) {job.error = failure.getMessage() == null ? "无法读取图片，请换一张图片重试。" : failure.getMessage();}
            job.ready = true;
            if (job.cancelled) {discardImage(app, job); return;}
            NoteActivity target = job.owner.get(); if (target != null) target.runOnUiThread(target::consumeImage);
        });
    }
    private void consumeImage() {
        if (!resumed || destroyed || canvas == null) return;
        ImageJob job = IMAGE_JOBS.get(noteId); if (job == null) {updateTools(); return;}
        job.owner = new WeakReference<>(this); if (!job.ready) {updateTools(); return;}
        if (canvas.getWidth() <= 0 || canvas.getHeight() <= 0) return;
        if (!IMAGE_JOBS.remove(noteId, job)) return;
        updateTools();
        if (job.error != null) {showDialog(ReaderDialogs.message(this, "图片未能插入", job.error, true)); return;}
        if (canvas.insertImage(job.filename, job.width, job.height)) {
            tool = "select"; applyTool(); toast("图片已插入：拖动移动，拖动四角调整大小");
        } else discardImage(getApplicationContext(), job);
    }
    private static void prepareImage(Context context, Uri uri, ImageJob job) throws IOException {
        File input = File.createTempFile("note-image-", ".tmp", context.getCacheDir());
        Bitmap bitmap = null; File output = null;
        try {
            try (InputStream source = context.getContentResolver().openInputStream(uri); FileOutputStream destination = new FileOutputStream(input)) {
                if (source == null) throw new IOException("无法打开所选图片。");
                byte[] buffer = new byte[32768]; int read; long total = 0;
                while ((read = source.read(buffer)) != -1) {if (job.cancelled) throw new IOException("图片导入已取消。"); total += read; if (total > 32L * 1024 * 1024) throw new IOException("单张图片请控制在 32 MB 以内。"); destination.write(buffer, 0, read);}
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeFile(input.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("无法识别这张图片，请使用 JPG、PNG 或设备支持的图片格式。");
            int sample = 1; while ((long)bounds.outWidth / sample > 2048 || (long)bounds.outHeight / sample > 2048) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bitmap = BitmapFactory.decodeFile(input.getAbsolutePath(), options);
            if (bitmap == null) throw new IOException("图片解码失败，请使用另一张图片。");
            Matrix rotation = new Matrix();
            try {
                ExifInterface exif = new ExifInterface(input.getAbsolutePath());
                int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                switch (orientation) {
                    case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: rotation.setScale(-1, 1); break;
                    case ExifInterface.ORIENTATION_ROTATE_180: rotation.setRotate(180); break;
                    case ExifInterface.ORIENTATION_FLIP_VERTICAL: rotation.setScale(1, -1); break;
                    case ExifInterface.ORIENTATION_TRANSPOSE: rotation.setRotate(90); rotation.postScale(-1, 1); break;
                    case ExifInterface.ORIENTATION_ROTATE_90: rotation.setRotate(90); break;
                    case ExifInterface.ORIENTATION_TRANSVERSE: rotation.setRotate(270); rotation.postScale(-1, 1); break;
                    case ExifInterface.ORIENTATION_ROTATE_270: rotation.setRotate(270); break;
                    default: break;
                }
            } catch (IOException ignored) { }
            if (!rotation.isIdentity()) {Bitmap corrected = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), rotation, true); if (corrected != bitmap) {bitmap.recycle(); bitmap = corrected;}}
            boolean alpha = bitmap.hasAlpha(); String basename = UUID.randomUUID().toString() + (alpha ? ".png" : ".jpg");
            output = File.createTempFile("note-image-ready-", ".tmp", context.getCacheDir());
            try (FileOutputStream destination = new FileOutputStream(output)) {if (!bitmap.compress(alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 92, destination)) throw new IOException("无法保存图片。"); destination.getFD().sync();}
            synchronized (NoteStore.class) {
                if (job.cancelled) throw new IOException("图片导入已取消。");
                if (!NoteStore.exists(context, job.noteId)) throw new IOException("这份笔记已被移除。");
                File target = new File(NoteStore.imagesDirectory(context, job.noteId), basename);
                if (!output.renameTo(target)) throw new IOException("无法保存图片。");
                job.filename = basename; job.width = bitmap.getWidth(); job.height = bitmap.getHeight();
            }
        } finally {if (bitmap != null) bitmap.recycle(); input.delete(); if (output != null) output.delete();}
    }
    private static void discardImage(Context context, ImageJob job) {
        synchronized (NoteStore.class) {
            if (job.filename == null || !NoteStore.exists(context, job.noteId)) return;
            try {new File(NoteStore.imagesDirectory(context, job.noteId), job.filename).delete();} catch (IOException ignored) { }
        }
    }
    private static final class ImageJob {
        final String noteId; volatile boolean ready, cancelled; volatile String error, filename; volatile int width, height;
        volatile WeakReference<NoteActivity> owner = new WeakReference<>(null);
        ImageJob(String noteId) {this.noteId = noteId;}
    }

    private void showDialog(Dialog next) {if (dialog != null) dialog.dismiss(); dialog = next; next.setOnDismissListener(v -> {if (dialog == next) dialog = null;});}
    private void dismissPopup() {if (popup != null) popup.dismiss();}
    private IconButton icon(String kind, String title, View.OnClickListener listener) {IconButton view = new IconButton(this, kind, title); view.setOnClickListener(listener); return view;}
    private void addIcon(LinearLayout parent, View view) {parent.addView(view, new LinearLayout.LayoutParams(dp(44), dp(44)));}
    private void divider(LinearLayout parent) {View view = new View(this); view.setBackgroundColor(AppColors.BORDER); LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(1), dp(23)); params.setMargins(dp(5), 0, dp(5), 0); parent.addView(view, params);}
    private int dp(float value) {return NoteDialogs.dp(this, value);}
    private String hex(int value) {return String.format(Locale.ROOT, "#%06X", value & 0xffffff);}
    private void toast(String text) {Toast.makeText(this, text, Toast.LENGTH_LONG).show();}
    private static float clamp(float value, float minimum, float maximum) {return Float.isNaN(value) || Float.isInfinite(value) ? minimum : Math.max(minimum, Math.min(maximum, value));}
    @Override protected void onResume() {super.onResume(); resumed = true; consumeImage();}
    @Override protected void onPause() {resumed = false; dismissPopup(); if (dialog != null) {dialog.dismiss(); dialog = null;} if (canvas != null) {canvas.finishGesture(); canvas.save();} super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state) {super.onSaveInstanceState(state); state.putString("tool", tool); state.putBoolean("collapsed", headerCollapsed);}
    @Override protected void onDestroy() {
        destroyed = true; dismissPopup(); if (dialog != null) {dialog.dismiss(); dialog = null;} if (canvas != null) {canvas.dispose(); canvas = null;}
        ImageJob job = noteId == null ? null : IMAGE_JOBS.get(noteId);
        if (job != null && job.owner.get() == this) {
            job.owner = new WeakReference<>(null);
            if (isFinishing()) {job.cancelled = true; IMAGE_JOBS.remove(noteId, job); discardImage(getApplicationContext(), job);}
        }
        super.onDestroy();
    }
}
