package cn.qingyue.pdf;
import android.content.Context;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.concurrent.*;

public final class NoteStoreTest {
    static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
    static NoteDocument disk(Context c,String id)throws IOException{try(InputStream in=new FileInputStream(new File(c.getFilesDir(),"notes/"+id+"/document.qnote"))){return NoteDocument.read(in);}}
    public static void main(String[] args)throws Exception{
        Context context=new Context(Files.createTempDirectory("qingyue-note-test-").toFile());Field f=NoteStore.class.getDeclaredField("worker");f.setAccessible(true);ExecutorService worker=(ExecutorService)f.get(null);
        try{
            NoteDocument d=NoteStore.create(context,"first","grid","infinite");check(NoteStore.exists(context,"first"),"new note exists");check(NoteStore.load(context,"first").template.equals("grid"),"create/load disk roundtrip");
            CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);worker.submit(()->{blocked.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});blocked.await();
            d.add(new NoteDocument.Item(d.newId(),NoteDocument.LINE,0xff000000,2,new double[]{-10,-20,100,200},""));NoteStore.saveAsync(context,"first",d,m->{throw new AssertionError(m);});check(NoteStore.load(context,"first").items().size()==1,"pending snapshot immediately available on reopen");d.add(new NoteDocument.Item(d.newId(),NoteDocument.RECTANGLE,0xff111111,3,new double[]{10,20,110,220},""));NoteStore.save(context,"first",d);release.countDown();worker.submit(()->{}).get();check(disk(context,"first").items().size()==2,"older async save cannot replace newer pause save");
            CountDownLatch blocked2=new CountDownLatch(1),release2=new CountDownLatch(1);worker.submit(()->{blocked2.countDown();try{release2.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});blocked2.await();NoteStore.saveAsync(context,"first",d,m->{throw new AssertionError(m);});File image=new File(NoteStore.imagesDirectory(context,"first"),"test.jpg");try(OutputStream out=new FileOutputStream(image)){out.write(1);}NoteStore.delete(context,"first");release2.countDown();worker.submit(()->{}).get();check(!NoteStore.exists(context,"first"),"queued snapshot cannot resurrect deleted note");check(!image.exists(),"deleting note deletes private image assets");NoteStore.save(context,"first",d);check(!NoteStore.exists(context,"first"),"late lifecycle save cannot resurrect deleted note");
            try{NoteStore.imagesDirectory(context,"first");throw new AssertionError("deleted image directory recreated");}catch(IOException expected){}try{NoteStore.imagesDirectory(context,"not-created");throw new AssertionError("missing note image directory created");}catch(IOException expected){}NoteDocument recreated=NoteStore.create(context,"first","blank","A5");check(recreated.items().isEmpty()&&disk(context,"first").size.equals("A5"),"explicit create clears tombstone");try{NoteStore.imagesDirectory(context,"../outside");throw new AssertionError("unsafe note id");}catch(IOException expected){}
            System.out.println("NoteStoreTest passed: atomic disk roundtrip, pending reopen, stale async save, deletion races, image cleanup and unsafe ID rejection. Desktop Android shims only; no device claim.");
        }finally{worker.shutdownNow();}
    }
}
