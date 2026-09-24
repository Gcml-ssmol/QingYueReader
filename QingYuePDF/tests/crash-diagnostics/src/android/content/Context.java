package android.content;
import android.content.pm.PackageManager;import java.io.File;
public abstract class Context {
    public static final String ACTIVITY_SERVICE="activity";
    public Context getApplicationContext(){return this;}
    public abstract SharedPreferences getSharedPreferences(String name,int mode);
    public abstract File getFilesDir();
    public abstract Object getSystemService(String name);
    public abstract String getPackageName();
    public abstract PackageManager getPackageManager();
}
