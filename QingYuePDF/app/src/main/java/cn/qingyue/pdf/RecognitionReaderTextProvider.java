package cn.qingyue.pdf;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;

/** Searches user-created PDF reader text boxes, including pending ReaderStore edits. */
public final class RecognitionReaderTextProvider implements GlobalSearchContentProvider {
    @Override public String providerId(){return "reader-text-main-v1";}
    @Override public String channel(){return "reader_text";}
    @Override public boolean supports(GlobalSearchLibrarySource.LibraryDocument d){return d!=null&&!d.isNote();}
    @Override public String fingerprint(Context c,GlobalSearchLibrarySource.LibraryDocument d,
            GlobalSearchSource.Cancellation token)throws IOException {
        ReaderDocument reader=ReaderStore.load(c.getFilesDir(),d.id);
        MessageDigest digest;
        try{digest=MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException e){throw new IOException(e);}
        boolean found=false;
        for(ReaderDocument.TextBox box:reader.textBoxes()){
            if(token!=null&&token.isCancelled())throw new InterruptedIOException("搜索索引已取消");
            if(box.page>=d.pages)continue;
            found=true;
            digest.update((byte)(box.page>>>24));digest.update((byte)(box.page>>>16));
            digest.update((byte)(box.page>>>8));digest.update((byte)box.page);
            digest.update(box.id.getBytes(StandardCharsets.UTF_8));
            digest.update(box.text.getBytes(StandardCharsets.UTF_8));
        }
        if(!found)return "";
        StringBuilder out=new StringBuilder();
        for(byte value:digest.digest())out.append(String.format("%02x",value&255));
        return out.toString();
    }
    @Override public void read(Context c,GlobalSearchLibrarySource.LibraryDocument d,Sink sink,
            GlobalSearchSource.Cancellation token)throws IOException {
        ReaderDocument reader=ReaderStore.load(c.getFilesDir(),d.id);
        for(ReaderDocument.TextBox box:reader.textBoxes()){
            if(token!=null&&token.isCancelled())throw new InterruptedIOException("搜索索引已取消");
            if(box.page<d.pages)sink.accept(new Text(box.page,"阅读器文本框",box.text,
                    GlobalSearchSource.JUMP_PDF_PAGE,box.id));
        }
    }
}
