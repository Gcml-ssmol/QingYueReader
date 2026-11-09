package cn.qingyue.pdf;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Atomic snapshots; queued old revisions cannot overwrite a newer save or resurrect deleted notes. */
public final class NoteStore {
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static final Map<String,NoteDocument> pending=new HashMap<>();
    // A copy shares its session token. Weak keys retain the durable high-water mark while any
    // editor/pending snapshot exists, but transient thumbnail/search loads leave no permanent row.
    private static final WeakHashMap<Object,SavedRevision> saved=new WeakHashMap<>();
    private static final class SavedRevision {
        final String key;final long revision;
        SavedRevision(String key,long revision){this.key=key;this.revision=revision;}
    }
    private static final Map<String,Version> scheduled=new HashMap<>();
    private static final Set<String> deleted=new HashSet<>();
    private static final class Version {
        final Object session;final long revision;
        Version(NoteDocument note){session=note.saveSession();revision=note.revision();}
        @Override public boolean equals(Object value){if(!(value instanceof Version))return false;Version other=(Version)value;return revision==other.revision&&session==other.session;}
        @Override public int hashCode(){return 31*System.identityHashCode(session)+(int)(revision^(revision>>>32));}
    }
    private static boolean isSaved(String key,Version version){SavedRevision newest=saved.get(version.session);return newest!=null&&key.equals(newest.key)&&newest.revision>=version.revision;}
    private static void markSaved(String key,NoteDocument note){
        Object session=note.saveSession();SavedRevision old=saved.get(session);
        if(old==null||!key.equals(old.key)||note.revision()>old.revision)saved.put(session,new SavedRevision(key,note.revision()));
    }
    private static void forgetSaved(String key){Iterator<Map.Entry<Object,SavedRevision>> rows=saved.entrySet().iterator();while(rows.hasNext())if(key.equals(rows.next().getValue().key))rows.remove();}
    public interface Failure {void failed(String message);}
    private NoteStore(){}
    private static File directory(Context c,String id)throws IOException{if(id==null||!id.matches("[a-zA-Z0-9_-]{1,80}"))throw new IOException("无效的笔记编号");return new File(new File(c.getFilesDir(),"notes"),id);}
    private static String key(Context c,String id)throws IOException{return directory(c,id).getAbsolutePath();}
    private static File file(Context c,String id)throws IOException{return new File(directory(c,id),"document.qnote");}
    public static synchronized NoteDocument create(Context c,String id,String template,String size)throws IOException{NoteDocument d=NoteDocument.create(template,size);String k=key(c,id);if(exists(c,id))throw new IOException("笔记编号已存在");deleted.remove(k);save(c,id,d);return d;}
    public static synchronized boolean exists(Context c,String id){try{File f=file(c,id);return !deleted.contains(key(c,id))&&(pending.containsKey(key(c,id))||f.exists()||new File(f+".bak").exists());}catch(IOException e){return false;}}
    public static synchronized NoteDocument load(Context c,String id)throws IOException{String k=key(c,id);if(deleted.contains(k))throw new FileNotFoundException("笔记已移除");NoteDocument d=pending.get(k);if(d!=null)return d.copy();try(FileInputStream in=new AtomicFile(file(c,id)).openRead()){NoteDocument loaded=NoteDocument.read(in);markSaved(k,loaded);return loaded;}}
    public static synchronized File imagesDirectory(Context c,String id)throws IOException{if(!exists(c,id))throw new FileNotFoundException("笔记已移除");File f=new File(directory(c,id),"images");if(!f.isDirectory()&&!f.mkdirs())throw new IOException("无法创建图片目录");return f;}
    /** Clipboard images may be reused only while the original file belongs to this note. */
    public static synchronized boolean imageExists(Context c,String id,String name){try{return exists(c,id)&&name!=null&&name.matches("[a-zA-Z0-9_-]+\\.(jpg|jpeg|png|webp)")&&new File(new File(directory(c,id),"images"),name).isFile();}catch(IOException error){return false;}}
    public static synchronized void save(Context c,String id,NoteDocument document)throws IOException{
        String k=key(c,id);if(deleted.contains(k))return;Version version=new Version(document);
        if(isSaved(k,version))return;
        NoteDocument waiting=pending.get(k);
        if(waiting!=null&&waiting.saveSession()==version.session&&waiting.revision()>version.revision)return;
        NoteDocument snapshot=document.copy();pending.put(k,snapshot);write(c,id,k,snapshot);
    }
    public static void saveAsync(Context c,String id,NoteDocument document,Failure failure){
        final Context app=c.getApplicationContext();final String k;final NoteDocument snapshot;final Version version;
        try{
            k=key(app,id);Version before=new Version(document);
            synchronized(NoteStore.class){if(skipAsync(k,before))return;}
            // Copy outside the store lock: the disk writer must not block UI snapshot assembly.
            snapshot=document.copy();version=new Version(snapshot);
            synchronized(NoteStore.class){if(skipAsync(k,version))return;pending.put(k,snapshot);scheduled.put(k,version);}
        }catch(IOException|RuntimeException e){if(failure!=null)failure.failed("笔记保存失败："+e.getMessage());return;}
        worker.execute(()->{try{synchronized(NoteStore.class){if(deleted.contains(k)||pending.get(k)!=snapshot)return;write(app,id,k,snapshot);}}catch(IOException|RuntimeException e){if(failure!=null)failure.failed("笔记保存失败："+e.getMessage());}finally{synchronized(NoteStore.class){if(version.equals(scheduled.get(k)))scheduled.remove(k);}}});
    }
    private static boolean skipAsync(String k,Version version){
        if(deleted.contains(k)||isSaved(k,version))return true;
        NoteDocument waiting=pending.get(k);
        return waiting!=null&&(waiting.saveSession()==version.session&&waiting.revision()>version.revision
                ||version.equals(new Version(waiting))&&version.equals(scheduled.get(k)));
    }
    private static void write(Context c,String id,String k,NoteDocument snapshot)throws IOException{File dir=directory(c,id);if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建笔记目录");AtomicFile atomic=new AtomicFile(file(c,id));FileOutputStream out=null;try{out=atomic.startWrite();NoteDocument.write(out,snapshot);atomic.finishWrite(out);markSaved(k,snapshot);if(pending.get(k)==snapshot)pending.remove(k);}catch(IOException|RuntimeException e){if(out!=null)atomic.failWrite(out);throw e;}}
    public static synchronized boolean hasPending(Context c,String id)throws IOException{return pending.containsKey(key(c,id));}
    /** Called under the library transaction lock before moving a note to durable delete staging. */
    static synchronized void flushPending(Context c,String id)throws IOException{String k=key(c,id);NoteDocument snapshot=pending.get(k);if(snapshot!=null&&!deleted.contains(k))write(c,id,k,snapshot);}
    static synchronized void invalidate(Context c,String id)throws IOException{String k=key(c,id);pending.remove(k);scheduled.remove(k);forgetSaved(k);deleted.add(k);}
    static synchronized void revive(Context c,String id)throws IOException{String k=key(c,id);deleted.remove(k);forgetSaved(k);scheduled.remove(k);}
    public static synchronized void delete(Context c,String id)throws IOException{String k=key(c,id);File dir=directory(c,id);File trash=new File(dir.getParentFile(),id+".deleted-"+UUID.randomUUID());if(dir.exists()&&!dir.renameTo(trash))throw new IOException("无法移除笔记，请稍后重试");deleted.add(k);pending.remove(k);forgetSaved(k);scheduled.remove(k);try{deleteTree(trash);}catch(IOException cleanup){worker.execute(()->{try{deleteTree(trash);}catch(IOException ignored){}});}}
    private static void deleteTree(File f)throws IOException{if(!f.exists())return;if(f.isDirectory()){File[] children=f.listFiles();if(children==null)throw new IOException("无法读取笔记目录");for(File child:children)deleteTree(child);}if(!f.delete())throw new IOException("无法移除笔记文件");}
}
