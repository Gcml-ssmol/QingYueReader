package cn.qingyue.pdf;

import android.util.AtomicFile;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Set;
import java.util.HashSet;

/** Atomic storage for additive reader metadata. Existing .ink files are untouched. */
public final class ReaderStore {
    static final class Loaded {
        final ReaderDocument document;
        final long revision;
        Loaded(ReaderDocument document,long revision){this.document=document;this.revision=revision;}
    }
    private static final class Pending {
        final long revision;
        final ReaderDocument document;
        Pending(long revision,ReaderDocument document){this.revision=revision;this.document=document;}
    }
    private static final AtomicLong revisions=new AtomicLong();
    private static final ConcurrentHashMap<String,Pending> pending=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,Long> currentRevisions=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,Object> locks=new ConcurrentHashMap<>();
    private static final Set<String> deleted=new HashSet<>();

    private ReaderStore(){}

    public static File file(File directory,String documentId){return new File(directory,safe(documentId)+".reader");}

    public static synchronized ReaderDocument load(File directory,String documentId)throws IOException {
        return loadVersioned(directory,documentId).document;
    }

    /** Returns a snapshot together with the in-process revision that made it current. */
    static synchronized Loaded loadVersioned(File directory,String documentId)throws IOException {
        File target=file(directory,documentId);String key=key(target);Object lock=lock(key);
        synchronized(lock){
            Pending staged=pending.get(key);
            if(staged!=null)return new Loaded(staged.document.copy(),staged.revision);
            ReaderDocument document;
            AtomicFile atomic=new AtomicFile(target);
            try(FileInputStream input=atomic.openRead()){document=ReaderDocument.read(input);}
            catch(FileNotFoundException missing){if(target.exists()||new File(target+".bak").exists())throw missing;document=new ReaderDocument();}
            return new Loaded(document,currentRevision(key));
        }
    }

    public static synchronized void save(File directory,String documentId,ReaderDocument document)throws IOException {
        long revision=stage(directory,documentId,document);saveStaged(directory,documentId,revision);
    }

    /** Publishes a detached snapshot immediately, before an Activity-owned I/O queue runs. */
    static synchronized long stage(File directory,String documentId,ReaderDocument document){
        if(document==null)throw new IllegalArgumentException("document");String key=key(file(directory,documentId));Object lock=lock(key);
        synchronized(lock){return deleted.contains(key)?-1L:publish(key,document);}
    }

    /** Publishes only when the editor was based on the currently visible revision. */
    static synchronized long stageIfCurrent(File directory,String documentId,ReaderDocument document,long expectedRevision){
        if(document==null)throw new IllegalArgumentException("document");String key=key(file(directory,documentId));Object lock=lock(key);
        synchronized(lock){return !deleted.contains(key)&&currentRevision(key)==expectedRevision?publish(key,document):-1L;}
    }

    /** Runs publication while holding the same lock used by stage, closing the check/use race. */
    static synchronized boolean runIfCurrent(File directory,String documentId,long expectedRevision,Runnable publication){
        if(publication==null)throw new IllegalArgumentException("publication");String key=key(file(directory,documentId));Object lock=lock(key);
        synchronized(lock){if(deleted.contains(key)||currentRevision(key)!=expectedRevision)return false;publication.run();return true;}
    }

    /** Writes only if this revision is still newest; superseded Activity work is a no-op. */
    static synchronized void saveStaged(File directory,String documentId,long revision)throws IOException {
        File target=file(directory,documentId);String key=key(target);Object lock=lock(key);
        synchronized(lock){
            Pending staged=pending.get(key);if(deleted.contains(key)||staged==null||staged.revision!=revision)return;AtomicFile atomic=new AtomicFile(target);FileOutputStream output=null;
            try{output=atomic.startWrite();staged.document.write(output);atomic.finishWrite(output);pending.remove(key,staged);}
            catch(IOException|RuntimeException problem){if(output!=null)atomic.failWrite(output);throw problem;}
        }
    }

    static synchronized void flushPending(File directory,String documentId)throws IOException {File target=file(directory,documentId);Pending staged=pending.get(key(target));if(staged!=null)saveStaged(directory,documentId,staged.revision);}
    static synchronized boolean hasPending(File directory,String documentId){return pending.containsKey(key(file(directory,documentId)));}

    public static synchronized void delete(File directory,String documentId){
        File target=file(directory,documentId);String key=key(target);Object lock=lock(key);
        synchronized(lock){deleted.add(key);pending.remove(key);currentRevisions.put(key,revisions.incrementAndGet());target.delete();new File(target+".bak").delete();new File(target+".new").delete();}
    }

    static synchronized void invalidate(File directory,String documentId){
        String key=key(file(directory,documentId));
        synchronized(lock(key)){deleted.add(key);pending.remove(key);currentRevisions.put(key,revisions.incrementAndGet());}
    }
    static synchronized void revive(File directory,String documentId){deleted.remove(key(file(directory,documentId)));}

    private static long publish(String key,ReaderDocument document){long revision=revisions.incrementAndGet();pending.put(key,new Pending(revision,document.copy()));currentRevisions.put(key,revision);return revision;}
    private static long currentRevision(String key){Long revision=currentRevisions.get(key);return revision==null?0L:revision;}
    private static Object lock(String key){return locks.computeIfAbsent(key,ignored->new Object());}
    private static String key(File target){return target.getAbsoluteFile().toPath().normalize().toString();}
    private static String safe(String id){if(id==null||!id.matches("[A-Za-z0-9._-]{1,160}")||id.equals(".")||id.equals(".."))throw new IllegalArgumentException("Unsafe document id");return id;}
}
