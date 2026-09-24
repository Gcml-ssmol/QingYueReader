package cn.qingyue.pdf;

import android.content.Context;
import android.util.AtomicFile;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.*;

/** In-place PDF edit with durable before-images and an index-last commit point. */
public final class PdfPageInsertion {
    public static final class Result {
        public final String documentId;public final int insertedPage,pageCount;
        Result(String id,int page,int count){documentId=id;insertedPage=page;pageCount=count;}
    }
    private static final Object RECOVERY_LOCK=new Object();
    private static final Map<String,Recovery> recoveries=new HashMap<>();
    private static final class Recovery {boolean done;IOException error;}
    interface Fault {void at(String phase)throws IOException;}
    static Fault testFault;
    private static void fault(String phase)throws IOException {if(testFault!=null)testFault.at(phase);}
    private PdfPageInsertion(){}

    /** Application entry point: only starts a worker; all recovery disk I/O is off the UI thread. */
    public static void startRecovery(Context context){
        final Context app=context.getApplicationContext();final String key=app.getFilesDir().getAbsolutePath();
        synchronized(RECOVERY_LOCK){
            if(recoveries.containsKey(key))return;Recovery recovery=new Recovery();recoveries.put(key,recovery);
            block("资料正在恢复，请稍后重试",null);
            new Thread(()->{IOException error=new IOException("插页恢复未正常完成，原件已保留，请重启后重试");boolean success=false;try{
                LibraryStore.recoveryGate("资料正在恢复，请稍后重试",Thread.currentThread());
                synchronized(BackupManager.TRANSACTION_LOCK){locked(()->{recoverLocked(app);return null;});}
                success=true;error=null;
            }catch(IOException|RuntimeException failure){error=new IOException("插页恢复未完成，原件已保留，请勿清除应用数据："+failure.getMessage(),failure);}
            finally{
                block(success?"":error.getMessage(),null);
                synchronized(RECOVERY_LOCK){recovery.error=error;recovery.done=true;RECOVERY_LOCK.notifyAll();}
            }},"qingyue-page-recovery").start();
        }
    }
    /** Call from startup/insertion workers, never the main thread. */
    public static void awaitRecovery(Context context)throws IOException {
        startRecovery(context);synchronized(RECOVERY_LOCK){Recovery state=recoveries.get(context.getFilesDir().getAbsolutePath());
            while(!state.done)try{RECOVERY_LOCK.wait();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("等待资料恢复被中断",interrupted);}
            if(state.error!=null)throw state.error;
        }
    }
    private static void block(String warning,Thread owner){DocumentEditLease.unavailable(warning);LibraryStore.recoveryGate(warning,owner);}
    private interface Work<T>{T run()throws IOException;}
    private static <T>T locked(Work<T> work)throws IOException {
        synchronized(TrashManager.class){synchronized(NoteStore.class){synchronized(ReaderStore.class){synchronized(InkStore.class){synchronized(LibraryStore.transactionLock()){return work.run();}}}}}
    }

