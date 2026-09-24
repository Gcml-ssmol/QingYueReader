package cn.qingyue.pdf;

import android.content.Context;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Exercises the actual 1.11 LibraryStore, NoteStore and OCR sidecar adapters. */
public final class CurrentProviderIntegrationTest {
    private static final String PDF="11111111-1111-4111-8111-111111111111";
    private static final String NOTE="22222222-2222-4222-8222-222222222222";
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static LibraryItem item(String id,String title,boolean note){
        LibraryItem item=new LibraryItem();item.id=id;item.name=title;item.originalName=title;
        item.type=note?"note":"pdf";item.format=note?"NOTE":"PDF";
        item.created=item.modified=1;return item;
    }
    private static boolean has(GlobalSearchEngine engine,String query,String channel){
        for(GlobalSearchEngine.Result hit:engine.search(query).results)
            if(channel.equals(hit.channel))return true;
        return false;
    }
    private static int pageOf(GlobalSearchEngine engine,String query,String channel){
        for(GlobalSearchEngine.Result hit:engine.search(query).results)
            if(channel.equals(hit.channel))return hit.page;
        return -1;
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("qingyue-current-search-");
        Context context=new Context(root.toFile());
        try {
            File pdf=new File(context.getFilesDir(),PDF+".pdf");
            Files.write(pdf.toPath(),new byte[]{1,2,3,4});
            LibraryItem pdfItem=item(PDF,"资料标题",false);
            LibraryItem noteItem=item(NOTE,"笔记标题",true);
            LibraryStore.save(context,Arrays.asList(pdfItem,noteItem));
            NoteDocument note=NoteStore.create(context,NOTE,"blank","A4");
            note.add(new NoteDocument.Item(note.newId(),NoteDocument.TEXT,0xff000000,16f,
                    new double[]{10,10,150,80},"","uniquenoteword"));
            note.addPage();
            note.add(new NoteDocument.Item(note.newId(),NoteDocument.TEXT,0xff000000,16f,
                    new double[]{10,10,150,80},"","secondpagenoteword"));
            NoteStore.save(context,NOTE,note);
            noteItem.pages=note.pageCount();
            String before=RecognitionOcrFingerprint.sha256(pdf,()->false);
            pdfItem.hash="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
            LibraryStore.save(context,Arrays.asList(pdfItem,noteItem));
            OcrSidecar.saveVerified(context,PDF,0,pdfItem.hash,before,"uniqueocrword");
            ReaderDocument reader=new ReaderDocument();
            reader.addTextBox(0,"uniquereaderword",0xff000000,.2f,.3f,.04f);
            ReaderStore.save(context.getFilesDir(),PDF,reader);
            GlobalSearchEngine engine=new GlobalSearchEngine(new File(root.toFile(),"index.bin"));
            List<GlobalSearchSource> sources=Arrays.asList(
                    new GlobalSearchProviderSource(context,new RecognitionNoteTextProvider()),
                    new GlobalSearchProviderSource(context,new RecognitionOcrSidecarProvider()),
                    new GlobalSearchProviderSource(context,new RecognitionReaderTextProvider()));
            engine.refresh(sources,()->false,null);
            check(has(engine,"uniquenoteword",GlobalSearchSource.CHANNEL_NOTE_TEXT),"real note TEXT missing");
            check(pageOf(engine,"uniquenoteword",GlobalSearchSource.CHANNEL_NOTE_TEXT)==0,
                    "first note page number wrong when second page is active");
            check(pageOf(engine,"secondpagenoteword",GlobalSearchSource.CHANNEL_NOTE_TEXT)==1,
                    "second note page TEXT missing or page number wrong");
            GlobalSearchLibrarySource.LibraryDocument descriptor=
                    new GlobalSearchLibrarySource.LibraryDocument(noteItem,
                            new File(context.getFilesDir(),"notes/"+NOTE+"/document.qnote"));
            String fingerprintBefore=new RecognitionNoteTextProvider().fingerprint(context,descriptor,()->false);
            note.movePage(1,0);
            NoteStore.save(context,NOTE,note);
            String fingerprintAfter=new RecognitionNoteTextProvider().fingerprint(context,descriptor,()->false);
            check(!fingerprintBefore.equals(fingerprintAfter),"page reorder left fingerprint unchanged");
            engine.refresh(sources,()->false,null);
            check(pageOf(engine,"secondpagenoteword",GlobalSearchSource.CHANNEL_NOTE_TEXT)==0,
                    "reordered second page kept stale search page number");
            check(pageOf(engine,"uniquenoteword",GlobalSearchSource.CHANNEL_NOTE_TEXT)==1,
                    "reordered first page kept stale search page number");
            check(has(engine,"uniqueocrword",GlobalSearchSource.CHANNEL_OCR_TEXT),"real OCR sidecar missing");
            check(has(engine,"uniquereaderword","reader_text"),"real reader text box missing");
            OcrSidecar.save(context,PDF,0,pdfItem.hash,"legacyconvertedword");
            engine.refresh(sources,()->false,null);
            check(!has(engine,"legacyconvertedword",GlobalSearchSource.CHANNEL_OCR_TEXT),
                    "legacy converted OCR without PDF hash was trusted");
            OcrSidecar.saveVerified(context,PDF,0,pdfItem.hash,before,"uniqueocrword");
            engine.refresh(sources,()->false,null);
            long modified=pdf.lastModified();
            Files.write(pdf.toPath(),new byte[]{1,2,3,5});pdf.setLastModified(modified);
            engine.refresh(sources,()->false,null);
            check(!has(engine,"uniqueocrword",GlobalSearchSource.CHANNEL_OCR_TEXT),
                    "changed PDF bytes left stale OCR text");
            Files.delete(pdf.toPath());
            engine.refresh(sources,()->false,null);
            check(!has(engine,"uniquereaderword","reader_text"),"deleted PDF retained reader text");
            pdfItem.trashedAt=2;
            LibraryStore.save(context,Arrays.asList(pdfItem,noteItem));
            engine.refresh(sources,()->false,null);
            check(!has(engine,"uniqueocrword",GlobalSearchSource.CHANNEL_OCR_TEXT),"trashed PDF indexed");
            check(!has(engine,"uniquereaderword","reader_text"),"trashed reader text indexed");
            noteItem.trashedAt=2;
            LibraryStore.save(context,Arrays.asList(pdfItem,noteItem));
            engine.refresh(sources,()->false,null);
            check(!has(engine,"uniquenoteword",GlobalSearchSource.CHANNEL_NOTE_TEXT),"trashed note indexed");
            System.out.println("CurrentProviderIntegrationTest passed");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(root)){
                paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}
                    catch(IOException failure){throw new RuntimeException(failure);}});
            }
        }
    }
}
