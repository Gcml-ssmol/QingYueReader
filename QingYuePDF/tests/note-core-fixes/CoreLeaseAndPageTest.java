package cn.qingyue.pdf;

import android.content.Context;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.*;

/** Production lease, store and page identity with desktop Context/AtomicFile shims. */
public final class CoreLeaseAndPageTest {
    static int assertions;
    static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    interface Attempt {void run()throws Exception;}
    static void rejected(Attempt attempt,String message)throws Exception{try{attempt.run();throw new AssertionError(message);}catch(IOException expected){check(true,message);}}
    public static void main(String[] args)throws Exception{
        Context context=new Context(Files.createTempDirectory("qingyue-core-lease-").toFile());
        NoteStore.create(context,"note","blank","A5");
        NoteDocument first;
        try(DocumentEditLease editor=DocumentEditLease.acquireExclusive(context,"note")){
            first=NoteStore.load(context,"note");
            rejected(()->DocumentEditLease.acquireExclusive(context,"note"),"second note writer admitted");
            rejected(()->DocumentEditLease.acquire(context,"note"),"shared editor admitted over exclusive writer");
            rejected(()->DocumentEditLease.reserve(context,"note"),"insertion reservation admitted over exclusive writer");
            check(NoteStore.load(context,"note")!=first,"store returns independent edit snapshots");
        }
        try(DocumentEditLease editor=DocumentEditLease.acquireExclusive(context,"note")){
            NoteDocument second=NoteStore.load(context,"note");
            second.add(new NoteDocument.Item(second.newId(),NoteDocument.LINE,0xff000000,2,new double[]{1,1,20,20},""));
            NoteStore.save(context,"note",second);
            check(NoteStore.load(context,"note").items().size()==1,"reopened writer stores its change");
            editor.close();editor.close();
        }
        try(DocumentEditLease pdfA=DocumentEditLease.acquire(context,"pdf");DocumentEditLease pdfB=DocumentEditLease.acquire(context,"pdf")){
            check(pdfA!=pdfB,"PDF admits two ordinary editors");
            rejected(()->DocumentEditLease.acquireExclusive(context,"pdf"),"exclusive acquired over shared PDF editors");
            rejected(()->DocumentEditLease.reserve(context,"pdf"),"reservation acquired over PDF editors");
        }
        try(DocumentEditLease.Reservation reservation=DocumentEditLease.reserve(context,"pdf")){
            rejected(()->DocumentEditLease.acquire(context,"pdf"),"ordinary editor acquired over reservation");
            rejected(()->DocumentEditLease.acquireExclusive(context,"pdf"),"exclusive editor acquired over reservation");
            reservation.close();reservation.close();
        }
        try(DocumentEditLease pdf=DocumentEditLease.acquire(context,"pdf")){check(pdf!=null,"reservation released PDF");}
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            Future<?> held=pool.submit(()->{try(DocumentEditLease ignored=DocumentEditLease.acquireExclusive(context,"note")){entered.countDown();release.await();}catch(Exception e){throw new RuntimeException(e);}});
            check(entered.await(2,TimeUnit.SECONDS),"writer admitted promptly");
            Future<?> denied=pool.submit(()->{try{DocumentEditLease.acquireExclusive(context,"note");throw new AssertionError("concurrent second writer admitted");}catch(IOException expected){return;}});
            denied.get(2,TimeUnit.SECONDS);release.countDown();held.get(2,TimeUnit.SECONDS);
            try(DocumentEditLease after=DocumentEditLease.acquireExclusive(context,"note")){check(after!=null,"writer released after concurrent attempt");}
        }finally{pool.shutdownNow();}
        NoteDocument pages=NoteStore.load(context,"note");String original=pages.pageToken(0);
        pages.addPage();String added=pages.pageToken(1);check(!original.equals(added),"new page has distinct token");
        pages.movePage(0,1);check(pages.pageIndex(original)==1&&pages.pageIndex(added)==0,"tokens follow page move");
        NoteDocument copy=pages.copy();check(copy.pageIndex(original)==1&&copy.pageIndex(added)==0,"snapshot copy keeps page tokens");
        copy.selectPage(1);copy.deletePage();check(copy.pageIndex(original)==-1&&pages.pageIndex(original)==1,"deleted token invalid only in edited copy");
        NoteStore.save(context,"note",pages);NoteDocument reloaded=NoteStore.load(context,"note");
        check(reloaded.pageIndex(original)==-1,"disk reload invalidates transient token");
        check(reloaded.pageCount()==2,"disk reload preserves v3 pages");
        System.out.println("CoreLeaseAndPageTest passed: "+assertions+" assertions; production lease/store/document with desktop Android file shims.");
    }
}
