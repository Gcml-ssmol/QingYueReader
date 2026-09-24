package cn.qingyue.pdf;

import android.content.Context;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.cos.*;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.*;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.*;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real PDFBox and production stores/backup; desktop Android shims are not a device test. */
public final class PdfPageInsertionTest {
    static int checks;
    static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    interface Io{void run()throws Exception;}
    static void rejected(Io action,String message)throws Exception{try{action.run();throw new AssertionError(message);}catch(IOException expected){checks++;}}
    static int moved(int page,int index){return page>=index?page+1:page;}
    static final List<InkPage.Stroke> STROKES=Collections.singletonList(new InkPage.Stroke(0xff123456,.01f,new float[]{.1f,.2f,.3f,.4f}));
    static final class Fixture {
        final File root;final Context context;final String id=UUID.randomUUID().toString(),noteId=UUID.randomUUID().toString();
        final File pdf;final String originalHash,bookmarkId;
        Fixture(boolean linked)throws Exception{
            root=Files.createTempDirectory("qingyue-insert-").toFile();context=new Context(root);pdf=new File(root,id+".pdf");PDFBoxResourceLoader.init(context);
            try(PDDocument doc=new PDDocument()){
                for(int p=0;p<3;p++){PDPage page=new PDPage(new PDRectangle(300+p*10,500+p*20));page.setRotation(p==1?90:0);page.setCropBox(new PDRectangle(5,7,280,470));page.getCOSObject().setInt("OriginalPage",p);doc.addPage(page);}
                PDDocumentOutline outline=new PDDocumentOutline();doc.getDocumentCatalog().setDocumentOutline(outline);
                PDOutlineItem object=new PDOutlineItem();object.setTitle("object");object.setDestination(doc.getPage(2));outline.addLast(object);
                PDOutlineItem numeric=new PDOutlineItem();numeric.setTitle("numeric");PDPageFitDestination destination=new PDPageFitDestination();destination.setPageNumber(1);numeric.setDestination(destination);outline.addLast(numeric);
                PDAnnotationLink link=new PDAnnotationLink();PDPageFitDestination target=new PDPageFitDestination();target.setPage(doc.getPage(2));link.setDestination(target);doc.getPage(0).getAnnotations().add(link);
                PDPageLabels labels=new PDPageLabels(doc);PDPageLabelRange range=new PDPageLabelRange();range.setPrefix("P-");range.setStyle(PDPageLabelRange.STYLE_DECIMAL);range.setStart(7);labels.setLabelItem(0,range);doc.getDocumentCatalog().setPageLabels(labels);
                doc.save(pdf);
            }
            originalHash=PdfPageInsertion.hash(pdf);
            LibraryItem item=new LibraryItem();item.id=id;item.name="Original PDF";item.pages=3;item.page=2;item.hash=originalHash;item.attachment=id+"/source.txt";
            File attachment=new File(LibraryStore.attachmentsDirectory(context),item.attachment);attachment.getParentFile().mkdirs();Files.write(attachment.toPath(),"original source".getBytes("UTF-8"));
            LibraryItem note=new LibraryItem();note.id=noteId;note.name="Linked note";note.type="note";note.format="NOTE";
            NoteDocument document=NoteStore.create(context,noteId,"blank","A4");if(linked){addSource(document,id);NoteStore.save(context,noteId,document);}
            LibraryStore.save(context,Arrays.asList(item,note));
            ReaderDocument reader=new ReaderDocument();bookmarkId=reader.addBookmark(2,"自定义书签").id;
            reader.addMarkup(1,0xff112233,"original quote",Collections.singletonList(new ReaderDocument.Box(.1f,.2f,.3f,.4f)));
            reader.addTextBox(2,"旧页文字",0xff000000,.2f,.3f,.04f);ReaderStore.save(root,id,reader);
            InkStore.save(root,id+"-1",STROKES);InkStore.prepareClear(id+"-1",STROKES);InkStore.save(root,id+"-1",STROKES);
            OcrSidecar.saveVerified(context,id,1,originalHash,originalHash,"保存的 OCR 修订");
            PdfPageInsertion.awaitRecovery(context);
        }
        LibraryItem item(){for(LibraryItem i:LibraryStore.load(context).items)if(i.id.equals(id))return i;throw new AssertionError();}
    }
    static void addSource(NoteDocument note,String id){note.add(new NoteDocument.Item(note.newId(),NoteDocument.TEXT,0xff000000,12,new double[]{10,10,210,70},ReaderSourceLink.page(id,"source",2).toUri(),"引用原第三页"));}
    static void verify(Fixture f,int index)throws Exception{
        try(PDDocument doc=PDDocument.load(f.pdf)){
            check(doc.getNumberOfPages()==4,"real PDF missing page");check(!doc.getPage(index).hasContents(),"inserted page not blank");
            for(int p=0;p<3;p++)check(doc.getPage(moved(p,index)).getCOSObject().getInt("OriginalPage")==p,"old page content/object changed");
            PDPage adjacent=doc.getPage(moved(index==0?0:index-1,index)),blank=doc.getPage(index);
            check(adjacent.getMediaBox().getWidth()==blank.getMediaBox().getWidth()&&adjacent.getCropBox().getLowerLeftX()==blank.getCropBox().getLowerLeftX()&&adjacent.getRotation()==blank.getRotation(),"blank size/crop/rotation mismatch");
            PDOutlineItem first=doc.getDocumentCatalog().getDocumentOutline().getFirstChild();check(doc.getPages().indexOf(first.findDestinationPage(doc))==moved(2,index),"object outline changed target");
            check(((PDPageDestination)first.getNextSibling().getDestination()).getPageNumber()==moved(1,index),"numeric outline did not move");
            PDAnnotationLink link=(PDAnnotationLink)doc.getPage(moved(0,index)).getAnnotations().get(0);check(doc.getPages().indexOf(((PDPageDestination)link.getDestination()).getPage())==moved(2,index),"link changed target");
            String[] labels=doc.getDocumentCatalog().getPageLabels().getLabelsByPageIndices();for(int p=0;p<3;p++)check(labels[moved(p,index)].equals("P-"+(7+p)),"old labels changed");
        }
        LibraryItem item=f.item();check(item.pages==4&&item.page==moved(2,index),"index pages/reading position");check(item.hash.equals(f.originalHash),"import identity changed");
        check(new File(LibraryStore.attachmentsDirectory(f.context),item.attachment).isFile(),"original attachment lost");
        ReaderDocument reader=ReaderStore.load(f.root,f.id);check(reader.bookmarks().get(0).page==moved(2,index)&&reader.bookmarks().get(0).id.equals(f.bookmarkId),"bookmark identity/page");
        check(reader.markups().get(0).page==moved(1,index)&&reader.textBoxes().get(0).page==moved(2,index),"reader annotation page");
        check(InkStore.load(f.root,f.id+"-"+moved(1,index)).size()==1,"ink lost");check(InkStore.loadClearRecovery(f.root,f.id+"-"+moved(1,index)).size()==1,"clear recovery lost");
        check(InkStore.load(f.root,f.id+"-"+index).isEmpty(),"new blank inherited old ink");
        String hash=PdfPageInsertion.hash(f.pdf);check(OcrSidecar.loadForPdf(f.context,f.id,moved(1,index),f.originalHash,hash,false).equals("保存的 OCR 修订"),"OCR binding/page");
        NoteDocument note=NoteStore.load(f.context,f.noteId);check(ReaderSourceLink.parse(note.items().get(0).image).page==moved(2,index),"saved note source page");
        rejected(()->OcrSidecar.saveVerified(f.context,f.id,1,f.originalHash,f.originalHash,"stale"),"stale OCR overwrote migrated text");
    }
    static void positionsAndBackup()throws Exception{
        for(int index:new int[]{0,1,3}){Fixture f=new Fixture(true);PdfPageInsertion.Result result=PdfPageInsertion.insertBlankPage(f.context,f.id,index,3);check(result.documentId.equals(f.id)&&result.insertedPage==index&&result.pageCount==4,"API result");verify(f,index);
            // The inserted page remains a normal writable page after closing and reopening.
            InkStore.save(f.root,f.id+"-"+index,STROKES);check(InkStore.load(f.root,f.id+"-"+index).size()==1,"new page not writable");
            if(index==1){
                File exported=new File(f.root,"inserted-annotated.pdf");try(OutputStream output=new FileOutputStream(exported)){PdfAnnotationExporter.Report report=PdfAnnotationExporter.exportPdf(f.context,f.pdf,f.root,f.id,ReaderStore.load(f.root,f.id),PdfAnnotationExporter.PageRange.all(4),output,()->false,null);check(report.pages==4&&report.inkStrokes==2,"annotated export page/ink count");}
                try(PDDocument doc=PDDocument.load(exported)){check(doc.getNumberOfPages()==4&&doc.getPage(1).hasContents(),"export lost writable inserted page");check(doc.getPage(2).getCOSObject().getInt("OriginalPage")==1&&doc.getPage(2).hasContents(),"export moved old page/annotation incorrectly");}
                File archive=new File(f.root,"inserted.qyb");BackupManager.createSnapshot(f.context,LibraryStore.load(f.context).items,archive,BackupManager.NEVER_CANCEL,BackupManager.NO_PROGRESS);
                File restored=Files.createTempDirectory("qingyue-insert-restored-").toFile();Context context=new Context(restored);BackupManager.restore(context,archive,BackupManager.ConflictPolicy.KEEP_BOTH,false,BackupManager.NEVER_CANCEL,BackupManager.NO_PROGRESS);
                try(PDDocument pdf=PDDocument.load(new File(restored,f.id+".pdf"))){check(pdf.getNumberOfPages()==4,"backup restored stale PDF");}
                check(ReaderStore.load(restored,f.id).bookmarks().get(0).page==3,"backup restored old bookmark");check(InkStore.load(restored,f.id+"-1").size()==1,"backup lost new page ink");
                check(ReaderSourceLink.parse(NoteStore.load(context,f.noteId).items().get(0).image).page==3,"backup source link reverted");
            }
        }
    }
    static class PowerLoss extends Error {}
    static void restart(Fixture f)throws Exception{
        PdfPageInsertion.testFault=null;
        java.lang.reflect.Field states=PdfPageInsertion.class.getDeclaredField("recoveries");states.setAccessible(true);((Map<?,?>)states.get(null)).clear();
        PdfPageInsertion.awaitRecovery(f.context);
    }
    static void failuresAndRestart()throws Exception{
        for(String phase:new String[]{"journal","installed:","files-installed","index-committed","committed"}){
            Fixture f=new Fixture(true);String before=PdfPageInsertion.hash(f.pdf);String revision=LibraryStore.load(f.context).revision;
            InkHistoryRegistry.put(f.id+"-1",new InkPage(STROKES));
            PdfPageInsertion.testFault=at->{if(at.equals(phase)||(phase.equals("installed:")&&at.startsWith(phase)))throw new PowerLoss();};
            try{PdfPageInsertion.insertBlankPage(f.context,f.id,1,3);throw new AssertionError("fault missed");}catch(PowerLoss expected){}
            restart(f);
            check(InkHistoryRegistry.get(f.id+"-1")==null,"old page history survived settled marker/restart");
            if(phase.equals("index-committed")||phase.equals("committed"))verify(f,1);
            else{check(PdfPageInsertion.hash(f.pdf).equals(before),"rollback changed PDF");check(LibraryStore.load(f.context).revision.equals(revision),"rollback changed index");check(ReaderStore.load(f.root,f.id).bookmarks().get(0).page==2,"rollback bookmark shifted");check(ReaderSourceLink.parse(NoteStore.load(f.context,f.noteId).items().get(0).image).page==2,"rollback source shifted");}
            try(DocumentEditLease lease=DocumentEditLease.acquire(f.context,f.id)){check(lease!=null,"lease remained blocked after restart");}
        }
        Fixture f=new Fixture(true);String before=PdfPageInsertion.hash(f.pdf);
        PdfPageInsertion.testFault=phase->{if(phase.equals("files-installed"))throw new IOException("simulated index failure");};
        rejected(()->PdfPageInsertion.insertBlankPage(f.context,f.id,1,3),"commit failure accepted");PdfPageInsertion.testFault=null;check(PdfPageInsertion.hash(f.pdf).equals(before),"IO failure did not roll back");
    }
    static void concurrency()throws Exception{
        Fixture f=new Fixture(true);try(DocumentEditLease ignored=DocumentEditLease.acquire(f.context,f.id)){rejected(()->PdfPageInsertion.insertBlankPage(f.context,f.id,1,3),"active PDF lease ignored");}
        try(DocumentEditLease ignored=DocumentEditLease.acquire(f.context,f.noteId)){rejected(()->PdfPageInsertion.insertBlankPage(f.context,f.id,1,3),"affected note lease ignored");}
        PdfPageInsertion.testFault=phase->{if(phase.equals("prepared")){try{long start=System.nanoTime();rejected(()->DocumentEditLease.acquire(f.context,f.id),"busy PDF admitted");rejected(()->DocumentEditLease.acquire(f.context,f.noteId),"busy source note admitted");check(System.nanoTime()-start<1_000_000_000L,"lease acquire blocked on I/O");}catch(Exception failure){throw new IOException(failure);}}};
        PdfPageInsertion.insertBlankPage(f.context,f.id,1,3);PdfPageInsertion.testFault=null;verify(f,1);
        Fixture pending=new Fixture(false);String before=PdfPageInsertion.hash(pending.pdf);
        PdfPageInsertion.testFault=phase->{if(phase.equals("prepared")){synchronized(NoteStore.class){NoteDocument note=NoteStore.load(pending.context,pending.noteId);addSource(note,pending.id);NoteStore.saveAsync(pending.context,pending.noteId,note,null);}}};
        rejected(()->PdfPageInsertion.insertBlankPage(pending.context,pending.id,1,3),"pending newly linked note not detected");PdfPageInsertion.testFault=null;
        check(PdfPageInsertion.hash(pending.pdf).equals(before),"pending-note rejection changed PDF");check(ReaderSourceLink.parse(NoteStore.load(pending.context,pending.noteId).items().get(0).image).page==2,"pending note overwritten");
    }
    static void committedCleanup()throws Exception{
        Fixture f=new Fixture(true);
        // Hold the active directory open in a child is platform-specific. Rename interception at
        // retirement leaves the same valid journal+COMMITTED state as a filesystem rename failure.
        PdfPageInsertion.testFault=phase->{if(phase.equals("retire"))throw new IOException("cleanup unavailable");};
        PdfPageInsertion.insertBlankPage(f.context,f.id,1,3);
        PdfPageInsertion.testFault=null;try(DocumentEditLease ignored=DocumentEditLease.acquire(f.context,f.id)){check(true,"committed cleanup left editors blocked");}
        ReaderDocument reader=ReaderStore.load(f.root,f.id);reader.addBookmark(1,"提交后新增");ReaderStore.save(f.root,f.id,reader);InkStore.save(f.root,f.id+"-1",STROKES);
        restart(f);check(ReaderStore.load(f.root,f.id).bookmarks().size()==2,"committed cleanup overwrote later bookmark");check(InkStore.load(f.root,f.id+"-1").size()==1,"committed cleanup overwrote later ink");
    }
    static void temporaryAndRecoveryError()throws Exception{
        Fixture f=new Fixture(true);File interrupted=new File(OcrSidecar.directory(f.context,f.id),"page-2.txt.inserting");
        PdfPageInsertion.testFault=phase->{if(phase.equals("journal")){Files.write(interrupted.toPath(),new byte[]{1,2,3});throw new PowerLoss();}};
        try{PdfPageInsertion.insertBlankPage(f.context,f.id,1,3);throw new AssertionError();}catch(PowerLoss expected){}
        // Any Error during startup recovery must leave both the library and editor gate closed.
        java.lang.reflect.Field states=PdfPageInsertion.class.getDeclaredField("recoveries");states.setAccessible(true);((Map<?,?>)states.get(null)).clear();
        Thread.UncaughtExceptionHandler original=Thread.getDefaultUncaughtExceptionHandler();Thread.setDefaultUncaughtExceptionHandler((thread,error)->{});
        try{PdfPageInsertion.testFault=phase->{if(phase.equals("recover-before"))throw new PowerLoss();};rejected(()->PdfPageInsertion.awaitRecovery(f.context),"startup Error reopened library");rejected(()->DocumentEditLease.acquire(f.context,f.id),"startup Error admitted editor");check(!LibraryStore.load(f.context).allowSave,"startup Error allowed index write");}
        finally{Thread.setDefaultUncaughtExceptionHandler(original);}
        restart(f);check(!interrupted.exists(),"interrupted OCR install temporary survived rollback");
        PdfPageInsertion.insertBlankPage(f.context,f.id,1,3);verify(f,1);
    }
    static void notePages()throws Exception{
        NoteDocument note=NoteDocument.create("grid","A4");note.add(new NoteDocument.Item(note.newId(),NoteDocument.LINE,0xff000000,2,new double[]{0,0,20,20},""));note.centerX=77;note.viewportSaved=true;
        note.addPage();note.add(new NoteDocument.Item(note.newId(),NoteDocument.LINE,0xff000000,2,new double[]{0,0,30,30},""));
        note.insertPage(1);check(note.pageCount()==3&&note.currentPage()==1&&note.items().isEmpty(),"note insertion selection/content");check(note.pageItems(0).size()==1&&note.pageItems(2).size()==1,"note insertion lost old content");note.selectPage(0);check(note.centerX==77&&note.viewportSaved,"note insertion lost old viewport");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();NoteDocument.write(bytes,note);NoteDocument restored=NoteDocument.read(new ByteArrayInputStream(bytes.toByteArray()));check(restored.pageCount()==3&&restored.pageItems(2).size()==1,"note insertion wire roundtrip");
    }
    public static void main(String[] args){try{positionsAndBackup();failuresAndRestart();concurrency();committedCleanup();temporaryAndRecoveryError();notePages();System.out.println("PdfPageInsertionTest passed: "+checks+" assertions; real begin/middle/end PDFs, links/labels, reader/ink/clear/OCR/source, backup, restart, leases, pending notes, writable new page, cleanup, interrupted temps, recovery Error gate.");System.exit(0);}catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
