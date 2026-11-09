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
            d.addPage();d.add(new NoteDocument.Item(d.newId(),NoteDocument.IMAGE,0xffffffff,1,new double[]{0,0,80,80},"test.jpg"));NoteStore.save(context,"first",d);check(disk(context,"first").pageCount()==2&&disk(context,"first").items().get(0).image.equals("test.jpg"),"all pages persisted");NoteDocument pendingCopy=NoteStore.load(context,"first");pendingCopy.selectPage(0);check(pendingCopy.items().size()==2,"earlier page preserved in snapshot");
            CountDownLatch blocked2=new CountDownLatch(1),release2=new CountDownLatch(1);worker.submit(()->{blocked2.countDown();try{release2.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});blocked2.await();NoteStore.saveAsync(context,"first",d,m->{throw new AssertionError(m);});File image=new File(NoteStore.imagesDirectory(context,"first"),"test.jpg");try(OutputStream out=new FileOutputStream(image)){out.write(1);}NoteStore.delete(context,"first");release2.countDown();worker.submit(()->{}).get();check(!NoteStore.exists(context,"first"),"queued snapshot cannot resurrect deleted note");check(!image.exists(),"deleting note deletes private image assets");NoteStore.save(context,"first",d);check(!NoteStore.exists(context,"first"),"late lifecycle save cannot resurrect deleted note");
            try{NoteStore.imagesDirectory(context,"first");throw new AssertionError("deleted image directory recreated");}catch(IOException expected){}try{NoteStore.imagesDirectory(context,"not-created");throw new AssertionError("missing note image directory created");}catch(IOException expected){}NoteDocument recreated=NoteStore.create(context,"first","blank","A5");check(recreated.items().isEmpty()&&disk(context,"first").size.equals("A5"),"explicit create clears tombstone");try{NoteStore.imagesDirectory(context,"../outside");throw new AssertionError("unsafe note id");}catch(IOException expected){}
            int committed=android.util.AtomicFile.completedWrites;
            NoteDocument reopened=NoteStore.load(context,"first");NoteStore.save(context,"first",reopened);
            NoteStore.save(context,"first",recreated);NoteStore.saveAsync(context,"first",recreated,null);worker.submit(()->{}).get();
            check(android.util.AtomicFile.completedWrites==committed,"unchanged content must not rewrite qnote");
            long revision=recreated.revision();recreated.setViewport(120,210,1.25,true);
            check(recreated.revision()>revision,"changed viewport advances save revision");
            NoteStore.save(context,"first",recreated);check(android.util.AtomicFile.completedWrites==committed+1,"changed viewport persists once");
            recreated.setViewport(120,210,1.25,true);NoteStore.save(context,"first",recreated);
            check(android.util.AtomicFile.completedWrites==committed+1,"same viewport does not rewrite qnote");
            recreated.add(new NoteDocument.Item(recreated.newId(),NoteDocument.LINE,0xff111111,2,new double[]{1,1,10,10},""));
            android.util.AtomicFile.failNextWrite=true;
            try{NoteStore.save(context,"first",recreated);throw new AssertionError("injected write must fail");}catch(IOException expected){}
            check(NoteStore.hasPending(context,"first"),"failed write retains dirty snapshot");
            check(NoteStore.load(context,"first").items().size()==1,"same-process reopen sees failed-write snapshot");
            check(disk(context,"first").items().isEmpty(),"failed write does not replace committed disk data");
            NoteStore.save(context,"first",recreated);
            check(!NoteStore.hasPending(context,"first")&&disk(context,"first").items().size()==1,"retry commits and clears dirty snapshot");
            recreated.add(new NoteDocument.Item(recreated.newId(),NoteDocument.RECTANGLE,0xff222222,2,new double[]{2,2,12,12},""));
            java.util.concurrent.atomic.AtomicReference<String> asyncFailure=new java.util.concurrent.atomic.AtomicReference<>();
            android.util.AtomicFile.failNextWrite=true;NoteStore.saveAsync(context,"first",recreated,asyncFailure::set);worker.submit(()->{}).get();
            check(asyncFailure.get()!=null&&NoteStore.hasPending(context,"first"),"failed async write reports error and retains pending snapshot");
            check(disk(context,"first").items().size()==1,"failed async write leaves prior disk version readable");
            NoteStore.save(context,"first",recreated);check(disk(context,"first").items().size()==2,"pause retry commits failed async revision");
            NoteDocument neverSavedOlder=recreated.copy();
            check(neverSavedOlder.saveSession()==recreated.saveSession(),"snapshot keeps the live weak session token");
            neverSavedOlder.add(new NoteDocument.Item(neverSavedOlder.newId(),NoteDocument.CIRCLE,0xff333333,2,new double[]{3,3,13,13},""));
            recreated.add(new NoteDocument.Item(recreated.newId(),NoteDocument.CIRCLE,0xff444444,2,new double[]{4,4,14,14},""));
            recreated.add(new NoteDocument.Item(recreated.newId(),NoteDocument.LINE,0xff555555,2,new double[]{5,5,15,15},""));
            NoteStore.save(context,"first",recreated);int latestWrites=android.util.AtomicFile.completedWrites;
            NoteStore.save(context,"first",neverSavedOlder);NoteStore.saveAsync(context,"first",neverSavedOlder,null);worker.submit(()->{}).get();
            check(android.util.AtomicFile.completedWrites==latestWrites&&disk(context,"first").items().size()==4,"never-saved older revision cannot overwrite newer committed revision");
            System.out.println("NoteStoreTest passed: atomic disk roundtrip, pending reopen, stale async save, deletion races, image cleanup and unsafe ID rejection. Desktop Android shims only; no device claim.");
        }finally{worker.shutdownNow();}
    }
}
