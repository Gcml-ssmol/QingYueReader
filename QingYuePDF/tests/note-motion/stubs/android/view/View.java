package android.view;
import android.content.Context;
import java.util.ArrayList;
public class View {
    public interface OnAttachStateChangeListener {void onViewAttachedToWindow(View view);void onViewDetachedFromWindow(View view);}
    public final Context context;
    public final Animator animator=new Animator(this);
    public final ArrayList<OnAttachStateChangeListener> listeners=new ArrayList<>();
    public float alpha=1,translationY;
    public boolean attached=true;
    public View(Context context){this.context=context;}
    public Context getContext(){return context;}
    public Animator animate(){return animator;}
    public void setAlpha(float value){alpha=value;}
    public void setTranslationY(float value){translationY=value;}
    public boolean isAttachedToWindow(){return attached;}
    public void addOnAttachStateChangeListener(OnAttachStateChangeListener listener){listeners.add(listener);}
    public void removeOnAttachStateChangeListener(OnAttachStateChangeListener listener){listeners.remove(listener);}
    public void detach(){attached=false;for(OnAttachStateChangeListener listener:new ArrayList<>(listeners))listener.onViewDetachedFromWindow(this);}
    public static class Animator {
        private final View view;private Runnable end;
        public int cancels,starts;public long duration;
        Animator(View view){this.view=view;}
        public Animator alpha(float value){return this;}
        public Animator translationY(float value){return this;}
        public Animator setDuration(long value){duration=value;return this;}
        public Animator withEndAction(Runnable value){end=value;return this;}
        public void start(){starts++;}
        public void cancel(){cancels++;end=null;}
        public void finish(){if(end!=null){Runnable action=end;end=null;action.run();}}
    }
}
