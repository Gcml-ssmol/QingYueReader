package android.util;
import java.io.*;import java.nio.file.*;
public final class AtomicFile {
    private final File base,fresh;public AtomicFile(File base){this.base=base;fresh=new File(base+".new");}
    public FileInputStream openRead()throws FileNotFoundException{return new FileInputStream(base);}
    public FileOutputStream startWrite()throws FileNotFoundException{return new FileOutputStream(fresh);}
    public void finishWrite(FileOutputStream stream){try{stream.getFD().sync();stream.close();Files.move(fresh.toPath(),base.toPath(),StandardCopyOption.REPLACE_EXISTING);}catch(IOException problem){throw new RuntimeException(problem);}}
    public void failWrite(FileOutputStream stream){try{stream.close();}catch(IOException ignored){}fresh.delete();}
}
