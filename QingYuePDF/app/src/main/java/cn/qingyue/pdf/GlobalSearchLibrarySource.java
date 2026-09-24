package cn.qingyue.pdf;

import android.content.Context;
import java.io.*;
import java.util.*;

/** Read-only view of the validated v2 library. */
public final class GlobalSearchLibrarySource implements GlobalSearchSource {
    private final Context context;
    private volatile GlobalSearchPdfSource delegate;
    private volatile Set<String> noteIds = Collections.emptySet();
    public GlobalSearchLibrarySource(Context context) { this.context=context.getApplicationContext(); }
    @Override public String sourceId() { return GlobalSearchPdfSource.SOURCE_ID; }
    @Override public List<Document> scan(Cancellation cancellation) throws IOException {
        Catalog catalog=readCatalog(context,cancellation);
        ArrayList<GlobalSearchPdfSource.Item> pdfs=new ArrayList<>();
        HashSet<String> notes=new HashSet<>();
        for(LibraryDocument item:catalog.documents) {
            if(item.isNote()) notes.add(item.id);
            else pdfs.add(new GlobalSearchPdfSource.Item(item.id,item.name,item.contentFile,item.hash,item.pages));
        }
        GlobalSearchPdfSource pdf=new GlobalSearchPdfSource(context,pdfs);
        ArrayList<Document> out=new ArrayList<>(pdf.scan(cancellation));
        for(LibraryDocument item:catalog.documents) if(item.isNote())
            out.add(new Document(item.id,item.name,"note",item.contentFingerprint(),1,JUMP_NOTE_PAGE));
        delegate=pdf; noteIds=notes; return out;
    }
    @Override public void read(Document document,Sink sink,Cancellation cancellation)throws IOException {
        if(noteIds.contains(document.id))return;
        GlobalSearchPdfSource pdf=delegate;
        if(pdf==null)throw new IOException("尚未扫描资料库");
        pdf.read(document,sink,cancellation);
    }
    public int skippedEntries(){return 0;}
    public static final class Catalog {
        public final List<LibraryDocument> documents;
        Catalog(List<LibraryDocument> documents){this.documents=Collections.unmodifiableList(documents);}
    }
    public static final class LibraryDocument {
        public final String id,name,kind,hash,format;
        public final int pages;
        public final File contentFile;
        LibraryDocument(LibraryItem item,File file){id=item.id;name=item.name;kind=item.type;
            hash=item.hash;format=item.format;pages=item.pages;contentFile=file;}
        public boolean isNote(){return "note".equals(kind);}
        public String contentFingerprint(){return hash+":"+contentFile.length()+":"+
                contentFile.lastModified()+":"+pages;}
    }
    public static Catalog readCatalog(Context context,Cancellation cancellation)throws IOException {
        LibraryStore.State state=LibraryStore.load(context);
        if(!state.allowSave||!state.damaged.isEmpty())
            throw new IOException("资料库索引损坏，搜索索引未更新："+state.warning);
        ArrayList<LibraryDocument> documents=new ArrayList<>();
        File root=context.getFilesDir().getCanonicalFile(); String prefix=root.getPath()+File.separator;
        for(LibraryItem item:state.items){
            if(cancellation!=null&&cancellation.isCancelled())throw new InterruptedIOException("搜索索引已取消");
            if(item.isTrashed())continue;
            File file=item.isNote()?new File(new File(new File(root,"notes"),item.id),"document.qnote")
                    :new File(root,item.id+".pdf");
            file=file.getCanonicalFile();
            if(file.getPath().startsWith(prefix)&&file.isFile())documents.add(new LibraryDocument(item,file));
        }
        return new Catalog(documents);
    }
}
