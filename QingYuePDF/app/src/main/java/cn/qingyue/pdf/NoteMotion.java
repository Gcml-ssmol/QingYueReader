package cn.qingyue.pdf;

import android.app.Activity;
import android.content.Context;
import android.provider.Settings;
import android.os.Build;
import android.view.View;
import java.util.WeakHashMap;

/** Short navigation transitions outside the writing canvas. */
public final class NoteMotion {
    private static final WeakHashMap<View, View.OnAttachStateChangeListener> DETACH = new WeakHashMap<>();
    private NoteMotion() { }

    public static boolean enabled(Context context) {
        try {
            return Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
                    && Settings.Global.getFloat(context.getContentResolver(), Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) > 0f
                    && Settings.Global.getFloat(context.getContentResolver(), Settings.Global.WINDOW_ANIMATION_SCALE, 1f) > 0f;
        } catch (SecurityException ignored) { return true; }
    }

    public static void enter(View target) {
        if (target == null) return;
        target.animate().cancel();
        View.OnAttachStateChangeListener previous = DETACH.remove(target);
        if (previous != null) target.removeOnAttachStateChangeListener(previous);
        target.setAlpha(1f);
        target.setTranslationY(0f);
        if (!enabled(target.getContext()) || !target.isAttachedToWindow()) return;
        View.OnAttachStateChangeListener detachReset = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) {
                view.animate().cancel(); view.setAlpha(1f); view.setTranslationY(0f);
                view.removeOnAttachStateChangeListener(this);
                DETACH.remove(view);
            }
        };
        target.addOnAttachStateChangeListener(detachReset);
        DETACH.put(target, detachReset);
        target.setAlpha(0f);
        target.setTranslationY(NoteDialogs.dp(target.getContext(), 10));
        target.animate().alpha(1f).translationY(0f).setDuration(180).withEndAction(() -> {
            target.setAlpha(1f); target.setTranslationY(0f);
            target.removeOnAttachStateChangeListener(detachReset);
            if (DETACH.get(target) == detachReset) DETACH.remove(target);
        }).start();
    }

    public static void applyActivityTransition(Activity activity, boolean entering) {
        if (activity == null) return;
        if (!enabled(activity)) {
            if (Build.VERSION.SDK_INT >= 34) Api34.transition(activity, entering, 0, 0);
            else activity.overridePendingTransition(0, 0);
            return;
        }
        int first = anim(activity, entering ? "note_enter" : "note_return_enter");
        int second = anim(activity, entering ? "note_exit" : "note_return_exit");
        if (first != 0 && second != 0) {
            if (Build.VERSION.SDK_INT >= 34) Api34.transition(activity, entering, first, second);
            else activity.overridePendingTransition(first, second);
        }
    }

    private static final class Api34 {
        static void transition(Activity activity, boolean entering, int first, int second) {
            activity.overrideActivityTransition(
                    entering ? Activity.OVERRIDE_TRANSITION_OPEN : Activity.OVERRIDE_TRANSITION_CLOSE,
                    first, second);
        }
    }

    private static int anim(Context context, String name) {
        return context.getResources().getIdentifier(name, "anim", context.getPackageName());
    }
}
