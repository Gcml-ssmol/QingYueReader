package cn.qingyue.pdf;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Transient text-editor state. Page tokens track moves while the document stays in memory. */
final class NoteTextDraft {
    String text;
    String size;
    int color;
    String pageToken;
    int pageIndex;
    long itemId;
    String pageStructure;
    boolean restoreAfterRotation;

    NoteTextDraft(String text, String size, int color, String pageToken, int pageIndex,
                  long itemId, String pageStructure, boolean restoreAfterRotation) {
        this.text=text;this.size=size;this.color=color;this.pageToken=pageToken;
        this.pageIndex=pageIndex;this.itemId=itemId;this.pageStructure=pageStructure;
        this.restoreAfterRotation=restoreAfterRotation;
    }

    static String structure(NoteDocument document) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(int page=0;page<document.pageCount();page++) {
                digest.update((byte)0xff);
                for(NoteDocument.Item item:document.pageItems(page)) {
                    long id=item.id;
                    for(int shift=56;shift>=0;shift-=8)digest.update((byte)(id>>>shift));
                }
            }
            char[] hex="0123456789abcdef".toCharArray();StringBuilder result=new StringBuilder(64);
            for(byte value:digest.digest()){result.append(hex[(value>>>4)&15]).append(hex[value&15]);}
            return result.toString();
        }catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }

    int resolvePage(NoteDocument document) {
        int page=document.pageIndex(pageToken);
        if(page<0 && restoreAfterRotation && pageIndex>=0 && pageIndex<document.pageCount()
                && pageStructure.equals(structure(document)))page=pageIndex;
        if(page<0)return -1;
        if(itemId==0)return page;
        for(int index=0;index<document.pageCount();index++)
            for(NoteDocument.Item item:document.pageItems(index))
                if(item.id==itemId)return item.type==NoteDocument.TEXT?index:-1;
        return -1;
    }
}

/** Keeps delayed dismiss callbacks from older dialogs from clearing a resumed draft. */
final class NoteTextDialogLifecycle {
    static final class Session { private boolean lifecycleClose; }
    private Session active;

    Session open(){Session session=new Session();active=session;return session;}
    boolean isActive(Session session){return active==session;}
    void pause(Session session){if(active==session){session.lifecycleClose=true;active=null;}}
    boolean cancel(Session session){if(active!=session)return false;active=null;return true;}
    boolean dismissed(Session session){
        if(active!=session)return false;
        active=null;
        return !session.lifecycleClose;
    }
}
