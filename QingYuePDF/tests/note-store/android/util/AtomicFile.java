package android.util;
import java.io.*;
/** Test-only filesystem shim implementing atomic snapshot behavior. */
public final class AtomicFile {
    private final File file,fresh;
    public AtomicFile(File file){this.file=file;fresh=new File(file+".new");}
    public FileInputStream openRead()throws IOException{return new FileInputStream(file);}
    public FileOutputStream startWrite()throws IOException{return new FileOutputStream(fresh);}
    public void finishWrite(FileOutputStream stream){try{stream.getFD().sync();stream.close();java.nio.file.Files.move(fresh.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}catch(IOException e){throw new RuntimeException(e);}}
    public void failWrite(FileOutputStream stream){try{stream.close();}catch(IOException ignored){}fresh.delete();}
}
