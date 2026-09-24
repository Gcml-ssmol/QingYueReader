package android.app;
import java.util.List;
public abstract class ActivityManager {public abstract List<ApplicationExitInfo> getHistoricalProcessExitReasons(String packageName,int pid,int max);}
