package cn.qingyue.pdf;

import android.util.AtomicFile;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** All disk calls run on the Activity's shared serial worker, including across recreation. */
final class InkStore {
    static final ConcurrentHashMap<String,List<InkPage.Stroke>> pending=new ConcurrentHashMap<>();
    static File file(File directory,String key){return new File(directory,key+".ink");}
    static List<InkPage.Stroke> load(File directory,String key)throws IOException {
        List<InkPage.Stroke> unsaved=pending.get(key);if(unsaved!=null)return unsaved;
        AtomicFile atomic=new AtomicFile(file(directory,key));
        try(FileInputStream input=atomic.openRead()){return InkPage.read(input);}
        catch(FileNotFoundException e){if(file(directory,key).exists()||new File(file(directory,key)+".bak").exists())throw e;return Collections.emptyList();}
    }
    static void save(File directory,String key,List<InkPage.Stroke> snapshot)throws IOException {
        AtomicFile atomic=new AtomicFile(file(directory,key));FileOutputStream out=null;
        try{out=atomic.startWrite();InkPage.write(out,snapshot);atomic.finishWrite(out);pending.remove(key,snapshot);}
        catch(IOException|RuntimeException e){if(out!=null)atomic.failWrite(out);throw e;}
    }
    static void deleteDocument(File directory,String id){
        for(String key:new ArrayList<>(pending.keySet()))if(key.startsWith(id+"-"))pending.remove(key);
        File[] files=directory.listFiles();if(files==null)return;
        for(File f:files)if(f.getName().startsWith(id+"-")&&(f.getName().endsWith(".ink")||f.getName().endsWith(".ink.bak")||f.getName().endsWith(".ink.new")))f.delete();
    }
}