    public static Result insertBlankPage(Context context,String documentId,int index,int expectedPageCount)throws IOException {
        if(documentId==null||!documentId.matches("[0-9a-fA-F-]{36}")||index<0||index>expectedPageCount)throw new IOException("插页位置无效");
        Context app=context.getApplicationContext();awaitRecovery(app);
        try(DocumentEditLease.Reservation reservation=DocumentEditLease.reserve(app,documentId)){
            synchronized(BackupManager.TRANSACTION_LOCK){
                // Cleanup after a previously committed edit may have been interrupted.
                locked(()->{recoverLocked(app);return null;});
                final Plan plan=locked(()->snapshot(app,documentId,index,expectedPageCount,reservation));
                boolean settled=false;
                try {
                    PDFBoxResourceLoader.init(app);
                    PdfBlankPageEditor.insert(plan.oldFile(documentId+".pdf"),plan.newFile(documentId+".pdf"),app.getCacheDir(),index,expectedPageCount);
                    String newHash=hash(plan.newFile(documentId+".pdf"));
                    // Keep imported identity hash and attachment; actual-PDF hashes bind OCR to this revision.
                    migrateOcr(plan,documentId,index,expectedPageCount,plan.pdfHash,newHash);
                    for(LibraryItem item:plan.nextItems)if(item.id.equals(documentId)){item.pages=expectedPageCount+1;item.page=item.page>=index?item.page+1:item.page;item.size=plan.newFile(documentId+".pdf").length();item.modified=System.currentTimeMillis();}
                    plan.newRevision=new LibraryStore.State(plan.nextItems,plan.folders,new ArrayList<>(),true,false,"").revision;
                    plan.finishEntries();fault("prepared");
                    locked(()->{commit(app,plan);return null;});settled=true;
                    return new Result(documentId,index,expectedPageCount+1);
                }catch(IOException|RuntimeException failure){
                    try{locked(()->{recoverLocked(app);return null;});settled=true;}catch(IOException recovery){failure.addSuppressed(recovery);block("插页恢复未完成，原件已保留，请重启后重试",null);}
                    throw failure;
                }finally{
                    if(!settled&&new File(plan.directory,"journal").isFile())block("插页恢复未完成，原件已保留，请重启后重试",null);
                    if(!new File(plan.directory,"journal").exists())discardPrepared(plan.directory);
                }
            }
        }
    }

