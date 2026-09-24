package android.content;
import java.io.File;
import android.content.res.AssetManager;
public class Context {
    public static final int MODE_PRIVATE=0;
    private final File files;
    public Context(File files){this.files=files;}
    public File getFilesDir(){return files;}
    public File getCacheDir(){File cache=new File(files,"cache");cache.mkdirs();return cache;}
    public Context getApplicationContext(){return this;}
    public SharedPreferences getSharedPreferences(String name,int mode){return (key,fallback)->fallback;}
    public ContentResolver getContentResolver(){return new ContentResolver();}
    public AssetManager getAssets(){return new AssetManager();}
}
