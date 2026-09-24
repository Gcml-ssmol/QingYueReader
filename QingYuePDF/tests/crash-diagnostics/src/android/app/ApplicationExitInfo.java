package android.app;
public class ApplicationExitInfo {
    public static final int REASON_CRASH=4,REASON_CRASH_NATIVE=5,REASON_ANR=6,REASON_USER_REQUESTED=10;
    private final String process;private final long time;private final int reason;
    public ApplicationExitInfo(String process,long time,int reason){this.process=process;this.time=time;this.reason=reason;}
    public String getProcessName(){return process;}public long getTimestamp(){return time;}public int getReason(){return reason;}
}