    private static final class Entry {
        final String path,oldHash,newHash;
        Entry(String path,String oldHash,String newHash){this.path=path;this.oldHash=oldHash;this.newHash=newHash;}
    }
    private static final class Plan {
        final Context context;final File directory;final TreeMap<String,String> original=new TreeMap<>();
        final TreeMap<String,String> noteChecks=new TreeMap<>();final ArrayList<Entry> entries=new ArrayList<>();
        ArrayList<LibraryItem> nextItems;ArrayList<LibraryFolder> folders;String oldRevision,newRevision,pdfHash;
        Plan(Context c)throws IOException{context=c;directory=active(c);if(directory.exists())throw new IOException("上次插页恢复尚未完成");mkdir(directory);mkdir(new File(directory,"old"));mkdir(new File(directory,"new"));}
        File oldFile(String path)throws IOException{return safe(new File(directory,"old"),path);}
        File newFile(String path)throws IOException{File file=safe(new File(directory,"new"),path);mkdir(file.getParentFile());return file;}
        void capture(String path)throws IOException {
            if(original.containsKey(path))return;File source=safe(context.getFilesDir(),path);
            if(source.exists()&&!source.isFile())throw new IOException("插页文件不是普通文件："+path);
            String digest=source.isFile()?hash(source):"";original.put(path,digest);if(!digest.isEmpty()){copy(source,oldFile(path));if(!digest.equals(hash(oldFile(path))))throw new IOException("插页原件校验失败");}
        }
        void keep(String source,String target)throws IOException{capture(source);capture(target);copy(oldFile(source),newFile(target));}
        void finishEntries()throws IOException{for(Map.Entry<String,String> e:original.entrySet()){File next=newFile(e.getKey());entries.add(new Entry(e.getKey(),e.getValue(),next.isFile()?hash(next):""));}}
    }
    private static Plan snapshot(Context c,String id,int index,int count,DocumentEditLease.Reservation lease)throws IOException {
        LibraryStore.State state=LibraryStore.load(c);if(!state.allowSave||!state.damaged.isEmpty())throw new IOException("资料库索引需要修复，已取消插页");
        LibraryItem target=null;for(LibraryItem item:state.items)if(id.equals(item.id))target=item;
        if(target==null||target.isTrashed()||target.isNote()||target.pages!=count||TrashManager.isDeletionStaged(id))throw new IOException("文档已变化，请重新打开后插页");
        File files=c.getFilesDir();ReaderStore.flushPending(files,id);InkStore.flushDocument(files,id);
        Plan plan=new Plan(c);plan.oldRevision=state.revision;plan.nextItems=new ArrayList<>();plan.folders=new ArrayList<>(state.folders);
        try{
            for(LibraryItem item:state.items)plan.nextItems.add(item.copy());
            plan.capture(id+".pdf");plan.pdfHash=plan.original.get(id+".pdf");if(plan.pdfHash.isEmpty())throw new IOException("PDF 原件不存在");
            // Parse before capturing so AtomicFile recovery is included in the before-image.
            ReaderDocument reader=ReaderStore.load(files,id).insertingPage(index,count);
            for(String suffix:Arrays.asList(".reader",".reader.bak",".reader.new"))plan.capture(id+suffix);
            write(plan.newFile(id+".reader"),reader::write);
            Pattern ink=Pattern.compile(Pattern.quote(id)+"-([0-9]+)(\\.ink(?:\\.clear)?(?:\\.bak|\\.new)?)");
            File[] fileList=files.listFiles();if(fileList==null)throw new IOException("无法读取批注目录");
            for(File file:fileList){Matcher match=ink.matcher(file.getName());if(match.matches()){int page=page(match.group(1),count);plan.keep(file.getName(),id+"-"+(page>=index?page+1:page)+match.group(2));}}
            File ocr=OcrSidecar.directory(c,id);File[] ocrFiles=ocr.listFiles();
            if(ocr.exists()&&ocrFiles==null)throw new IOException("无法读取 OCR 目录");
            if(ocrFiles!=null)for(File f:ocrFiles){if(!f.getName().matches("page-[0-9]+\\.txt(?:\\.bak|\\.new)?"))throw new IOException("OCR 目录含未知文件，已取消插页");plan.capture("recognition/"+id+"/"+f.getName());}
            ArrayList<String> affected=new ArrayList<>();Map<String,NoteDocument> migrated=new LinkedHashMap<>();
            for(LibraryItem item:state.items)if(item.isNote()){
                NoteStore.flushPending(c,item.id);NoteDocument note=NoteStore.load(c,item.id);
                String path="notes/"+item.id+"/document.qnote";plan.noteChecks.put(path,hash(safe(files,path)));
                if(shiftSources(note,id,index,count)){affected.add(item.id);migrated.put(item.id,note);}
            }
            lease.add(affected);
            for(Map.Entry<String,NoteDocument> entry:migrated.entrySet()){
                String path="notes/"+entry.getKey()+"/document.qnote";for(String suffix:Arrays.asList("",".bak",".new"))plan.capture(path+suffix);
                write(plan.newFile(path),out->NoteDocument.write(out,entry.getValue()));
            }
            return plan;
        }catch(IOException|RuntimeException failure){discardPrepared(plan.directory);throw failure;}
    }
    private static boolean shiftSources(NoteDocument note,String id,int index,int count)throws IOException {
        boolean changed=false;int selected=note.currentPage();
        try{for(int p=0;p<note.pageCount();p++){
            note.selectPage(p);for(NoteDocument.Item item:new ArrayList<>(note.items()))if(item.type==NoteDocument.TEXT&&!item.image.isEmpty()){
                ReaderSourceLink link;try{link=ReaderSourceLink.parse(item.image);}catch(IllegalArgumentException invalid){throw new IOException("笔记来源链接损坏，已取消插页",invalid);}
                if(id.equals(link.documentId)){
                    if(link.page>=count)throw new IOException("笔记来源页码超出原文档，已取消插页");
                    if(link.page>=index){ReaderSourceLink next=new ReaderSourceLink(link.documentId,link.documentName,link.page+1,link.x,link.y,link.quote);note.replace(new NoteDocument.Item(item.id,item.type,item.color,item.width,item.points,next.toUri(),item.text));changed=true;}
                }
            }
        }}finally{note.selectPage(selected);}return changed;
    }
    private static void migrateOcr(Plan plan,String id,int index,int count,String oldHash,String newHash)throws IOException {
        // Preserve legacy-v1 identity semantics; verified v2 remains verified only for the new actual PDF.
        for(String path:new ArrayList<>(plan.original.keySet()))if(path.startsWith("recognition/"+id+"/")&&!plan.original.get(path).isEmpty()){
            String name=path.substring(path.lastIndexOf('/')+1);Matcher m=Pattern.compile("page-([0-9]+)(\\.txt(?:\\.bak|\\.new)?)").matcher(name);if(!m.matches())throw new IOException("OCR 文件名无效");int p=page(m.group(1),count);
            String content=readText(plan.oldFile(path),8_000_000);String[] lines=content.split("\n",4);
            if(lines.length>=4&&lines[0].equals("QY-OCR-2")){
                if(!lines[2].equalsIgnoreCase(oldHash))throw new IOException("存在来源已变化的 OCR 资料，请先核对后再插页");
                content=lines[0]+"\n"+lines[1]+"\n"+newHash+"\n"+lines[3];
            }else if(lines.length>=3&&lines[0].equals("QY-OCR-1")){
                if(lines[1].equalsIgnoreCase(oldHash))content="QY-OCR-1\n"+newHash+"\n"+content.substring(content.indexOf('\n',content.indexOf('\n')+1)+1);
                else if(!lines[1].matches("[0-9a-fA-F]{64}"))throw new IOException("OCR 来源标记损坏");
            }else throw new IOException("OCR 资料格式无法识别，已取消插页");
            String destination="recognition/"+id+"/page-"+(p>=index?p+1:p)+m.group(2);plan.capture(destination);final byte[] bytes=content.getBytes(StandardCharsets.UTF_8);write(plan.newFile(destination),out->out.write(bytes));
        }
    }
    private static int page(String value,int count)throws IOException{try{int page=Integer.parseInt(value);if(page>=0&&page<count)return page;}catch(NumberFormatException ignored){}throw new IOException("侧车页码超出原文档，已取消插页");}

