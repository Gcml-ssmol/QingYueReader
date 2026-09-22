package cn.qingyue.pdf;

import java.io.*;
import java.util.*;

/** Run with javac/java: real model, no Android mocks required. */
public final class NoteDocumentTest {
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    private static void reject(Runnable r,String message){try{r.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){}}
    public static void main(String[] args)throws Exception{
        for(String template:new String[]{"blank","ruled","grid"})for(String size:new String[]{"A3","A4","A5","B4","B5","Letter","infinite"}){NoteDocument d=NoteDocument.create(template,size);check(d.infinite()==size.equals("infinite"),"paper size");if(!d.infinite())check(d.width>400&&d.height>d.width,"portrait paper geometry");ByteArrayOutputStream out=new ByteArrayOutputStream();NoteDocument.write(out,d);NoteDocument restored=NoteDocument.read(new ByteArrayInputStream(out.toByteArray()));check(restored.template.equals(template)&&restored.size.equals(size),"paper roundtrip");}
        NoteDocument d=NoteDocument.create("grid","infinite");d.centerX=-8000000123.125;d.centerY=14000000456.75;d.zoom=.5;d.viewportSaved=true;
        double[] points={-9000000000.5,18000000000.25,-8999999800.5,18000000100.25};
        NoteDocument.Item stroke=new NoteDocument.Item(d.newId(),NoteDocument.STROKE,0xff112233,3,points,"");d.add(stroke);points[0]=7;check(d.items().get(0).points[0]==-9000000000.5,"constructor copies coordinates");check(stroke.near(-8999999900.5,18000000050.25,1),"large negative coordinate segment hit");check(!stroke.near(0,0,1),"distant segment missed");
        NoteDocument.Item image=new NoteDocument.Item(d.newId(),NoteDocument.IMAGE,0xffffffff,1,new double[]{100,100,300,200},"image_1.jpg");d.add(image);d.replace(image.bounds(-500,-700,400,200));check(d.find(image.id).left()==-500,"image moves and resizes");check(d.undo()&&d.find(image.id).left()==100,"undo image transform");check(d.redo()&&d.find(image.id).left()==-500,"redo image transform");
        d.remove(image.id);check(d.find(image.id)==null,"image deletion");d.undo();check(d.find(image.id)!=null,"undo image deletion");d.undo();d.add(new NoteDocument.Item(d.newId(),NoteDocument.LINE,0xff000000,2,new double[]{0,0,10,10},""));check(!d.canRedo(),"new action clears redo");
        for(int type:new int[]{NoteDocument.CIRCLE,NoteDocument.RECTANGLE,NoteDocument.ARROW})d.add(new NoteDocument.Item(d.newId(),type,0xff123456,2,new double[]{10,10,110,110},""));
        NoteDocument.Item arrow=new NoteDocument.Item(42,NoteDocument.ARROW,0xff000000,2,new double[]{0,0,100,0},"");double[] heads=NoteDocument.arrowHeads(arrow.points,arrow.width);check(arrow.near(heads[0],heads[1],.2),"arrow head outside endpoint bounds erases");check(arrow.top()<0&&arrow.bottom()>0,"arrow head included in viewport culling bounds");
        NoteDocument.Item circle=new NoteDocument.Item(40,NoteDocument.CIRCLE,0xff000000,2,new double[]{0,0,100,100},"");check(circle.near(100,50,.1),"circle perimeter hit");check(!circle.near(50,50,.1),"circle center is not ink");NoteDocument.Item rectangle=new NoteDocument.Item(41,NoteDocument.RECTANGLE,0xff000000,2,new double[]{100,100,0,0},"");check(rectangle.near(0,50,.1),"reverse rectangle geometry");check(!rectangle.near(50,50,.1),"rectangle center is not ink");
        ByteArrayOutputStream encoded=new ByteArrayOutputStream();NoteDocument.write(encoded,d);NoteDocument restored=NoteDocument.read(new ByteArrayInputStream(encoded.toByteArray()));check(restored.items().size()==d.items().size(),"all item types preserved");check(restored.centerX==d.centerX&&restored.centerY==d.centerY&&restored.zoom==d.zoom&&restored.viewportSaved,"viewport double precision persists");check(restored.items().get(0).points[0]==-9000000000.5,"infinite canvas coordinate persistence");long max=0;for(NoteDocument.Item item:restored.items())max=Math.max(max,item.id);check(restored.newId()>max,"new ids after reopen");
        byte[] truncated=Arrays.copyOf(encoded.toByteArray(),encoded.size()-1);try{NoteDocument.read(new ByteArrayInputStream(truncated));throw new AssertionError("truncation accepted");}catch(IOException expected){}
        byte[] trailing=Arrays.copyOf(encoded.toByteArray(),encoded.size()+1);try{NoteDocument.read(new ByteArrayInputStream(trailing));throw new AssertionError("trailing bytes accepted");}catch(IOException expected){}
        reject(()->new NoteDocument.Item(1,NoteDocument.IMAGE,0,1,new double[]{0,0,1,1},"../secret.jpg"),"image path traversal accepted");reject(()->new NoteDocument.Item(1,NoteDocument.STROKE,0,1,new double[]{Double.NaN,0},""),"NaN accepted");reject(()->NoteDocument.create("unknown","A4"),"unknown paper accepted");
        NoteDocument snapshot=d.copy();d.remove(d.items().get(0).id);check(snapshot.items().size()==d.items().size()+1,"snapshot independent from undo/edit lists");
        System.out.println("NoteDocumentTest passed: 21 paper/template combinations, two-axis coordinates, vector shapes, image geometry, undo/redo, serialization and corrupt data rejection.");
    }
}
