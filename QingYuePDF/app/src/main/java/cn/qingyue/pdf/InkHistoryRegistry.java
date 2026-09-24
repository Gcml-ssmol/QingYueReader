package cn.qingyue.pdf;

import java.util.*;

/**
 * Process-session ink history independent from PdfRenderer bitmaps and Activity instances.
 * Disk remains the source of truth; this bounded registry only preserves undo/redo state.
 */
final class InkHistoryRegistry {
    private static final int MAX_PAGES=80;
    private static final LinkedHashMap<String,InkPage> pages=new LinkedHashMap<String,InkPage>(32,.75f,true);
    private InkHistoryRegistry(){}
    static synchronized InkPage get(String key){return pages.get(key);}
    static synchronized void put(String key,InkPage page){pages.put(key,page);trim();}
    static synchronized void touch(String key){pages.get(key);}
    static synchronized void removeDocument(String id){Iterator<String> iterator=pages.keySet().iterator();while(iterator.hasNext())if(iterator.next().startsWith(id+"-"))iterator.remove();}
    private static void trim(){
        while(pages.size()>MAX_PAGES){String fallback=null;Iterator<Map.Entry<String,InkPage>> iterator=pages.entrySet().iterator();while(iterator.hasNext()){
            Map.Entry<String,InkPage> entry=iterator.next();if(fallback==null)fallback=entry.getKey();
            if(!entry.getValue().canUndo()&&!entry.getValue().canRedo()){iterator.remove();fallback=null;break;}
        }if(fallback!=null)pages.remove(fallback);}
    }
}
