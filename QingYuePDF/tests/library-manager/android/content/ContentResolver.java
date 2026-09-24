package android.content;
import android.net.Uri;
import java.io.*;
public class ContentResolver {
    public InputStream openInputStream(Uri uri) throws IOException { return new FileInputStream(uri.file()); }
    public OutputStream openOutputStream(Uri uri, String mode) throws IOException { return new FileOutputStream(uri.file()); }
}
