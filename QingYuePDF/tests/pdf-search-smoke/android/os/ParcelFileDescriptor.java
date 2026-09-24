package android.os;
import java.io.*;
public class ParcelFileDescriptor implements AutoCloseable {public static final int MODE_READ_ONLY=0;public static ParcelFileDescriptor open(File file,int mode)throws FileNotFoundException{return new ParcelFileDescriptor();}public void close(){}}
