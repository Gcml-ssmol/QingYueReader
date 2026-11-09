package cn.qingyue.pdf;
import android.content.Context;
public final class DocumentEditLease implements AutoCloseable {
    public static DocumentEditLease acquire(Context context,String id){return new DocumentEditLease();}
    @Override public void close() { }
}
