package android.os;
import java.io.*;
public final class ParcelFileDescriptor implements AutoCloseable {
    public static final int MODE_READ_ONLY=0;
    public static ParcelFileDescriptor open(File file,int mode)throws FileNotFoundException{if(!file.isFile())throw new FileNotFoundException(file.toString());return new ParcelFileDescriptor();}
    public void close(){}
}
