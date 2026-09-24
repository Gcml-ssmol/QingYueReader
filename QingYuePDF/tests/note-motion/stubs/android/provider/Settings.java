package android.provider;
import android.content.ContentResolver;
public class Settings {
    public static class Global {
        public static final String ANIMATOR_DURATION_SCALE="animator";
        public static final String TRANSITION_ANIMATION_SCALE="transition";
        public static final String WINDOW_ANIMATION_SCALE="window";
        public static float getFloat(ContentResolver resolver,String key,float fallback){
            if(key.equals(ANIMATOR_DURATION_SCALE))return resolver.animator;
            if(key.equals(TRANSITION_ANIMATION_SCALE))return resolver.transition;
            if(key.equals(WINDOW_ANIMATION_SCALE))return resolver.window;
            return fallback;
        }
    }
}
