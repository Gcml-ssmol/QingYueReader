package cn.qingyue.pdf;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/** One root owns system bar, cutout and optional keyboard padding. */
public final class UiSafeArea {
    private UiSafeArea() { }

    public static void install(Activity activity, View root, boolean keyboard) {
        if (Build.VERSION.SDK_INT < 30) {
            // Pre-edge-to-edge windows already fit their content and adjustResize handles the IME.
            return;
        }
        Api30.install(activity,root,keyboard);
    }

    private static final class Api30 {
        static void install(Activity activity, View root, boolean keyboard) {
            activity.getWindow().setDecorFitsSystemWindows(false);
            final int left = root.getPaddingLeft(), top = root.getPaddingTop();
            final int right = root.getPaddingRight(), bottom = root.getPaddingBottom();
            root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets safe = windowInsets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                Insets ime = keyboard ? windowInsets.getInsets(WindowInsets.Type.ime()) : Insets.NONE;
                view.setPadding(left + safe.left, top + safe.top, right + safe.right,
                        bottom + Math.max(safe.bottom, ime.bottom));
                return windowInsets;
            });
            root.requestApplyInsets();
        }
    }
}
