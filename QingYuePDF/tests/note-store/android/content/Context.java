package android.content;
import java.io.File;
/** Test-only desktop Context; never included in the APK. */
public class Context {
    private final File files;
    public Context(File files){this.files=files;}
    public File getFilesDir(){return files;}
    public Context getApplicationContext(){return this;}
}
