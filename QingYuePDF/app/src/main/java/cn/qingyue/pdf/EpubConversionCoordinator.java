package cn.qingyue.pdf;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;

/** Durable, private PDF conversion jobs. A worker always reads its immutable source.pdf. */
public final class EpubConversionCoordinator {
    public enum State { RUNNING, INTERRUPTED, COMPLETED, CANCELED, FAILED }
    public enum Kind { SAMPLE, FULL }
    public interface Listener { void changed(Session session, Snapshot snapshot); }
    public static final class Snapshot {
        public final String id; public final Kind kind; public final State state; public final long revision;
        public final int completed,total,pageIndex; public final String stage; public final boolean cancelRequested;
        public final File epub; public final PdfEpubExporter.Report report; public final Throwable error;
        Snapshot(String id,Kind kind,State state,long revision,int completed,int total,int pageIndex,
                 String stage,boolean cancelRequested,File epub,PdfEpubExporter.Report report,Throwable error){
            this.id=id;this.kind=kind;this.state=state;this.revision=revision;this.completed=completed;
            this.total=total;this.pageIndex=pageIndex;this.stage=stage;this.cancelRequested=cancelRequested;
            this.epub=epub;this.report=report;this.error=error;
        }
        public boolean terminal(){return state!=State.RUNNING;}
    }
    public static final class Session {
        private final Object lock=new Object();
        private final String id,documentId,title,author,libraryHash;
        private final int from,to; private final Kind kind; private final boolean coverFirstPage,deskew;
        private final List<EpubBook.Heading> overrides;
        private final Context app; private volatile OcrCancellation cancellation=new OcrCancellation();
        private final CopyOnWriteArrayList<WeakReference<Listener>> listeners=new CopyOnWriteArrayList<>();
        private volatile Snapshot snapshot; private long revision; private String sourceHash="",outputHash="",savedUri="";
        private long outputBytes; private int saveLeases,attemptsQueued;
        private Session(Context context,String id,String documentId,String title,String author,int from,int to,
                Kind kind,boolean coverFirstPage,boolean deskew,List<EpubBook.Heading> overrides,String libraryHash){
            this.app=context.getApplicationContext();this.id=id;this.documentId=documentId;this.title=title;
            this.author=author;this.from=from;this.to=to;this.kind=kind;this.coverFirstPage=coverFirstPage;
            this.deskew=deskew;this.overrides=overrides==null?null:Collections.unmodifiableList(new ArrayList<>(overrides));
            this.libraryHash=libraryHash==null?"":libraryHash;
            snapshot=next(State.INTERRUPTED,0,to-from,-1,"等待开始",false,null,null,null);
        }
        public String id(){return id;} public String documentId(){return documentId;}
        public String title(){return title;} public String author(){return author;}
        public int from(){return from;} public int to(){return to;} public boolean deskew(){return deskew;}
        public List<EpubBook.Heading> overrides(){return overrides;}
        public String savedUri(){return savedUri;} public Snapshot snapshot(){return snapshot;}
        public boolean verifyOutput()throws IOException{return snapshot.state==State.COMPLETED&&output().isFile()
                &&output().length()==outputBytes&&!outputHash.isEmpty()
                &&outputHash.equalsIgnoreCase(hash(output(),new OcrCancellation()));}
        public boolean acquireSaveLease(){synchronized(lock){if(snapshot.state!=State.COMPLETED)return false;
            saveLeases++;return true;}}
        public void releaseSaveLease(){synchronized(lock){if(saveLeases>0)saveLeases--;}}
        public void attach(Listener listener){if(listener==null)return;detach(listener);
            WeakReference<Listener> ref=new WeakReference<>(listener);listeners.add(ref);dispatch(ref,snapshot);}
        public void detach(Listener listener){for(WeakReference<Listener> ref:listeners){Listener old=ref.get();
            if(old==null||old==listener)listeners.remove(ref);}}
        public boolean cancel(){Snapshot value; synchronized(lock){if(snapshot.terminal()||snapshot.cancelRequested)return false;
            cancellation.cancel();value=next(State.RUNNING,snapshot.completed,snapshot.total,snapshot.pageIndex,
                    "正在取消…",true,null,null,null);
            try{persist();}catch(IllegalStateException bad){value=next(State.FAILED,value.completed,value.total,
                    value.pageIndex,"任务记录写入失败",false,null,null,bad);}}
            publish(value);return true;}
        public boolean interrupt(){return interrupt("后台任务已中断；可从导出中心继续");}
        public boolean interrupt(String reason){Snapshot value;synchronized(lock){if(snapshot.state!=State.RUNNING)return false;
            cancellation.cancel();value=next(State.INTERRUPTED,snapshot.completed,snapshot.total,snapshot.pageIndex,
                    reason,false,null,null,null);
            try{persist();}catch(IllegalStateException bad){value=next(State.FAILED,value.completed,value.total,
                    value.pageIndex,"任务记录写入失败",false,null,null,bad);}}
            publish(value);return true;}
        public boolean resume(){synchronized(lock){if(snapshot.state!=State.INTERRUPTED&&snapshot.state!=State.FAILED
                &&snapshot.state!=State.CANCELED)return false;
            cancellation=new OcrCancellation();Snapshot value=next(State.RUNNING,0,to-from,-1,"准备恢复",false,null,null,null);
            try{persist();}catch(IllegalStateException bad){next(State.FAILED,0,to-from,-1,
                    "任务记录写入失败",false,null,null,bad);return false;}
            publish(value);OcrCancellation token=cancellation;attemptsQueued++;WORKER.execute(()->run(token));return true;}}
        public boolean markSaved(String uri){synchronized(lock){savedUri=uri==null?"":uri;
            try{persist();return true;}catch(IllegalStateException bad){return false;}}}
        private Snapshot next(State state,int done,int total,int page,String stage,boolean cancel,File epub,
                              PdfEpubExporter.Report report,Throwable error){
            Snapshot value=new Snapshot(id,kind,state,++revision,done,total,page,stage,cancel,epub,report,error);
            snapshot=value;return value;
        }
        private void progress(OcrCancellation token,int done,int total,int page,String stage){Snapshot value;
            synchronized(lock){if(cancellation!=token||snapshot.terminal()||snapshot.cancelRequested)return;
                value=next(State.RUNNING,Math.max(0,done),Math.max(1,total),page,
                        stage==null?"正在转换":stage,false,null,null,null);
                try{persist();}catch(IllegalStateException bad){token.cancel();value=next(State.FAILED,value.completed,
                        value.total,value.pageIndex,"任务记录写入失败",false,null,null,bad);}}
            publish(value);}
        private void finish(OcrCancellation token,State state,File epub,PdfEpubExporter.Report report,Throwable error){Snapshot value;
            synchronized(lock){if(cancellation!=token||snapshot.terminal())return;
                if(state==State.COMPLETED&&token.isCancelled()){state=State.CANCELED;epub=null;report=null;}
                Snapshot candidate=new Snapshot(id,kind,state,++revision,snapshot.completed,snapshot.total,snapshot.pageIndex,
                        state==State.COMPLETED?"转换完成":state==State.CANCELED?"已取消":"转换失败",
                        snapshot.cancelRequested,epub,report,error);
                try{persist(candidate);snapshot=candidate;value=candidate;}
                catch(IllegalStateException bad){value=next(state,candidate.completed,candidate.total,
                        candidate.pageIndex,state==State.COMPLETED?"转换完成；记录未写入，请立即保存":"任务记录写入失败",
                        candidate.cancelRequested,epub,report,bad);}}
            publish(value);}
        private void publish(Snapshot value){for(WeakReference<Listener> ref:listeners)dispatch(ref,value);}
        private void dispatch(WeakReference<Listener> ref,Snapshot value){MAIN.post(()->{Listener l=ref.get();
            if(l==null){listeners.remove(ref);return;}if(listeners.contains(ref)&&value.revision>=snapshot.revision)
                l.changed(this,value);});}
        private File dir(){return jobDir(app,id);} private File source(){return new File(dir(),"source.pdf");}
        private File output(){return new File(dir(),"book.epub");}
        private void run(OcrCancellation token){File dir=dir(),source=source(),output=output();boolean success=false;
            try{
                if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建任务目录");
                if(source.isFile()){
                    String actual=hash(source,token);
                    if(!sourceHash.isEmpty()&&!sourceHash.equalsIgnoreCase(actual)
                            ||sourceHash.isEmpty()&&!libraryHash.isEmpty()&&!libraryHash.equalsIgnoreCase(actual))
                        throw new IOException("原快照校验失败；请删除此任务后重新转换");
                    if(sourceHash.isEmpty()){sourceHash=actual;persist();}
                }else capture(source,token);
                token.throwIfCancelled();
                if(output.exists()&&!output.delete())throw new IOException("无法清理旧版 EPUB");
                PdfEpubExporter.Options options=new PdfEpubExporter.Options(title,author,from,to,documentId,
                        libraryHash,coverFirstPage).withDeskew(deskew).withChapterOverrides(overrides);
                PdfEpubExporter.Report report=PdfEpubExporter.convert(app,source,output,options,token,
                        (done,total,page,stage)->progress(token,done,total,page,stage),null);
                token.throwIfCancelled();
                if(!output.isFile()||output.length()==0)throw new IOException("转换未生成有效电子书");
                outputBytes=output.length();outputHash=hash(output,token);
                finish(token,State.COMPLETED,output,report,null);success=snapshot.state==State.COMPLETED;
            }catch(Throwable failure){finish(token,token.isCancelled()?State.CANCELED:State.FAILED,null,null,failure);}
            finally{if(!success)output.delete();synchronized(lock){attemptsQueued=Math.max(0,attemptsQueued-1);}}
        }
        private void capture(File target,OcrCancellation token)throws IOException{
            // Import/insertion lock precedes trash lock; hold both only for the bounded source copy.
            synchronized(BackupManager.TRANSACTION_LOCK){synchronized(TrashManager.class){
                token.throwIfCancelled();LibraryItem item=null;
                for(LibraryItem candidate:LibraryStore.load(app).items)
                    if(documentId.equals(candidate.id)&&!candidate.isNote()&&!candidate.isTrashed()){item=candidate;break;}
                if(item==null||to>item.pages)throw new IOException("原 PDF 已移除或页数变化，无法继续");
                if(!libraryHash.equalsIgnoreCase(item.hash))throw new IOException("原 PDF 已变化，请重新建立转换任务");
                File original=new File(app.getFilesDir(),documentId+".pdf");
                if(!original.isFile()||original.length()>512L*1024*1024)throw new IOException("原 PDF 不存在或超过 512 MB");
                try(DocumentEditLease lease=DocumentEditLease.acquire(app,documentId)){
                    File temporary=new File(dir(),"source.partial");
                    try{String actual=copyHash(original,temporary,token);
                        if(!sourceHash.isEmpty()&&!sourceHash.equalsIgnoreCase(actual)
                                ||!libraryHash.isEmpty()&&!actual.equalsIgnoreCase(libraryHash))
                            throw new IOException("原 PDF 内容已变化，请重新建立转换任务");
                        sourceHash=actual;
                        if(!temporary.renameTo(target))throw new IOException("无法保存原 PDF 快照");
                        persist();
                    }finally{temporary.delete();}
                }
            }}
        }
        private void persist(){persist(snapshot);}
        private void persist(Snapshot value){synchronized(lock){try{saveRecord(this,value);}
            catch(IOException error){throw new IllegalStateException("无法保存转换任务："+error.getMessage(),error);}}}
    }
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"qingyue-epub");
        t.setPriority(Thread.NORM_PRIORITY-1);return t;});
    private static final ConcurrentHashMap<String,Session> SESSIONS=new ConcurrentHashMap<>();
    private static Context APP; private EpubConversionCoordinator(){}
    private static File root(Context app){return new File(app.getFilesDir(),"epub-jobs");}
    private static File jobDir(Context app,String id){if(!LibraryItem.isUuid(id))throw new IllegalArgumentException("非法任务编号");
        try{File files=app.getFilesDir().getCanonicalFile(),jobs=root(app).getCanonicalFile();
            File dir=new File(jobs,id).getCanonicalFile();
            if(!jobs.getParentFile().equals(files)||!dir.getParentFile().equals(jobs))
                throw new IllegalArgumentException("任务路径无效");
            return dir;
        }catch(IOException bad){throw new IllegalArgumentException("任务路径无效",bad);}}
    public static synchronized void initialize(Context app){if(app==null)return;Context application=app.getApplicationContext();
        if(APP==application)return;APP=application;SESSIONS.clear();File[] dirs=root(application).listFiles();if(dirs==null)return;
        for(File dir:dirs){if(!dir.isDirectory()||!LibraryItem.isUuid(dir.getName()))continue;
            try{Session s=readRecord(application,dir);SESSIONS.put(s.id,s);}catch(Exception ignored){}
        }}
    public static synchronized Session start(Context app,String documentId,String title,String author,int from,int to,
            Kind kind,boolean coverFirstPage){return start(app,documentId,title,author,from,to,kind,coverFirstPage,false,null);}
    public static synchronized Session start(Context app,String documentId,String title,String author,int from,int to,
            Kind kind,boolean coverFirstPage,boolean deskew,List<EpubBook.Heading> overrides){
        if(app==null||!LibraryItem.isUuid(documentId)||title==null||title.trim().isEmpty()||title.length()>512
                ||author!=null&&author.length()>512||from<0||to<=from||to-from>2000||kind==null)
            throw new IllegalArgumentException("转换参数无效");
        initialize(app);for(Session s:SESSIONS.values())if(s.snapshot.state==State.RUNNING)
            throw new IllegalStateException("已有电子书转换任务正在运行");
        if(SESSIONS.size()>=12)throw new IllegalStateException("导出中心最多保留 12 个任务，请清理旧任务");
        LibraryItem item=null;for(LibraryItem candidate:LibraryStore.load(app).items)
            if(documentId.equals(candidate.id)&&!candidate.isTrashed()&&!candidate.isNote()){item=candidate;break;}
        if(item==null||to>item.pages)throw new IllegalArgumentException("原 PDF 已移除或页数变化");
        File original=new File(app.getFilesDir(),documentId+".pdf");
        if(!original.isFile()||original.length()>512L*1024*1024)
            throw new IllegalArgumentException("原 PDF 不存在或超过 512 MB");
        long used=0;for(Session existing:SESSIONS.values()){
            File dir=existing.dir();for(String name:new String[]{"source.pdf","book.epub"}){
                long size=new File(dir,name).length();if(size>0)used+=size;
            }}
        if(used+original.length()>1024L*1024*1024)
            throw new IllegalStateException("导出中心私有文件已达到 1 GB 上限，请删除旧任务");
        Session s=new Session(app,UUID.randomUUID().toString(),documentId,title.trim(),
                author==null?"":author.trim(),from,to,kind,coverFirstPage,deskew,overrides,item.hash);
        if(!s.dir().mkdirs())throw new IllegalStateException("无法创建任务目录");
        s.snapshot=s.next(State.RUNNING,0,to-from,-1,"准备原文档",false,null,null,null);
        try{s.persist();}catch(IllegalStateException bad){s.dir().delete();throw bad;}
        SESSIONS.put(s.id,s);OcrCancellation token=s.cancellation;s.attemptsQueued=1;WORKER.execute(()->s.run(token));return s;
    }
    public static Session find(String id){return id==null?null:SESSIONS.get(id);}
    public static List<Session> sessions(){ArrayList<Session> list=new ArrayList<>(SESSIONS.values());
        Collections.sort(list,(a,b)->b.id.compareTo(a.id));return list;}
    public static synchronized boolean forget(String id){Session s=find(id);if(s==null||!s.snapshot.terminal())return false;
        synchronized(s.lock){if(s.saveLeases>0||s.attemptsQueued>0)return false;}
        File dir=s.dir();boolean clean=true;
        for(String name:new String[]{"source.pdf","source.partial","book.epub","task.properties","task.pending"})
            {File file=new File(dir,name);if(file.exists()&&!file.delete())clean=false;}
        if(!dir.delete())clean=false;if(clean)SESSIONS.remove(id,s);return clean;}
    /** Kept for old callers; Activity destruction no longer discards durable jobs. */
    public static void releaseWhenDone(String id){}
    public static boolean canSave(String pickerSessionId,Snapshot result){return pickerSessionId!=null&&result!=null
        &&pickerSessionId.equals(result.id)&&result.kind==Kind.FULL&&result.state==State.COMPLETED
        &&result.epub!=null&&result.epub.isFile()&&find(result.id)!=null
        &&result.epub.length()==find(result.id).outputBytes;}
    private static String hash(File file,OcrCancellation token)throws IOException{try(FileInputStream in=new FileInputStream(file)){
        MessageDigest digest=sha();byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){token.throwIfCancelled();digest.update(b,0,n);}
        return hex(digest.digest());}}
    private static String copyHash(File source,File target,OcrCancellation token)throws IOException{
        MessageDigest digest=sha();try(FileInputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(target)){
            byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){token.throwIfCancelled();out.write(b,0,n);digest.update(b,0,n);}
            out.getFD().sync();}return hex(digest.digest());}
    private static MessageDigest sha()throws IOException{try{return MessageDigest.getInstance("SHA-256");}
        catch(Exception error){throw new IOException(error);}}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
    private static void saveRecord(Session s,Snapshot v)throws IOException{File dir=s.dir();if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("任务目录不可写");
        Properties p=new Properties();p.setProperty("version","1");p.setProperty("id",s.id);p.setProperty("document",s.documentId);
        p.setProperty("title",s.title);p.setProperty("author",s.author);p.setProperty("from",String.valueOf(s.from));
        p.setProperty("to",String.valueOf(s.to));p.setProperty("kind",s.kind.name());p.setProperty("cover",String.valueOf(s.coverFirstPage));
        p.setProperty("deskew",String.valueOf(s.deskew));p.setProperty("libraryHash",s.libraryHash);p.setProperty("sourceHash",s.sourceHash);
        p.setProperty("savedUri",s.savedUri);p.setProperty("outputBytes",String.valueOf(s.outputBytes));
        p.setProperty("outputHash",s.outputHash);p.setProperty("overrides",s.overrides==null?"auto":String.valueOf(s.overrides.size()));
        if(s.overrides!=null)for(int i=0;i<s.overrides.size();i++){EpubBook.Heading h=s.overrides.get(i);
            p.setProperty("h"+i+"title",h.title);p.setProperty("h"+i+"page",String.valueOf(h.sourcePage));
            p.setProperty("h"+i+"anchor",h.anchorText==null?"":h.anchorText);}
        p.setProperty("state",v.state.name());p.setProperty("done",String.valueOf(v.completed));
        p.setProperty("total",String.valueOf(v.total));p.setProperty("page",String.valueOf(v.pageIndex));
        p.setProperty("stage",v.stage);p.setProperty("error",v.error==null?"":String.valueOf(v.error.getMessage()));
        if(v.report!=null){PdfEpubExporter.Report r=v.report;for(String[] pair:new String[][]{{"pages",String.valueOf(r.pages)},
            {"characters",String.valueOf(r.characters)},{"ocrPages",String.valueOf(r.ocrPages)},
            {"blankPages",String.valueOf(r.blankPages)},{"lowConfidencePages",String.valueOf(r.lowConfidencePages)}})
            p.setProperty(pair[0],pair[1]);p.setProperty("chapters",String.valueOf(r.chapterPreviews.size()));
            for(int i=0;i<r.chapterPreviews.size();i++){PdfEpubExporter.ChapterPreview c=r.chapterPreviews.get(i);
                p.setProperty("c"+i+"title",c.title);p.setProperty("c"+i+"page",String.valueOf(c.sourcePage));
                p.setProperty("c"+i+"excerpt",c.excerpt);}
            if(r.warnings.size()>20000)throw new IOException("需校订页记录超过上限");
            p.setProperty("warnings",String.valueOf(r.warnings.size()));for(int i=0;i<r.warnings.size();i++){
                PdfEpubExporter.PageWarning w=r.warnings.get(i);p.setProperty("w"+i+"page",String.valueOf(w.pageIndex));
                p.setProperty("w"+i+"kind",w.kind.name());p.setProperty("w"+i+"confidence",String.valueOf(w.confidence));
                p.setProperty("w"+i+"detail",w.detail==null?"":w.detail);}}
        File pending=new File(dir,"task.pending"),record=new File(dir,"task.properties");
        try(FileOutputStream out=new FileOutputStream(pending)){p.store(out,"QingYue private EPUB task");out.getFD().sync();}
        Files.move(pending.toPath(),record.toPath(),StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }
    private static Session readRecord(Context app,File dir)throws IOException{Properties p=new Properties();
        File record=new File(dir,"task.properties"),pending=new File(dir,"task.pending");
        if(!record.isFile()&&pending.isFile())Files.move(pending.toPath(),record.toPath(),
                StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        try(FileInputStream in=new FileInputStream(record)){p.load(in);}
        if(!"1".equals(p.getProperty("version"))||!dir.getName().equals(p.getProperty("id")))throw new IOException("任务记录无效");
        String document=p.getProperty("document","");if(!LibraryItem.isUuid(document))throw new IOException("文档编号无效");
        int from=num(p,"from"),to=num(p,"to");if(from<0||to<=from||to-from>2000)throw new IOException("页码无效");
        String mode=p.getProperty("overrides","auto");List<EpubBook.Heading> heads=null;
        if(!"auto".equals(mode)){int count=Integer.parseInt(mode);if(count<0||count>2000)throw new IOException("目录无效");
            heads=new ArrayList<>();for(int i=0;i<count;i++)heads.add(new EpubBook.Heading(
                    p.getProperty("h"+i+"title",""),num(p,"h"+i+"page"),p.getProperty("h"+i+"anchor","")));}
        Session s=new Session(app,dir.getName(),document,p.getProperty("title",""),p.getProperty("author",""),
                from,to,Kind.valueOf(p.getProperty("kind")),Boolean.parseBoolean(p.getProperty("cover")),
                Boolean.parseBoolean(p.getProperty("deskew")),heads,p.getProperty("libraryHash",""));
        s.sourceHash=p.getProperty("sourceHash","");s.savedUri=p.getProperty("savedUri","");
        s.outputHash=p.getProperty("outputHash","");try{s.outputBytes=Long.parseLong(p.getProperty("outputBytes","0"));}
        catch(NumberFormatException invalid){throw new IOException("结果大小无效",invalid);}
        State state=State.valueOf(p.getProperty("state"));String stage=p.getProperty("stage","");
        PdfEpubExporter.Report report=null;File output=new File(dir,"book.epub");
        if(state==State.COMPLETED&&output.isFile()&&output.length()>0&&output.length()==s.outputBytes&&p.containsKey("pages")){
            ArrayList<PdfEpubExporter.ChapterPreview> chapters=new ArrayList<>();
            for(int i=0,n=num(p,"chapters");i<n&&i<2000;i++)chapters.add(new PdfEpubExporter.ChapterPreview(
                    p.getProperty("c"+i+"title",""),p.getProperty("c"+i+"excerpt",""),num(p,"c"+i+"page")));
            ArrayList<PdfEpubExporter.PageWarning> warnings=new ArrayList<>();
            int warningCount=num(p,"warnings");if(warningCount<0||warningCount>20000)throw new IOException("需校订页记录无效");
            for(int i=0;i<warningCount;i++)warnings.add(new PdfEpubExporter.PageWarning(
                    num(p,"w"+i+"page"),PdfEpubExporter.PageWarning.Kind.valueOf(p.getProperty("w"+i+"kind")),
                    num(p,"w"+i+"confidence"),p.getProperty("w"+i+"detail","")));
            report=new PdfEpubExporter.Report(num(p,"pages"),num(p,"characters"),num(p,"ocrPages"),
                    num(p,"blankPages"),num(p,"lowConfidencePages"),chapters,warnings);
        }else if(state==State.COMPLETED){state=State.INTERRUPTED;stage="结果文件不存在，请继续重建";}
        if(state==State.RUNNING){state=State.INTERRUPTED;stage="上次转换中断，验证快照后可继续";}
        String error=p.getProperty("error","");s.snapshot=s.next(state,num(p,"done"),num(p,"total"),num(p,"page"),
                stage,false,state==State.COMPLETED?output:null,report,error.isEmpty()?null:new IOException(error));
        return s;
    }
    private static int num(Properties p,String key)throws IOException{try{return Integer.parseInt(p.getProperty(key,"0"));}
        catch(NumberFormatException error){throw new IOException("任务数值无效："+key,error);}}
}