    private static void commit(Context c,Plan plan)throws IOException {
        if(!plan.oldRevision.equals(LibraryStore.load(c).revision))throw new IOException("资料库已更新，请重新插页");
        for(LibraryItem item:plan.nextItems)if(item.isNote())NoteStore.flushPending(c,item.id);
        for(Map.Entry<String,String> note:plan.noteChecks.entrySet())if(!note.getValue().equals(hash(safe(c.getFilesDir(),note.getKey()))))throw new IOException("笔记正在更新，请保存后重新插页");
        for(Entry entry:plan.entries)if(!entry.oldHash.equals(optionalHash(safe(c.getFilesDir(),entry.path))))throw new IOException("文档资料已更新，请重新插页");
        writeJournal(plan);fault("journal");
        for(Entry entry:plan.entries){install(c,plan.directory,entry,true);fault("installed:"+entry.path);}
        verifyInstalled(c,plan.entries,true);fault("files-installed");
        LibraryStore.saveIfRevision(c,plan.oldRevision,plan.nextItems,plan.folders);
        LibraryStore.State actual=LibraryStore.load(c);
        if(!actual.allowSave||!plan.newRevision.equals(actual.revision))throw new IOException("插页索引提交验证失败");
        fault("index-committed");invalidate(c,plan.entries);markCommitted(plan.directory);fault("committed");
        retire(plan.directory);
    }
    private static void invalidate(Context c,List<Entry> entries)throws IOException {
        Set<String> pdfs=new HashSet<>(),notes=new HashSet<>();
        for(Entry e:entries){if(e.path.matches("[0-9a-fA-F-]{36}\\.pdf"))pdfs.add(e.path.substring(0,36));if(e.path.matches("notes/[A-Za-z0-9_-]{1,80}/document\\.qnote"))notes.add(e.path.split("/")[1]);}
        for(String id:pdfs){ReaderStore.invalidate(c.getFilesDir(),id);ReaderStore.revive(c.getFilesDir(),id);InkStore.invalidateDocument(id);InkStore.reviveDocument(id);}
        for(String id:notes){NoteStore.invalidate(c,id);NoteStore.revive(c,id);}
    }

