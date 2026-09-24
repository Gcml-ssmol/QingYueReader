package cn.qingyue.pdf;

import android.content.Context;
import java.io.IOException;
import java.util.*;

/** Process-local editor admission. Never holds an I/O/store lock or performs disk I/O. */
public final class DocumentEditLease implements AutoCloseable {
    private static final Object LOCK=new Object();
    private static final Map<String,Integer> editors=new HashMap<>();
    private static final Set<String> mutations=new HashSet<>();
    private static String unavailable="";
    private final String key;
    private boolean closed;
    private DocumentEditLease(String key){this.key=key;}
    private static String key(Context c,String id){return c.getFilesDir().getAbsolutePath()+"/"+id;}
    public static DocumentEditLease acquire(Context context,String documentId)throws IOException {
        if(documentId==null||!documentId.matches("[A-Za-z0-9_-]{1,80}"))throw new IOException("文档编号无效");
        String key=key(context,documentId);
        synchronized(LOCK){
            if(!unavailable.isEmpty())throw new IOException(unavailable);
            if(mutations.contains(key))throw new IOException("文档正在插页，请稍后重试");
            editors.put(key,editors.getOrDefault(key,0)+1);return new DocumentEditLease(key);
        }
    }
    @Override public void close(){synchronized(LOCK){if(closed)return;closed=true;int n=editors.getOrDefault(key,0);if(n<=1)editors.remove(key);else editors.put(key,n-1);}}
    static void unavailable(String message){synchronized(LOCK){unavailable=message==null?"":message;}}
    static Reservation reserve(Context context,String id)throws IOException {
        Reservation result=new Reservation(context);result.add(Collections.singleton(id));return result;
    }
    static final class Reservation implements AutoCloseable {
        private final Context context;private final Set<String> keys=new HashSet<>();
        Reservation(Context context){this.context=context;}
        void add(Collection<String> ids)throws IOException {synchronized(LOCK){
            if(!unavailable.isEmpty())throw new IOException(unavailable);
            for(String id:ids){String k=key(context,id);if(!keys.contains(k)&&(editors.containsKey(k)||mutations.contains(k)))throw new IOException("这份资料或引用它的笔记仍在其他窗口编辑，请先关闭后重试");}
            for(String id:ids){String k=key(context,id);keys.add(k);mutations.add(k);}
        }}
        @Override public void close(){synchronized(LOCK){mutations.removeAll(keys);keys.clear();}}
    }
}
