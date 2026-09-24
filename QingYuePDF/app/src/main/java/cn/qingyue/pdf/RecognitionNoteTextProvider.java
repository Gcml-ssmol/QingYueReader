package cn.qingyue.pdf;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Indexes text boxes by note page, including page order in the fingerprint. */
public final class RecognitionNoteTextProvider implements GlobalSearchContentProvider {
    @Override public String providerId(){return "note-text-main-v3";}
    @Override public String channel(){return GlobalSearchSource.CHANNEL_NOTE_TEXT;}
    @Override public boolean supports(GlobalSearchLibrarySource.LibraryDocument d){return d!=null&&d.isNote();}
    @Override public String fingerprint(Context c,GlobalSearchLibrarySource.LibraryDocument d,
            GlobalSearchSource.Cancellation token)throws IOException {
        return text(c,d,token).fingerprint;
    }
    @Override public void read(Context c,GlobalSearchLibrarySource.LibraryDocument d,Sink sink,
            GlobalSearchSource.Cancellation token)throws IOException {
        TextSnapshot value=text(c,d,token);
        for(int page=0;page<value.pages.size();page++){
            if(token!=null&&token.isCancelled())throw new InterruptedIOException("搜索索引已取消");
            String body=value.pages.get(page);
            if(!body.isEmpty())sink.accept(new Text(page,"文本框",body,
                    GlobalSearchSource.JUMP_NOTE_PAGE,""));
        }
    }
    private static TextSnapshot text(Context c,GlobalSearchLibrarySource.LibraryDocument d,
            GlobalSearchSource.Cancellation token)throws IOException {
        if(token!=null&&token.isCancelled())throw new InterruptedIOException("搜索索引已取消");
        NoteDocument note=NoteStore.load(c,d.id);
        ArrayList<String> pages=new ArrayList<>(note.pageCount());
        int total=0;
        for(int page=0;page<note.pageCount();page++){
            StringBuilder out=new StringBuilder();
            for(NoteDocument.Item item:note.pageItems(page)){
                if(token!=null&&token.isCancelled())throw new InterruptedIOException("搜索索引已取消");
                if(item.type==NoteDocument.TEXT){
                    if(total+item.text.length()+(out.length()>0?1:0)>2_000_000)
                        throw new IOException("笔记文本超过索引上限");
                    if(out.length()>0){out.append('\n');total++;}
                    out.append(item.text);total+=item.text.length();
                }
            }
            pages.add(out.toString());
        }
        if(total==0)return new TextSnapshot(pages,"");
        return new TextSnapshot(pages,digest(pages));
    }
    private static String digest(List<String> pages)throws IOException {
        try {MessageDigest md=MessageDigest.getInstance("SHA-256");
            updateInt(md,pages.size());
            for(String page:pages){byte[] bytes=page.getBytes(StandardCharsets.UTF_8);updateInt(md,bytes.length);md.update(bytes);}
            byte[] hash=md.digest();
            StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format("%02x",b&255));return out.toString();
        }catch(NoSuchAlgorithmException e){throw new IOException(e);}
    }
    private static void updateInt(MessageDigest md,int value){
        md.update((byte)(value>>>24));md.update((byte)(value>>>16));
        md.update((byte)(value>>>8));md.update((byte)value);
    }
    private static final class TextSnapshot {
        final List<String> pages;
        final String fingerprint;
        TextSnapshot(List<String> pages,String fingerprint){this.pages=pages;this.fingerprint=fingerprint;}
    }
}
