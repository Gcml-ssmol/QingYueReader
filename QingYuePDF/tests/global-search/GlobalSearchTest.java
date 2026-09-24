package cn.qingyue.pdf;

import android.content.Context;
import java.io.*;
import java.util.*;

/** Desktop core/PDF parser regression. Android Activity lifecycle and rendering require a device. */
public final class GlobalSearchTest {
    private static final class FakeDocument {
        String id, name, kind, stamp;
        int pages;
        final List<GlobalSearchSource.Fragment> fragments =
                new ArrayList<GlobalSearchSource.Fragment>();
        FakeDocument(String id, String name, String kind, String stamp, int pages) {
            this.id=id;this.name=name;this.kind=kind;this.stamp=stamp;this.pages=pages;
        }
        FakeDocument text(int page,String channel,String value,String jump,String anchor) {
            fragments.add(new GlobalSearchSource.Fragment(page,channel,"",value,jump,anchor));
            return this;
        }
    }

    private static final class FakeSource implements GlobalSearchSource {
        final String id;
        final LinkedHashMap<String,FakeDocument> documents = new LinkedHashMap<String,FakeDocument>();
        int reads;
        boolean cancelDuringRead;
        boolean mutateStampDuringRead;
        FakeSource(String id){this.id=id;}
        FakeSource add(FakeDocument document){documents.put(document.id,document);return this;}
        @Override public String sourceId(){return id;}
        @Override public List<Document> scan(Cancellation cancellation)throws IOException{
            ArrayList<Document> values=new ArrayList<Document>();
            for(FakeDocument d:documents.values())values.add(new Document(d.id,d.name,d.kind,d.stamp,
                    d.pages,"note".equals(d.kind)?JUMP_NOTE_PAGE:JUMP_PDF_PAGE));
            return values;
        }
        @Override public void read(Document document,Sink sink,Cancellation cancellation)throws IOException{
            reads++;
            if(cancelDuringRead){((GlobalSearchEngine.CancellationToken)cancellation).cancel();
                throw new InterruptedIOException("cancelled by test");}
            for(GlobalSearchSource.Fragment fragment:documents.get(document.id).fragments){
                if(cancellation.isCancelled())throw new InterruptedIOException();sink.accept(fragment);
            }
            if(mutateStampDuringRead) documents.get(document.id).stamp += "-changed-during-read";
        }
    }

    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static GlobalSearchEngine.Result find(GlobalSearchEngine.SearchResponse response,
            String channel){for(GlobalSearchEngine.Result result:response.results)
        if(channel.equals(result.channel))return result;return null;}
    private static File copy(File source,File target)throws IOException{
        try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(target)){
            byte[] buffer=new byte[65536];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
        }return target;
    }

    public static void main(String[] args)throws Exception{
        File root=new File(System.getProperty("global.search.root"));check(root.isDirectory(),"test root");
        checkChannelSearch(root);
        File index=new File(root,"fake/global-search-v1.bin");
        GlobalSearchEngine engine=new GlobalSearchEngine(index);
        FakeSource pdf=new FakeSource("pdf-text-v1").add(new FakeDocument("pdf-1","微积分讲义","pdf","p1",4)
                .text(1,GlobalSearchSource.CHANNEL_PDF_TEXT,"随机 变量与概率 PDF PDF",GlobalSearchSource.JUMP_PDF_PAGE,""));
        FakeSource note=new FakeSource("note-text-v2").add(new FakeDocument("note-1","课堂笔记","note","n1",3)
                .text(2,GlobalSearchSource.CHANNEL_NOTE_TEXT,"复习随机变量重点",GlobalSearchSource.JUMP_NOTE_PAGE,"box-7"));
        FakeSource ocr=new FakeSource("local-ocr-v1").add(new FakeDocument("pdf-1","微积分讲义","pdf","o1",4)
                .text(3,GlobalSearchSource.CHANNEL_OCR_TEXT,"扫描页包含贝叶斯公式",GlobalSearchSource.JUMP_PDF_PAGE,"region-2"));
        FakeSource summary=new FakeSource("study-summary-v1").add(new FakeDocument("note-1","课堂笔记","note","s1",3)
                .text(-1,GlobalSearchSource.CHANNEL_STUDY_SUMMARY,"复习计划：概率是本周重点",GlobalSearchSource.JUMP_NOTE_PAGE,"summary"));
        List<GlobalSearchSource> sources=Arrays.<GlobalSearchSource>asList(pdf,note,ocr,summary);
        GlobalSearchEngine.RefreshReport first=engine.refresh(sources,new GlobalSearchEngine.CancellationToken(),null);
        check(first.documents==4&&first.fragments==4&&first.rebuiltDocuments==4,"initial cross-source build");

        GlobalSearchEngine.SearchResponse chinese=engine.search("随机变量");
        GlobalSearchEngine.Result pdfHit=find(chinese,GlobalSearchSource.CHANNEL_PDF_TEXT);
        GlobalSearchEngine.Result noteHit=find(chinese,GlobalSearchSource.CHANNEL_NOTE_TEXT);
        check(pdfHit!=null&&pdfHit.page==1&&pdfHit.documentId.equals("pdf-1"),"PDF page/navigation");
        check(noteHit!=null&&noteHit.page==2&&noteHit.anchor.equals("box-7"),"note page/anchor");
        GlobalSearchEngine.Result ocrHit=find(engine.search("贝叶斯"),GlobalSearchSource.CHANNEL_OCR_TEXT);
        check(ocrHit!=null&&ocrHit.page==3&&ocrHit.snippet.contains("贝叶斯"),"OCR channel");
        GlobalSearchEngine.Result summaryHit=find(engine.search("复习 重点"),GlobalSearchSource.CHANNEL_STUDY_SUMMARY);
        check(summaryHit!=null&&summaryHit.page==-1,"AND terms and document-level summary");
        GlobalSearchEngine.SearchResponse title=engine.search("微积分讲义");
        int titleHits=0;for(GlobalSearchEngine.Result result:title.results)
            if("document_title".equals(result.channel))titleHits++;
        check(titleHits==1,"title deduplicated across PDF and OCR sources");

        GlobalSearchEngine reopened=new GlobalSearchEngine(index);
        check(find(reopened.search("贝叶斯"),GlobalSearchSource.CHANNEL_OCR_TEXT)!=null,"persistent reload");
        int reads=pdf.reads+note.reads+ocr.reads+summary.reads;
        GlobalSearchEngine.RefreshReport unchanged=reopened.refresh(sources,
                new GlobalSearchEngine.CancellationToken(),null);
        check(unchanged.reusedDocuments==4&&unchanged.rebuiltDocuments==0,"unchanged reuse");
        check(reads==pdf.reads+note.reads+ocr.reads+summary.reads,"reuse avoids source read");

        FakeDocument noteDocument=note.documents.get("note-1");noteDocument.stamp="n2";
        noteDocument.fragments.clear();noteDocument.text(0,GlobalSearchSource.CHANNEL_NOTE_TEXT,
                "更新后的拓扑学文本",GlobalSearchSource.JUMP_NOTE_PAGE,"box-9");
        GlobalSearchEngine.RefreshReport changed=reopened.refresh(sources,
                new GlobalSearchEngine.CancellationToken(),null);
        check(changed.rebuiltDocuments==1&&changed.reusedDocuments==3,"single-source invalidation");
        check(reopened.search("复习随机变量重点").results.isEmpty(),"stale changed text removed");
        check(find(reopened.search("拓扑学"),GlobalSearchSource.CHANNEL_NOTE_TEXT).page==0,"changed text indexed");
        ocr.documents.clear();
        reopened.refresh(sources,new GlobalSearchEngine.CancellationToken(),null);
        check(reopened.search("贝叶斯").results.isEmpty(),"deleted provider document removed");

        // Cancellation after a changed source starts must preserve the last complete snapshot.
        pdf.documents.get("pdf-1").stamp="p2";pdf.documents.get("pdf-1").fragments.clear();
        pdf.documents.get("pdf-1").text(0,GlobalSearchSource.CHANNEL_PDF_TEXT,"should-not-commit",
                GlobalSearchSource.JUMP_PDF_PAGE,"");pdf.cancelDuringRead=true;
        boolean cancelled=false;
        try{reopened.refresh(sources,new GlobalSearchEngine.CancellationToken(),null);}
        catch(InterruptedIOException expected){cancelled=true;}
        check(cancelled,"refresh cancellation surfaced");
        check(reopened.search("概率 PDF").results.size()>0&&!new GlobalSearchEngine(index)
                .search("概率 PDF").results.isEmpty(),"cancel preserves memory and disk snapshot");
        check(reopened.search("should-not-commit").results.isEmpty(),"cancelled fragment absent");
        pdf.cancelDuringRead=false;

        // A file/provider stamp changing between scan and commit also keeps the old snapshot.
        pdf.documents.get("pdf-1").stamp="p3";pdf.mutateStampDuringRead=true;
        boolean raced=false;
        try{reopened.refresh(sources,new GlobalSearchEngine.CancellationToken(),null);}
        catch(IOException expected){raced=expected.getMessage().contains("发生变化");}
        check(raced&&reopened.search("should-not-commit").results.isEmpty(),"mid-index change rejected");
        pdf.mutateStampDuringRead=false;

        // A source cannot force an unbounded page into the cache; other records still commit.
        FakeSource huge=new FakeSource("bounded-v1").add(new FakeDocument("huge","巨大文本","note","h1",1)
                .text(0,GlobalSearchSource.CHANNEL_NOTE_TEXT,repeat('甲',4*1024*1024+1),
                        GlobalSearchSource.JUMP_NOTE_PAGE,""));
        GlobalSearchEngine.RefreshReport bounded=reopened.refresh(Arrays.<GlobalSearchSource>asList(note,huge),
                new GlobalSearchEngine.CancellationToken(),null);
        check(bounded.failures.size()==1
                &&find(reopened.search("巨大文本"),"document_title")!=null,
                "bounded provider retains title");
        check(reopened.search("甲甲甲甲").results.isEmpty(),"bounded provider body omitted");
        check(find(reopened.search("拓扑学"),GlobalSearchSource.CHANNEL_NOTE_TEXT)!=null,"valid provider retained");

        // Separate Activity instances can refresh the same cache path without mixing temp files.
        final GlobalSearchEngine concurrentA=new GlobalSearchEngine(new File(root,"shared/index.bin"));
        final GlobalSearchEngine concurrentB=new GlobalSearchEngine(new File(root,"shared/index.bin"));
        final Throwable[] concurrentErrors=new Throwable[2];
        Runnable writeA=()->{try{concurrentA.refresh(Collections.singletonList(pdf),
                new GlobalSearchEngine.CancellationToken(),null);}catch(Throwable failure){
                concurrentErrors[0]=failure;}};
        Runnable writeB=()->{try{concurrentB.refresh(Collections.singletonList(note),
                new GlobalSearchEngine.CancellationToken(),null);}catch(Throwable failure){
                concurrentErrors[1]=failure;}};
        Thread firstWriter=new Thread(writeA),secondWriter=new Thread(writeB);
        firstWriter.start();secondWriter.start();firstWriter.join();secondWriter.join();
        check(concurrentErrors[0]==null&&concurrentErrors[1]==null,
                "concurrent cache writers complete");
        GlobalSearchEngine sharedReload=new GlobalSearchEngine(new File(root,"shared/index.bin"));
        check(sharedReload.loadWarning()==null&&sharedReload.documentCount()==1,
                "concurrent cache reload is complete");

        // Corrupt cache bytes are ignored without touching any source document.
        try(FileOutputStream out=new FileOutputStream(index)){out.write(new byte[]{1,2,3,4,5});}
        GlobalSearchEngine corrupt=new GlobalSearchEngine(index);
        check(corrupt.documentCount()==0&&corrupt.loadWarning()!=null,"corrupt cache rejected safely");

        // Exercise the real PDFBox source, Chinese CMaps, persistence, and file stamp invalidation.
        File pdfCopy=copy(new File(System.getProperty("global.search.fixtures"),"sample.pdf"),
                new File(root,"sample-copy.pdf"));
        GlobalSearchPdfSource.Item item=new GlobalSearchPdfSource.Item("actual-pdf","真实样例",pdfCopy,
                "fixture-hash",4);
        File pdfIndex=new File(root,"pdf/global-search-v1.bin");
        GlobalSearchEngine pdfEngine=new GlobalSearchEngine(pdfIndex);
        GlobalSearchPdfSource actual=new GlobalSearchPdfSource(new Context(),Collections.singletonList(item));
        GlobalSearchEngine.RefreshReport actualFirst=pdfEngine.refresh(Collections.singletonList(actual),
                new GlobalSearchEngine.CancellationToken(),null);
        check(actualFirst.rebuiltDocuments==1,"actual PDF indexed");
        GlobalSearchEngine.Result actualChinese=find(pdfEngine.search("随机变量"),GlobalSearchSource.CHANNEL_PDF_TEXT);
        check(actualChinese!=null&&actualChinese.page==1,"actual PDF Chinese page text");
        check(find(pdfEngine.search("PDF"),GlobalSearchSource.CHANNEL_PDF_TEXT)!=null,"actual PDF English text");
        GlobalSearchEngine.RefreshReport actualReuse=pdfEngine.refresh(Collections.singletonList(
                new GlobalSearchPdfSource(new Context(),Collections.singletonList(item))),
                new GlobalSearchEngine.CancellationToken(),null);
        check(actualReuse.reusedDocuments==1,"actual unchanged file reused");
        long oldModified=pdfCopy.lastModified();
        check(pdfCopy.setLastModified(oldModified+4000),"fixture mtime update");
        GlobalSearchPdfSource.Item changedItem=new GlobalSearchPdfSource.Item("actual-pdf","真实样例",pdfCopy,
                "fixture-hash",4);
        GlobalSearchEngine.RefreshReport actualChanged=pdfEngine.refresh(Collections.singletonList(
                new GlobalSearchPdfSource(new Context(),Collections.singletonList(changedItem))),
                new GlobalSearchEngine.CancellationToken(),null);
        check(actualChanged.reusedDocuments==1,"mtime alone does not invalidate unchanged PDF bytes");

        System.out.println("GlobalSearchTest passed: channel filtering before result limits, cross-source PDF/note/OCR/summary results, 0-based jumps, persistence, incremental reuse, changed/deleted invalidation, cancellation atomicity, size bounds, corrupt-cache rejection, and actual PDFBox English/Chinese extraction (desktop Android shims; not device UI validation).");
    }

    private static void checkChannelSearch(File root)throws Exception{
        File index=new File(root,"channel-filter/global-search-v1.bin");
        GlobalSearchEngine engine=new GlobalSearchEngine(index);
        FakeSource source=new FakeSource("channel-limit-regression");
        // High-ranked title hits fill the entire unfiltered result limit.
        for(int i=0;i<230;i++)source.add(new FakeDocument("title-"+i,"needle title "+i,
                "pdf","t1",1));
        FakeDocument pdf=new FakeDocument("body-pdf","正文资料","pdf","p1",230);
        for(int i=0;i<230;i++)pdf.text(i,GlobalSearchSource.CHANNEL_PDF_TEXT,"needle body",
                GlobalSearchSource.JUMP_PDF_PAGE,"pdf-"+i);
        source.add(pdf);
        FakeDocument note=new FakeDocument("body-note","笔记资料","note","n1",3);
        for(int i=0;i<3;i++)note.text(i,GlobalSearchSource.CHANNEL_NOTE_TEXT,"needle note",
                GlobalSearchSource.JUMP_NOTE_PAGE,"note-"+i);
        source.add(note);
        FakeDocument ocr=new FakeDocument("body-ocr","识别资料","pdf","o1",2);
        for(int i=0;i<2;i++)ocr.text(i,GlobalSearchSource.CHANNEL_OCR_TEXT,"needle ocr",
                GlobalSearchSource.JUMP_PDF_PAGE,"ocr-"+i);
        source.add(ocr);
        engine.refresh(Collections.singletonList(source),new GlobalSearchEngine.CancellationToken(),null);

        GlobalSearchEngine.SearchResponse all=engine.search("needle");
        check(all.results.size()==200&&all.limited,"legacy search retains default result cap");
        for(GlobalSearchEngine.Result result:all.results)
            check("document_title".equals(result.channel),"fixture fills cap with higher-ranked titles");
        check(engine.search("needle",7).results.size()==7,"legacy explicit result limit preserved");
        check(engine.search("needle",(String)null).results.size()==200,"null channel searches all");
        check(engine.search("needle","").results.size()==200,"empty channel searches all");

        GlobalSearchEngine.SearchResponse pdfHits=engine.search("needle",GlobalSearchSource.CHANNEL_PDF_TEXT);
        check(pdfHits.results.size()==200&&pdfHits.limited,"PDF cap counts only PDF hits");
        for(int i=0;i<pdfHits.results.size();i++){
            GlobalSearchEngine.Result hit=pdfHits.results.get(i);
            check(GlobalSearchSource.CHANNEL_PDF_TEXT.equals(hit.channel)&&hit.page==i,
                    "PDF channel survives title pressure and preserves ranking/page");
        }
        GlobalSearchEngine.SearchResponse notes=engine.search("needle",GlobalSearchSource.CHANNEL_NOTE_TEXT);
        check(notes.results.size()==3&&!notes.limited,"note hits are not displaced or falsely limited");
        for(int i=0;i<notes.results.size();i++){
            GlobalSearchEngine.Result hit=notes.results.get(i);
            check(GlobalSearchSource.CHANNEL_NOTE_TEXT.equals(hit.channel)&&hit.page==i
                    &&hit.anchor.equals("note-"+i)&&GlobalSearchSource.JUMP_NOTE_PAGE.equals(hit.jumpType),
                    "filtered note jump metadata retained");
        }
        GlobalSearchEngine.SearchResponse ocrHits=engine.search("needle",GlobalSearchSource.CHANNEL_OCR_TEXT);
        check(ocrHits.results.size()==2&&!ocrHits.limited,"OCR hits are not displaced or falsely limited");
        check(ocrHits.results.get(1).page==1&&ocrHits.results.get(1).anchor.equals("ocr-1"),
                "filtered OCR page and anchor retained");
        GlobalSearchEngine.SearchResponse oneNote=engine.search("needle",GlobalSearchSource.CHANNEL_NOTE_TEXT,1);
        check(oneNote.results.size()==1&&oneNote.limited,"requested limit applies inside selected channel");
        check(engine.search("needle","missing-channel").results.isEmpty(),"unknown channel cannot leak title hits");
        GlobalSearchEngine.SearchResponse reloaded=new GlobalSearchEngine(index)
                .search("needle",GlobalSearchSource.CHANNEL_NOTE_TEXT);
        check(reloaded.results.size()==3&&!reloaded.limited,"channel filtering also works on persisted index");
    }

    private static String repeat(char value,int count){char[] chars=new char[count];Arrays.fill(chars,value);return new String(chars);}
}
