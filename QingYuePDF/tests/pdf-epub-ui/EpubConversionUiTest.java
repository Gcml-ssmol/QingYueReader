package cn.qingyue.pdf;
import android.content.Context;
import java.io.*;import java.nio.file.Files;import java.util.*;import java.util.concurrent.*;
public final class EpubConversionUiTest {
    private static int checks;
    private static final String ID="11111111-1111-1111-1111-111111111111";
    private static void check(boolean condition,String description){if(!condition)throw new AssertionError(description);checks++;}
    public static void main(String[] args)throws Exception{
        int result=1;
        try{runTests();result=0;}
        catch(Throwable failure){failure.printStackTrace();}
        finally{System.exit(result);}
    }
    private static void runTests()throws Exception{
        File root=Files.createTempDirectory("epub-ui-test").toFile();Context app=new Context(root);
        LibraryItem item=new LibraryItem();item.id=ID;LibraryStore.ITEMS.add(item);
        try(FileOutputStream out=new FileOutputStream(new File(app.getFilesDir(),ID+".pdf"))){out.write(new byte[]{1,2,3});}
        testReplayAndBinding(app);testCancellation(app);testFailure(app);testInterruptResume(app);testRestart(app,root);
        System.out.println("EpubConversionUiTest: "+checks+" checks passed");
    }
    private static void testReplayAndBinding(Context app)throws Exception{
        PdfEpubExporter.entered=new CountDownLatch(1);PdfEpubExporter.release=new CountDownLatch(1);PdfEpubExporter.fail=false;
        EpubConversionCoordinator.Session job=EpubConversionCoordinator.start(app,ID,"书","作者",0,5,EpubConversionCoordinator.Kind.FULL,false);
        check(PdfEpubExporter.entered.await(3,TimeUnit.SECONDS),"worker entered");
        CountDownLatch first=new CountDownLatch(1);EpubConversionCoordinator.Listener old=(s,v)->first.countDown();
        job.attach(old);check(first.await(3,TimeUnit.SECONDS),"initial attach replayed");job.detach(old);
        CountDownLatch finish=new CountDownLatch(1);EpubConversionCoordinator.Listener rotated=(s,v)->{if(v.terminal())finish.countDown();};
        job.attach(rotated);PdfEpubExporter.release.countDown();check(finish.await(3,TimeUnit.SECONDS),"rotated listener completes");
        check(EpubConversionCoordinator.find(job.id())==job,"session retained by ID");
        check(EpubConversionCoordinator.canSave(job.id(),job.snapshot()),"matching full result eligible for SAF");
        check(!EpubConversionCoordinator.canSave("other",job.snapshot()),"other picker ID rejected");
        check(job.snapshot().epub.isFile()&&job.snapshot().epub.length()==3,"private result retained");
        check(new File(job.snapshot().epub.getParentFile(),"source.pdf").isFile(),"immutable source snapshot retained for rebuild");
        PdfEpubExporter.entered=new CountDownLatch(1);PdfEpubExporter.release=new CountDownLatch(1);
        EpubConversionCoordinator.Session other=EpubConversionCoordinator.start(app,ID,"另一书","",0,3,EpubConversionCoordinator.Kind.SAMPLE,false);
        check(PdfEpubExporter.entered.await(3,TimeUnit.SECONDS),"other Activity's job entered");
        boolean busy=false;try{EpubConversionCoordinator.start(app,ID,"书","",0,5,EpubConversionCoordinator.Kind.FULL,false);}
        catch(IllegalStateException expected){busy=true;}
        check(busy,"busy start rejects a second running job");
        check(job.snapshot().epub.isFile()&&EpubConversionCoordinator.canSave(job.id(),job.snapshot()),
                "busy rejection leaves prior successful result saveable");
        other.cancel();awaitState(other,EpubConversionCoordinator.State.CANCELED);
        PdfEpubExporter.release.countDown();
        File privateBook=job.snapshot().epub;
        EpubConversionCoordinator.releaseWhenDone(job.id());
        check(EpubConversionCoordinator.find(job.id())==job,"Activity release does not remove task");
        check(privateBook.exists(),"private EPUB retained after Activity release");
        check(EpubConversionCoordinator.forget(job.id()),"explicit task deletion succeeds");
        check(!privateBook.exists(),"explicit deletion cleans private EPUB");
    }
    private static void testCancellation(Context app)throws Exception{
        PdfEpubExporter.entered=new CountDownLatch(1);PdfEpubExporter.release=new CountDownLatch(1);
        EpubConversionCoordinator.Session job=EpubConversionCoordinator.start(app,ID,"书","",0,3,EpubConversionCoordinator.Kind.SAMPLE,false);
        check(PdfEpubExporter.entered.await(3,TimeUnit.SECONDS),"sample entered");
        check(job.cancel(),"cancel accepted");
        awaitState(job,EpubConversionCoordinator.State.CANCELED);
        check(!EpubConversionCoordinator.canSave(job.id(),job.snapshot()),"sample/canceled result cannot be saved");
        check(new File(new File(app.getFilesDir(),"epub-jobs/"+job.id()),"source.pdf").isFile(),
                "canceled source snapshot retained for restart");
        EpubConversionCoordinator.releaseWhenDone(job.id());
        check(EpubConversionCoordinator.find(job.id())==job,"canceled task record retained");
        PdfEpubExporter.release.countDown();
        check(job.resume(),"canceled task can restart from retained snapshot");
        awaitState(job,EpubConversionCoordinator.State.COMPLETED);
    }
    private static void testFailure(Context app)throws Exception{
        PdfEpubExporter.entered=null;PdfEpubExporter.release=null;PdfEpubExporter.fail=true;
        EpubConversionCoordinator.Session job=EpubConversionCoordinator.start(app,ID,"书","",0,5,EpubConversionCoordinator.Kind.FULL,false);
        awaitState(job,EpubConversionCoordinator.State.FAILED);
        check(job.snapshot().error!=null,"failure retained for UI");
        check(!EpubConversionCoordinator.canSave(job.id(),job.snapshot()),"failed result cannot be saved");
        PdfEpubExporter.fail=false;
    }
    private static void testInterruptResume(Context app)throws Exception{
        PdfEpubExporter.entered=new CountDownLatch(1);PdfEpubExporter.release=new CountDownLatch(1);
        EpubConversionCoordinator.Session job=EpubConversionCoordinator.start(app,ID,"后台恢复","",0,5,
                EpubConversionCoordinator.Kind.FULL,false);
        check(PdfEpubExporter.entered.await(3,TimeUnit.SECONDS),"long OCR entered");
        check(job.interrupt(),"timeout moves running task to interrupted");
        check(job.snapshot().state==EpubConversionCoordinator.State.INTERRUPTED,"interrupted state visible");
        check(job.resume(),"immediate resume accepted and queued");
        PdfEpubExporter.release.countDown();awaitState(job,EpubConversionCoordinator.State.COMPLETED);
        check(job.verifyOutput(),"resumed result valid despite old worker exit");
        PdfEpubExporter.entered=null;PdfEpubExporter.release=null;
    }
    private static void testRestart(Context app,File root)throws Exception{
        PdfEpubExporter.entered=null;PdfEpubExporter.release=null;
        PdfEpubExporter.warningCount=2501;
        EpubConversionCoordinator.Session job=EpubConversionCoordinator.start(app,ID,"重启书","作者",0,5,
                EpubConversionCoordinator.Kind.FULL,false,true,Arrays.asList(new EpubBook.Heading("第一章",0,"标题")));
        awaitState(job,EpubConversionCoordinator.State.COMPLETED);
        String id=job.id();check(job.snapshot().report.chapterPreviews.size()==1,"report has chapter metadata");
        Context fresh=new Context(root);EpubConversionCoordinator.initialize(fresh);
        EpubConversionCoordinator.Session restored=EpubConversionCoordinator.find(id);
        check(restored!=null&&restored!=job,"record restored from disk");
        check(restored.snapshot().state==EpubConversionCoordinator.State.COMPLETED,"completed state survives restart");
        check(restored.snapshot().report.chapterPreviews.size()==1,"report preview survives restart");
        check(restored.snapshot().report.warnings.size()==2501,"all warning rows survive restart");
        PdfEpubExporter.warningCount=1;
        check(restored.overrides().size()==1&&"标题".equals(restored.overrides().get(0).anchorText),"chapter overrides survive restart");
        check(restored.deskew(),"deskew parameter survives restart");
        check(EpubConversionCoordinator.canSave(id,restored.snapshot()),"restored result remains saveable");
        check(restored.verifyOutput(),"result hash verified after restart");
        try(FileOutputStream out=new FileOutputStream(restored.snapshot().epub)){out.write(new byte[]{4,5,6});}
        check(!restored.verifyOutput(),"same-size modified EPUB fails hash verification");
        File source=new File(new File(app.getFilesDir(),"epub-jobs/"+id),"source.pdf");
        try(FileOutputStream out=new FileOutputStream(source)){out.write(new byte[]{8,9});}
        // Missing output forces rebuilding, and corrupted snapshot must fail before export.
        check(restored.snapshot().epub.delete(),"remove result for resume test");
        EpubConversionCoordinator.initialize(new Context(root));
        EpubConversionCoordinator.Session interrupted=EpubConversionCoordinator.find(id);
        check(interrupted.snapshot().state==EpubConversionCoordinator.State.INTERRUPTED,"missing result marked interrupted");
        check(interrupted.resume(),"interrupted task can request resume");
        awaitState(interrupted,EpubConversionCoordinator.State.FAILED);
        check(interrupted.snapshot().error.getMessage().contains("校验"),"corrupt source rejected explicitly");
        check(source.delete(),"remove bad snapshot");
        try(FileOutputStream out=new FileOutputStream(new File(app.getFilesDir(),ID+".pdf"))){out.write(new byte[]{7,8,9});}
        check(interrupted.resume(),"missing snapshot requests recopy");
        awaitState(interrupted,EpubConversionCoordinator.State.FAILED);
        check(interrupted.snapshot().error.getMessage().contains("变化"),"changed original rejected explicitly");
    }
    private static void awaitState(EpubConversionCoordinator.Session job,EpubConversionCoordinator.State wanted)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<deadline){if(job.snapshot().state==wanted)return;Thread.sleep(10);}
        throw new AssertionError("timed out awaiting "+wanted+", was "+job.snapshot().state);
    }
    private static void awaitMissing(File file)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<deadline){if(!file.exists())return;Thread.sleep(10);}
        throw new AssertionError("temporary file still exists: "+file);
    }
}
