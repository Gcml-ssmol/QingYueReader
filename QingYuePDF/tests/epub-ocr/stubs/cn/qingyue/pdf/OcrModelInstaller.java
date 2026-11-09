package cn.qingyue.pdf;
import android.content.Context;import java.io.File;import java.io.IOException;
public final class OcrModelInstaller {
    public static int ensureCalls;
    public static File ensure(Context c,OcrCancellation token,Object progress)throws IOException{
        token.throwIfCancelled();ensureCalls++;return new File(System.getProperty("java.io.tmpdir"));
    }
}
