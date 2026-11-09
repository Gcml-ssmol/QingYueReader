package android.app;
public class Activity extends android.content.Context {
    public static final int OVERRIDE_TRANSITION_OPEN=0, OVERRIDE_TRANSITION_CLOSE=1;
    public int first,second,calls,modernCalls,transitionType;
    public void overridePendingTransition(int first,int second){this.first=first;this.second=second;calls++;}
    public void overrideActivityTransition(int type,int first,int second){
        this.transitionType=type;this.first=first;this.second=second;modernCalls++;
    }
}