    private static File active(Context c){return new File(new File(c.getFilesDir(),"page-insertion"),"active");}
    private static void writeJournal(Plan plan)throws IOException {
        write(new File(plan.directory,"journal.tmp"),stream->{DataOutputStream out=new DataOutputStream(stream);out.writeInt(0x51504931);out.writeUTF(plan.oldRevision);out.writeUTF(plan.newRevision);out.writeInt(plan.entries.size());for(Entry e:plan.entries){out.writeUTF(e.path);out.writeUTF(e.oldHash);out.writeUTF(e.newHash);}out.flush();});
        if(!new File(plan.directory,"journal.tmp").renameTo(new File(plan.directory,"journal")))throw new IOException("无法提交插页恢复日志");
    }
    private static final class Journal {String oldRevision,newRevision;final ArrayList<Entry> entries=new ArrayList<>();}
    private static Journal readJournal(File directory)throws IOException {
        File journal=new File(directory,"journal");if(journal.length()>32L*1024*1024)throw new IOException("插页日志过大");Journal result=new Journal();Set<String> paths=new HashSet<>();
        try(DataInputStream in=new DataInputStream(new FileInputStream(journal))){
            if(in.readInt()!=0x51504931)throw new IOException("插页日志格式损坏");result.oldRevision=in.readUTF();result.newRevision=in.readUTF();if(!digest(result.oldRevision)||!digest(result.newRevision))throw new IOException("插页日志索引标记损坏");
            int count=in.readInt();if(count<1||count>100000)throw new IOException("插页日志文件数无效");
            for(int i=0;i<count;i++){String path=in.readUTF(),before=in.readUTF(),after=in.readUTF();validPath(path);if(!paths.add(path)||(!before.isEmpty()&&!digest(before))||(!after.isEmpty()&&!digest(after)))throw new IOException("插页日志条目损坏");result.entries.add(new Entry(path,before,after));}
            if(in.read()!=-1)throw new IOException("插页日志尾部损坏");
        }return result;
    }
    /** Caller holds all store locks; no payload reader may be admitted while startup recovery runs. */
    private static void recoverLocked(Context c)throws IOException {
        File dir=active(c);if(!dir.exists())return;File journal=new File(dir,"journal");
        if(!journal.exists()){discardPrepared(dir);return;}
        Journal transaction=readJournal(dir);File marker=new File(dir,"COMMITTED");boolean committed=false;
        fault("recover-before");
        if(!marker.exists())marker=new File(dir,"ROLLED_BACK");
        if(marker.isFile()){
            if(!readText(marker,128).equals(hash(journal)))throw new IOException("插页提交标记损坏");
            // This marker follows verified payloads AND a verified index commit. Later valid edits
            // may already have changed these files when retiring the directory was unsuccessful.
            // Cleanup must not restore, hash-check or invalidate those newer editor snapshots.
            retire(dir);return;
        }
        else{
            LibraryStore.State current=LibraryStore.load(c);if(!current.allowSave||!current.damaged.isEmpty())throw new IOException("无法判定插页索引状态，已保留全部原件");
            if(current.revision.equals(transaction.newRevision))committed=true;
            else if(!current.revision.equals(transaction.oldRevision))throw new IOException("插页索引版本不明确，已保留全部原件");
        }
        if(committed){verifyInstalled(c,transaction.entries,true);invalidate(c,transaction.entries);markCommitted(dir);}
        else {
            // Validate EVERY before-image before restoring ANY payload; damaged journals never erase unique data.
            for(Entry e:transaction.entries)if(!e.oldHash.isEmpty()&&!e.oldHash.equals(optionalHash(safe(new File(dir,"old"),e.path))))throw new IOException("插页原件校验失败，已保留全部副本");
            for(Entry e:transaction.entries)install(c,dir,e,false);
            verifyInstalled(c,transaction.entries,false);
            invalidate(c,transaction.entries);
            markSettled(dir,"ROLLED_BACK");
        }
        retire(dir);
    }
    private static void markCommitted(File dir)throws IOException {markSettled(dir,"COMMITTED");}
    private static void markSettled(File dir,String name)throws IOException {File marker=new File(dir,name);if(marker.isFile())return;byte[] bytes=hash(new File(dir,"journal")).getBytes(StandardCharsets.US_ASCII);write(new File(dir,name+".tmp"),out->out.write(bytes));if(!new File(dir,name+".tmp").renameTo(marker))throw new IOException("无法保存插页提交标记");}
    private static void verifyInstalled(Context c,List<Entry> entries,boolean next)throws IOException {for(Entry e:entries)if(!(next?e.newHash:e.oldHash).equals(optionalHash(safe(c.getFilesDir(),e.path))))throw new IOException("插页载荷验证失败："+e.path);}
    private static void install(Context c,File dir,Entry entry,boolean next)throws IOException {
        File target=safe(c.getFilesDir(),entry.path);String expected=next?entry.newHash:entry.oldHash;
        File temp=new File(target.getPath()+".inserting");
        if(!temp.getCanonicalPath().startsWith(c.getFilesDir().getCanonicalPath()+File.separator))throw new IOException("插页临时路径超出资料目录");
        if(temp.exists()&&!temp.delete())throw new IOException("无法清理中断的插页临时文件");
        if(expected.equals(optionalHash(target)))return;
        if(expected.isEmpty()){if(target.exists()&&!target.delete())throw new IOException("无法移除旧侧车："+entry.path);return;}
        File source=safe(new File(dir,next?"new":"old"),entry.path);if(!expected.equals(optionalHash(source)))throw new IOException("插页恢复副本校验失败");
        mkdir(target.getParentFile());copy(source,temp);
        if(!expected.equals(hash(temp)))throw new IOException("插页写入校验失败");
        if(target.exists()&&!target.delete())throw new IOException("无法替换插页文件："+entry.path);
        if(!temp.renameTo(target))throw new IOException("无法提交插页文件："+entry.path);
    }
    private static void validPath(String path)throws IOException {
        if(path==null||!(path.matches("[0-9a-fA-F-]{36}\\.pdf")||path.matches("[0-9a-fA-F-]{36}\\.reader(?:\\.bak|\\.new)?")||path.matches("[0-9a-fA-F-]{36}-[0-9]+\\.ink(?:\\.clear)?(?:\\.bak|\\.new)?")||path.matches("notes/[A-Za-z0-9_-]{1,80}/document\\.qnote(?:\\.bak|\\.new)?")||path.matches("recognition/[0-9a-fA-F-]{36}/page-[0-9]+\\.txt(?:\\.bak|\\.new)?")))throw new IOException("插页日志包含无效路径");
    }
    private static File safe(File root,String path)throws IOException {validPath(path);File file=new File(root,path);if(!file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("插页文件超出资料目录");return file;}
    private interface Writer {void write(FileOutputStream out)throws IOException;}
    private static void write(File file,Writer writer)throws IOException {mkdir(file.getParentFile());try(FileOutputStream out=new FileOutputStream(file)){writer.write(out);out.flush();out.getFD().sync();}}
    private static void copy(File source,File target)throws IOException {write(target,out->{try(InputStream in=new FileInputStream(source)){byte[] buffer=new byte[64*1024];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}});}
    static String hash(File file)throws IOException {try{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[64*1024];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static String optionalHash(File file)throws IOException {if(!file.exists())return "";if(!file.isFile())throw new IOException("插页路径不是普通文件");return hash(file);}
    private static boolean digest(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static String readText(File file,int max)throws IOException {if(file.length()>max)throw new IOException("侧车文件过大");try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>max)throw new IOException("侧车文件过大");out.write(buffer,0,n);}return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString();}}
    private static void mkdir(File dir)throws IOException {if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建插页工作目录");}
    private static void retire(File dir)throws IOException {
        try{fault("retire");}catch(IOException unavailable){return;}
        File retired=new File(dir.getParentFile(),"finished-"+UUID.randomUUID());
        // A failed cleanup leaves the journal and its originals intact. Retry recovery on next launch/edit.
        if(!dir.renameTo(retired))return;discardPrepared(retired);
    }
    private static void discardPrepared(File dir){try{deleteTree(dir,dir.getCanonicalPath());}catch(IOException ignored){}}
    private static void deleteTree(File file,String root)throws IOException {String canonical=file.getCanonicalPath();if(!canonical.equals(root)&&!canonical.startsWith(root+File.separator))throw new IOException("插页清理路径无效");if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("插页清理目录不可读");for(File child:children)deleteTree(child,root);}if(file.exists()&&!file.delete())throw new IOException("插页临时文件清理失败");}
}
