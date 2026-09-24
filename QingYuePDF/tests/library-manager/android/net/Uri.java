package android.net;
import java.io.File;
public class Uri {
    private final File file;
    public Uri(File file) { this.file = file; }
    public File file() { return file; }
}
