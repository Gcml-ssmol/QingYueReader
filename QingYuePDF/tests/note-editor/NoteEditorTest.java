package cn.qingyue.pdf;

import java.io.*;
import java.util.*;

/** Exercises real v3 model and editor operations, without UI mocks. */
public final class NoteEditorTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static NoteDocument.Item ink(NoteDocument d,int color,double x,double y){return new NoteDocument.Item(d.newId(),NoteDocument.STROKE,color,3,new double[]{x,y,x+10,y+10},"");}
    public static void main(String[] args)throws Exception{
        NoteDocument doc=NoteDocument.create("grid","A4");
        NoteDocument.Item marker=ink(doc,0x66336699,30,30);
        NoteDocument.Item pen=ink(doc,0xff221100,300,300);
        NoteDocument.Item shape=new NoteDocument.Item(doc.newId(),NoteDocument.RECTANGLE,0xff222222,2,new double[]{45,45,90,90},"");
        NoteDocument.Item text=new NoteDocument.Item(doc.newId(),NoteDocument.TEXT,0xff333333,18,new double[]{100,100,230,150},"","测试文字");
        NoteDocument.Item image=new NoteDocument.Item(doc.newId(),NoteDocument.IMAGE,0xffffffff,1,new double[]{240,100,340,180},"sample_1.png");
        for(NoteDocument.Item item:Arrays.asList(marker,pen,shape,text,image))doc.add(item);
        List<double[]> box=Arrays.asList(new double[]{20,20},new double[]{350,190});
        Set<Long> all=NoteEditorOps.select(doc.items(),box,false,NoteEditorOps.ALL);
        check(all.size()==4&&!all.contains(pen.id),"rectangle lasso must select intersecting items on one page");
        check(NoteEditorOps.select(doc.items(),box,false,NoteEditorOps.HANDWRITING).equals(Collections.singleton(marker.id)),"handwriting filter must include transparent stroke without guessing brush kind");
        check(NoteEditorOps.select(doc.items(),box,false,NoteEditorOps.TEXT).equals(Collections.singleton(text.id)),"text filter");
        check(NoteEditorOps.select(doc.items(),box,false,NoteEditorOps.IMAGE).equals(Collections.singleton(image.id)),"image filter");
        check(NoteEditorOps.select(doc.items(),box,false,NoteEditorOps.GRAPHICS).equals(Collections.singleton(shape.id)),"graphics filter");
        List<double[]> polygon=Arrays.asList(new double[]{20,20},new double[]{355,20},new double[]{355,190},new double[]{20,190});
        check(NoteEditorOps.select(doc.items(),polygon,true,NoteEditorOps.ALL).equals(all),"free lasso must use polygon geometry");
        NoteDocument.Item diagonal=new NoteDocument.Item(doc.newId(),NoteDocument.STROKE,0xff000000,2,new double[]{0,100,100,0},"");
        NoteDocument.Item arrow=new NoteDocument.Item(doc.newId(),NoteDocument.ARROW,0xff000000,2,new double[]{0,100,100,0},"");
        NoteDocument.Item frame=new NoteDocument.Item(doc.newId(),NoteDocument.RECTANGLE,0xff000000,2,new double[]{0,0,100,100},"");
        NoteDocument.Item circle=new NoteDocument.Item(doc.newId(),NoteDocument.CIRCLE,0xff000000,2,new double[]{0,0,100,100},"");
        List<NoteDocument.Item> outlines=Arrays.asList(diagonal,arrow,frame,circle);
        List<double[]> emptyCorner=Arrays.asList(new double[]{5,5},new double[]{12,5},new double[]{12,12},new double[]{5,12});
        check(NoteEditorOps.select(outlines,emptyCorner,true,NoteEditorOps.ALL).isEmpty(),"free lasso in vector bounding-box corner must not select distant outlines");
        check(NoteEditorOps.select(outlines,Arrays.asList(new double[]{5,5},new double[]{12,12}),false,NoteEditorOps.ALL).isEmpty(),"rectangle lasso in vector bounding-box corner must not select distant outlines");
        check(NoteEditorOps.select(Arrays.asList(frame,circle),Arrays.asList(new double[]{40,40},new double[]{60,60}),false,NoteEditorOps.ALL).isEmpty(),"outline center is empty paper, not a filled shape");
        check(NoteEditorOps.select(Arrays.asList(diagonal,arrow),Arrays.asList(new double[]{45,45},new double[]{55,55}),false,NoteEditorOps.ALL).size()==2,"rectangle lasso intersects actual diagonal strokes");
        check(NoteEditorOps.select(Arrays.asList(frame,circle),Arrays.asList(new double[]{45,-5},new double[]{55,5}),false,NoteEditorOps.ALL).size()==2,"rectangle lasso intersects actual shape outlines");
        NoteDocument.Item recolored=NoteEditorOps.recolored(marker,0xffee5500);
        check((recolored.color>>>24)==0x66&&(recolored.color&0xffffff)==0xee5500,"recolor must preserve marker transparency");
        NoteDocument.Item moved=NoteEditorOps.translated(marker,20,30);
        check(moved.id==marker.id&&moved.points[0]==50&&moved.points[1]==60,"group move must retain id and local coordinates");
        NoteDocument.Item scaled=NoteEditorOps.scaled(text,100,100,1.5);
        check(scaled.width==27&&scaled.right()==295,"text frame and font must scale together");
        ArrayList<NoteDocument.Item> batch=new ArrayList<>(doc.items());batch.set(0,moved);batch.set(3,scaled);
        doc.applyItems(batch);check(doc.items().get(0).left()==50&&doc.items().get(3).right()==295,"batch transform");
        check(doc.undo()&&doc.items().get(0).left()==30&&doc.items().get(3).right()==230,"multi-object transform must be one undo");doc.redo();
        List<NoteDocument.Item> whole=NoteEditorOps.wholeStrokeErase(doc.items(),50,60,60,70,4);
        check(whole.size()<5&&!whole.contains(moved)&&whole.contains(image),"whole-stroke eraser must remove touched ink and retain image");
        List<NoteDocument.Item> partial=NoteDocument.eraseSweep(doc.items(),50,60,60,70,4);
        check(partial.size()>=4,"partial vector eraser remains available");
        doc.applyItems(whole);check(doc.undo()&&doc.items().size()==5,"whole eraser is one undo");
        doc.duplicatePage();check(doc.pageCount()==2&&doc.currentPage()==1&&doc.items().size()==5,"page duplicate");
        check(doc.items().get(4).image.equals(image.image)&&doc.items().get(4).id!=image.id,"duplicate keeps image resource and allocates new id");
        doc.clearPage();check(doc.items().isEmpty()&&doc.undo()&&doc.items().size()==5,"clear page must be undoable");
        doc.deletePage();check(doc.pageCount()==1&&doc.items().get(4).id==image.id,"delete returns to original page");
        try{doc.deletePage();throw new AssertionError("deleted final page");}catch(IllegalStateException expected){}
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();NoteDocument.write(bytes,doc);
        DataInputStream header=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));check(header.readInt()==0x514e4f54&&header.readInt()==3,"wire version must remain v3");
        NoteDocument reopened=NoteDocument.read(new ByteArrayInputStream(bytes.toByteArray()));check(reopened.items().size()==5&&reopened.items().get(4).image.equals("sample_1.png"),"reopen preserves image reference");
        check(ZoomLimits.noteInfiniteScale(10,1)==10&&ZoomLimits.noteInfiniteScale(100,10)==32,"infinite zoom supports 10x but persists <=32");
        check(ZoomLimits.noteScale(10,1)==3,"finite note zoom limit is unchanged");
        System.out.println("note-editor: OK");
    }
}
