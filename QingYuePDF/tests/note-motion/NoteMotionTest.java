package cn.qingyue.pdf;
import android.app.Activity;
import android.os.Build;
import android.view.View;

public final class NoteMotionTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        Activity activity=new Activity();View view=new View(activity);
        NoteMotion.enter(view);
        check(view.alpha==0&&view.translationY==10&&view.animator.starts==1,"entry animation starts");
        NoteMotion.enter(view);
        check(view.listeners.size()==1&&view.animator.cancels==2,"repeat replaces old animation and detach listener");
        view.detach();
        check(view.alpha==1&&view.translationY==0&&view.listeners.isEmpty(),"detached view restores normal state");
        View disabled=new View(activity);
        for(int axis=0;axis<3;axis++){
            activity.resolver.animator=activity.resolver.transition=activity.resolver.window=1;
            if(axis==0)activity.resolver.animator=0;
            if(axis==1)activity.resolver.transition=0;
            if(axis==2)activity.resolver.window=0;
            disabled.setAlpha(.4f);disabled.setTranslationY(7);
            NoteMotion.enter(disabled);
            check(disabled.alpha==1&&disabled.translationY==0&&disabled.animator.starts==0,"disabled scale restores view");
            NoteMotion.applyActivityTransition(activity,true);
            check(activity.calls==axis+1&&activity.first==0&&activity.second==0,"disabled scale explicitly removes activity transition");
        }
        activity.resolver.animator=activity.resolver.transition=activity.resolver.window=1;
        NoteMotion.applyActivityTransition(activity,true);
        check(activity.calls==4&&activity.first=="note_enter".hashCode()&&activity.second=="note_exit".hashCode(),"enter resources");
        NoteMotion.applyActivityTransition(activity,false);
        check(activity.calls==5&&activity.first=="note_return_enter".hashCode()&&activity.second=="note_return_exit".hashCode(),"return resources");
        Build.VERSION.SDK_INT=34;
        NoteMotion.applyActivityTransition(activity,true);
        check(activity.modernCalls==1&&activity.transitionType==Activity.OVERRIDE_TRANSITION_OPEN
                &&activity.first=="note_enter".hashCode(),"API 34 open route");
        NoteMotion.applyActivityTransition(activity,false);
        check(activity.modernCalls==2&&activity.transitionType==Activity.OVERRIDE_TRANSITION_CLOSE
                &&activity.first=="note_return_enter".hashCode(),"API 34 close route");
        activity.resolver.animator=0;
        NoteMotion.applyActivityTransition(activity,false);
        check(activity.modernCalls==3&&activity.first==0&&activity.second==0,
                "API 34 respects disabled animation scale");
    }
}
