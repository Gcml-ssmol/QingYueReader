package android.content;

import android.content.res.AssetManager;
import java.io.File;

public class Context {
    public static final int MODE_PRIVATE=0;
    private final File files;
    private final SharedPreferences preferences=(key,fallback)->fallback;
    public Context(File files){this.files=files;}
    public Context getApplicationContext(){return this;}
    public File getFilesDir(){return files;}
    public File getCacheDir(){File dir=new File(files,"cache");dir.mkdirs();return dir;}
    public AssetManager getAssets(){return new AssetManager();}
    public ContentResolver getContentResolver(){return new ContentResolver();}
    public SharedPreferences getSharedPreferences(String name,int mode){return preferences;}
}
