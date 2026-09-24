package android.app;
import android.content.Context;import android.content.SharedPreferences;import android.content.pm.PackageManager;import java.io.File;
public class Application extends Context {
    /** Test-only base context for exercising the real final Application subclass. */
    public Context testBaseContext;
    @Override public Context getApplicationContext(){return testBaseContext==null?this:testBaseContext;}
    public void onCreate(){}
    public SharedPreferences getSharedPreferences(String name,int mode){throw new UnsupportedOperationException();}
    public File getFilesDir(){throw new UnsupportedOperationException();}
    public Object getSystemService(String name){throw new UnsupportedOperationException();}
    public String getPackageName(){throw new UnsupportedOperationException();}
    public PackageManager getPackageManager(){throw new UnsupportedOperationException();}
}
