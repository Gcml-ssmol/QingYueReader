package cn.qingyue.pdf;

import android.content.Context;
import java.io.*;
import java.nio.file.Files;

/** Real NoteStore with the existing desktop Context/AtomicFile shims. */
public final class NoteStoreImageTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Context context=new Context(Files.createTempDirectory("qingyue-editor-store-").toFile());
        NoteDocument first=NoteStore.create(context,"first","blank","A4");
        NoteStore.create(context,"second","blank","A4");
        File image=new File(NoteStore.imagesDirectory(context,"first"),"shared_1.png");
        try(FileOutputStream out=new FileOutputStream(image)){out.write(new byte[]{1,2,3});}
        check(NoteStore.imageExists(context,"first","shared_1.png"),"clipboard may reuse an image owned by its note");
        check(!NoteStore.imageExists(context,"second","shared_1.png"),"clipboard must not borrow another note's image");
        check(!NoteStore.imageExists(context,"first","../shared_1.png"),"image lookup must reject traversal");
        first.add(new NoteDocument.Item(first.newId(),NoteDocument.IMAGE,0xffffffff,1,new double[]{20,20,120,90},"shared_1.png"));
        first.duplicatePage();NoteStore.save(context,"first",first);
        NoteDocument reopened=NoteStore.load(context,"first");
        check(reopened.pageCount()==2&&reopened.pageItems(0).get(0).id!=reopened.pageItems(1).get(0).id,"saved duplicate pages keep separate item IDs");
        check(reopened.pageItems(0).get(0).image.equals(reopened.pageItems(1).get(0).image)&&NoteStore.imageExists(context,"first","shared_1.png"),"duplicated image reference survives save/reopen");
        for(int page=0;page<reopened.pageCount();page++)check(BasicNoteExportBounds.forItems(reopened,reopened.pageItems(page)).width>0,"export bounds accept duplicated image page");
        NoteStore.delete(context,"first");check(!NoteStore.imageExists(context,"first","shared_1.png"),"deleted note cannot own clipboard images");
        System.out.println("note-editor store/image: OK");
    }
}
