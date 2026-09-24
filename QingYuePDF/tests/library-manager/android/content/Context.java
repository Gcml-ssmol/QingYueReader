package android.content;

import java.io.File;

/** Test-only desktop Context; never included in the APK. */
public class Context {
    public static final int MODE_PRIVATE = 0;
    private final File files;
    private final SharedPreferences emptyPreferences = (key, fallback) -> fallback;

    public Context(File files) { this.files = files; }
    public File getFilesDir() { return files; }
    public File getCacheDir() { File cache = new File(files, "cache"); cache.mkdirs(); return cache; }
    public ContentResolver getContentResolver() { return new ContentResolver(); }
    public Context getApplicationContext() { return this; }
    public SharedPreferences getSharedPreferences(String name, int mode) { return emptyPreferences; }
}
