package cn.qingyue.pdf;

import android.util.AtomicFile;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** All disk calls run on the Activity's shared serial worker, including across recreation. */
final class InkStore {
    // Compile against the interface: the packaged Android desugar implementation exposes
    // keySet(): Set, not the JDK's covariant keySet(): ConcurrentHashMap.KeySetView.
    static final ConcurrentMap<String,List<InkPage.Stroke>> pending=new ConcurrentHashMap<>();
    private static final ConcurrentMap<String,Long> revisions=new ConcurrentHashMap<>();
    private static final Set<String> deletedDocuments=new HashSet<>();
    private static long nextRevision;
    /** Snapshot kept until the first post-clear save is safely on disk. */
    private static final ConcurrentMap<String,List<InkPage.Stroke>> pendingClear=new ConcurrentHashMap<>();
    static File file(File directory,String key){return new File(directory,key+".ink");}
    static File clearFile(File directory,String key){return new File(directory,key+".ink.clear");}
    static synchronized List<InkPage.Stroke> load(File directory,String key)throws IOException {
        List<InkPage.Stroke> unsaved=pending.get(key);if(unsaved!=null)return unsaved;
        AtomicFile atomic=new AtomicFile(file(directory,key));
        try(FileInputStream input=atomic.openRead()){return InkPage.read(input);}
        catch(FileNotFoundException e){if(file(directory,key).exists()||new File(file(directory,key)+".bak").exists())throw e;return Collections.emptyList();}
    }
    static synchronized void save(File directory,String key,List<InkPage.Stroke> snapshot)throws IOException {
        long revision=stage(key,snapshot);saveStaged(directory,key,revision);
    }
    static synchronized long stage(String key,List<InkPage.Stroke> snapshot){
        int separator=key.lastIndexOf('-');
        if(separator>0&&deletedDocuments.contains(key.substring(0,separator)))return -1;
        long revision=++nextRevision;pending.put(key,new InkPage(snapshot).strokes());revisions.put(key,revision);return revision;
    }
    static synchronized boolean saveStaged(File directory,String key,long revision)throws IOException {
        if(revision<0)return false;
        Long current=revisions.get(key);if(current==null||current!=revision)return false;
        List<InkPage.Stroke> snapshot=pending.get(key);if(snapshot==null)return false;
        List<InkPage.Stroke> recovery=pendingClear.get(key);
        if(recovery!=null&&!recovery.isEmpty())saveAtomic(clearFile(directory,key),recovery);
        AtomicFile atomic=new AtomicFile(file(directory,key));FileOutputStream out=null;
        try{out=atomic.startWrite();InkPage.write(out,snapshot);atomic.finishWrite(out);pending.remove(key,snapshot);if(recovery!=null)pendingClear.remove(key,recovery);return true;}
        catch(IOException|RuntimeException e){if(out!=null)atomic.failWrite(out);throw e;}
    }
    static synchronized void prepareClear(String key,List<InkPage.Stroke> snapshot){
        int separator=key.lastIndexOf('-');
        if(separator>0&&deletedDocuments.contains(key.substring(0,separator)))return;
        if(snapshot!=null&&!snapshot.isEmpty())pendingClear.put(key,Collections.unmodifiableList(new ArrayList<>(snapshot)));
    }
    /** Drain the editor's published snapshots before an archive reads committed files. */
    static synchronized void flushDocument(File directory,String id)throws IOException {
        String prefix=id+"-";
        for(Map.Entry<String,List<InkPage.Stroke>> entry:new ArrayList<>(pending.entrySet())){
            String key=entry.getKey();
            if(key.startsWith(prefix)&&key.substring(prefix.length()).matches("[0-9]+"))
                saveStaged(directory,key,revisions.get(key));
        }
        for(Map.Entry<String,List<InkPage.Stroke>> entry:new ArrayList<>(pendingClear.entrySet())){
            String key=entry.getKey();
            if(key.startsWith(prefix)&&key.substring(prefix.length()).matches("[0-9]+"))
                saveAtomic(clearFile(directory,key),entry.getValue());
        }
    }
    static boolean hasClearRecovery(File directory,String key){
        return pendingClear.containsKey(key)||clearFile(directory,key).isFile()||new File(clearFile(directory,key)+".bak").isFile();
    }
    static List<InkPage.Stroke> loadClearRecovery(File directory,String key)throws IOException {
        List<InkPage.Stroke> queued=pendingClear.get(key);if(queued!=null)return queued;
        AtomicFile atomic=new AtomicFile(clearFile(directory,key));
        try(FileInputStream input=atomic.openRead()){return InkPage.read(input);}
    }
    static synchronized boolean consumeClearRecovery(File directory,String key,long revision)throws IOException {
        if(!saveStaged(directory,key,revision))return false;
        pendingClear.remove(key);
        File target=clearFile(directory,key);target.delete();new File(target+".bak").delete();new File(target+".new").delete();
        return true;
    }
    static SortedSet<Integer> annotatedPages(File directory,String id,int pageCount){
        TreeSet<Integer> pages=new TreeSet<>();String prefix=id+"-";
        for(String key:pending.keySet())addPage(pages,key,prefix,pageCount);
        File[] files=directory.listFiles();if(files!=null)for(File f:files){String name=f.getName();if(name.endsWith(".ink"))addPage(pages,name.substring(0,name.length()-4),prefix,pageCount);else if(name.endsWith(".ink.bak"))addPage(pages,name.substring(0,name.length()-8),prefix,pageCount);}
        return Collections.unmodifiableSortedSet(pages);
    }
    private static void addPage(Set<Integer> pages,String key,String prefix,int count){
        if(!key.startsWith(prefix))return;try{int page=Integer.parseInt(key.substring(prefix.length()));if(page>=0&&page<count)pages.add(page);}catch(NumberFormatException ignored){}
    }
    private static void saveAtomic(File target,List<InkPage.Stroke> snapshot)throws IOException {
        AtomicFile atomic=new AtomicFile(target);FileOutputStream out=null;
        try{out=atomic.startWrite();InkPage.write(out,snapshot);atomic.finishWrite(out);}
        catch(IOException|RuntimeException e){if(out!=null)atomic.failWrite(out);throw e;}
    }
    static synchronized void invalidateDocument(String id){
        deletedDocuments.add(id);
        InkHistoryRegistry.removeDocument(id);
        for(String key:new ArrayList<>(pending.keySet()))if(key.startsWith(id+"-")){pending.remove(key);revisions.put(key,++nextRevision);}
        for(String key:new ArrayList<>(pendingClear.keySet()))if(key.startsWith(id+"-"))pendingClear.remove(key);
    }
    static synchronized void reviveDocument(String id){deletedDocuments.remove(id);}
    static synchronized void deleteDocument(File directory,String id){
        invalidateDocument(id);
        File[] files=directory.listFiles();if(files==null)return;
        for(File f:files)if(f.getName().startsWith(id+"-")&&(f.getName().endsWith(".ink")||f.getName().endsWith(".ink.bak")||f.getName().endsWith(".ink.new")||f.getName().endsWith(".ink.clear")||f.getName().endsWith(".ink.clear.bak")||f.getName().endsWith(".ink.clear.new")))f.delete();
    }
}
