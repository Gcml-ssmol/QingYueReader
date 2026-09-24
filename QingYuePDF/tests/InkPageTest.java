package cn.qingyue.pdf;

import java.io.*;
import java.util.*;

public class InkPageTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void invalid(byte[] data)throws Exception{try{InkPage.read(new ByteArrayInputStream(data));throw new AssertionError("Invalid data accepted");}catch(IOException expected){}}
    public static void main(String[] args)throws Exception {
        InkPage.Stroke diagonal=new InkPage.Stroke(0xffd93838,0.006f,new float[]{0.1f,0.1f,0.9f,0.9f});
        InkPage.Stroke dot=new InkPage.Stroke(0x552464d6,0.03f,new float[]{0.8f,1.2f});
        InkPage page=new InkPage(Collections.emptyList());page.add(diagonal);page.add(dot);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();InkPage.write(bytes,page.strokes());
        DataInputStream legacyHeader=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));legacyHeader.readInt();check(legacyHeader.readInt()==1,"Legacy-only data must stay version 1");
        List<InkPage.Stroke> restored=InkPage.read(new ByteArrayInputStream(bytes.toByteArray()));
        check(restored.size()==2&&restored.get(1).color==dot.color&&restored.get(1).width==dot.width&&Arrays.equals(restored.get(0).points,diagonal.points),"Persistence round trip failed");
        page.replace(Collections.singletonList(dot));check(page.undo()&&page.strokes().size()==2,"Erase undo failed");
        check(page.redo()&&page.strokes().size()==1,"Erase redo failed");
        page.replace(Collections.emptyList());check(page.undo()&&page.strokes().size()==1,"Clear undo failed");
        page.add(diagonal);check(!page.canRedo(),"New drawing must discard redo history");
        List<InkPage.Stroke> before=page.strokes();page.add(dot);check(before.size()==2,"Snapshot mutated after edit");
        check(InkPage.hit(diagonal,0.5f,0.1f,0.5f,0.9f,0.01f),"Fast eraser crossing missed");
        check(!InkPage.hit(diagonal,0.01f,0.8f,0.02f,0.9f,0.01f),"Unrelated stroke erased");
        check(InkPage.hit(dot,0.79f,1.2f,0.79f,1.2f,0.01f),"Dot eraser missed");
        check(Math.abs(InkPage.toPage(500,100,800)-0.5f)<0.00001,"Zoom/pan inverse failed");
        check(InkPage.toPage(300,100,400)==InkPage.toPage(700,100,1200),"Ink moved under zoom");
        check(InkInput.isPen(2)&&InkInput.isPen(4),"Stylus/eraser rejected");
        check(!InkInput.isPen(0)&&!InkInput.isPen(1)&&!InkInput.isPen(3),"Finger, mouse or unknown tool admitted as pen");
        check(!InkInput.canTouchDraw(true)&&InkInput.canTouchDraw(false),"Pen-only mode must block finger strokes");
        InkInput.PressureSamples samples=new InkInput.PressureSamples();samples.begin(.7f);
        check(samples.move(.4f)==.4f&&samples.release(0)==.4f,"Lift-off zero pressure changed last segment");
        check(samples.release(Float.NaN)==.4f&&samples.release(Float.POSITIVE_INFINITY)==.4f,"Invalid lift-off pressure changed last segment");
        samples.begin(.65f);check(samples.release(0)==.65f,"Single-point lift-off changed down pressure");
        samples.begin(.6f);check(samples.move(.9f)==.9f&&samples.release(.8f)==.8f,"Valid movement or release pressure lost");
        InkInput.Router router=new InkInput.Router();
        check(router.route(0,11,1,true,false)==InkInput.TOUCH&&router.activeId==-1,"Finger became active pen");
        check(router.route(5,22,2,true,false)==InkInput.BEGIN&&router.activeId==22,"Pen following palm did not begin");
        check(router.route(5,33,1,true,true)==InkInput.IGNORE&&router.activeId==22,"Palm contact interrupted pen");
        check(router.route(2,11,1,true,true)==InkInput.MOVE&&router.activeId==22,"Pointer reorder lost pen identity");
        check(router.route(6,11,1,true,true)==InkInput.IGNORE&&router.activeId==22,"Palm lift ended pen stroke");
        check(router.route(6,22,2,true,true)==InkInput.END&&router.activeId==-1,"Pen lift did not commit");
        check(router.route(0,4,4,true,false)==InkInput.BEGIN,"Eraser tip did not begin");
        check(router.route(3,4,4,true,true)==InkInput.CANCEL&&router.activeId==-1,"Cancelled stroke retained pen");
        check(router.route(0,22,2,false,false)==InkInput.TOUCH,"Read mode started drawing");
        router.route(0,22,2,true,false);check(router.route(2,11,1,true,false)==InkInput.CANCEL,"Missing pen did not cancel");
        invalid(new byte[0]);invalid(Arrays.copyOf(bytes.toByteArray(),bytes.size()-1));
        byte[] bad=bytes.toByteArray();bad[0]=0;invalid(bad);
        ByteArrayOutputStream corrupt=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(corrupt);
        out.writeInt(0x51494e4b);out.writeInt(1);out.writeInt(1);out.writeInt(0xff000000);out.writeFloat(0.01f);out.writeInt(Integer.MAX_VALUE);invalid(corrupt.toByteArray());
        try{new InkPage.Stroke(1,Float.NaN,new float[]{0,0});throw new AssertionError("NaN accepted");}catch(IllegalArgumentException expected){}
        InkPage secondPage=new InkPage(Collections.emptyList());check(secondPage.strokes().isEmpty(),"Notes leaked to another page");
        InkPage.Stroke pressure=new InkPage.Stroke(0xff123456,.01f,new float[]{.1f,.5f,.9f,.5f},InkPage.KIND_FREEHAND,new float[]{.4f,.4f});
        InkPage.Stroke rectangle=InkPage.shape(InkPage.KIND_RECTANGLE,0xff000000,.005f,.2f,.2f,.6f,.7f);
        ByteArrayOutputStream extendedBytes=new ByteArrayOutputStream();InkPage.write(extendedBytes,Arrays.asList(pressure,rectangle));
        DataInputStream extendedHeader=new DataInputStream(new ByteArrayInputStream(extendedBytes.toByteArray()));extendedHeader.readInt();check(extendedHeader.readInt()==2,"Pressure/shape data must use version 2");
        List<InkPage.Stroke> extended=InkPage.read(new ByteArrayInputStream(extendedBytes.toByteArray()));
        check(extended.get(0).hasPressure()&&Math.abs(extended.get(0).pressures()[0]-.4f)<.0001f,"Constant pressure was discarded");
        check(extended.get(1).kind==InkPage.KIND_RECTANGLE,"Shape kind round trip failed");
        InkPage local=new InkPage(Collections.singletonList(new InkPage.Stroke(0xff000000,.01f,new float[]{.1f,.5f,.9f,.5f})));
        check(local.erasePartial(.49f,.3f,.51f,.7f,.02f),"Local eraser did not report change");
        check(local.strokes().size()==2,"Sparse crossing stroke was not split by local eraser");
        check(local.undo()&&local.strokes().size()==1,"Local erase was not undoable");
        InkPage tiny=new InkPage(Collections.singletonList(new InkPage.Stroke(0x66224488,.001f,new float[]{0f,.5f,1f,.5f})));
        check(tiny.erasePartial(.5001f,.499f,.5001f,.501f,.0002f)&&tiny.strokes().size()==2,"Tiny eraser missed long line");
        check(tiny.strokes().get(0).points().length==4&&tiny.strokes().get(1).points().length==4,"Long line was oversampled");
        check(tiny.erasePartial(.75f,.1f,.75f,.9f,.0002f)&&tiny.strokes().size()==3,"Second eraser pass did not split retained fragment");
        InkPage fastCross=new InkPage(Collections.singletonList(new InkPage.Stroke(0xff123456,.001f,new float[]{.1f,.1f,.9f,.9f})));
        check(fastCross.erasePartial(.1f,.9f,.9f,.1f,.0002f)&&fastCross.strokes().size()==2,"Fast diagonal crossing missed");
        InkPage arrowErase=new InkPage(Collections.singletonList(InkPage.shape(InkPage.KIND_ARROW,0x55336699,.004f,.1f,.5f,.8f,.5f)));
        check(arrowErase.erasePartial(.45f,.49f,.45f,.51f,.002f),"Arrow shaft erase missed");
        for(InkPage.Stroke piece:arrowErase.strokes())check(piece.color==0x55336699&&piece.points().length==4,"Arrow fragment lost alpha or retraced a head");
        InkPage fadedEllipse=new InkPage(Collections.singletonList(InkPage.shape(InkPage.KIND_ELLIPSE,0x55224488,.003f,.1f,.1f,.9f,.9f)));
        check(fadedEllipse.erasePartial(.08f,.5f,.12f,.5f,.002f)&&fadedEllipse.strokes().size()==1&&
                fadedEllipse.strokes().get(0).color==0x55224488,"Ellipse seam duplicated translucent ink");
        InkPage edits=new InkPage(Arrays.asList(diagonal,dot));List<Integer> selected=edits.select(new float[]{.05f,.05f,.55f,.05f,.55f,.55f,.05f,.55f});
        check(selected.size()==1&&selected.get(0)==0,"Lasso selection failed");
        check(edits.transform(selected,.5f,.5f,1,.05f,0),"Lasso move failed");int beforeCopy=edits.strokes().size();
        check(edits.copy(selected,.02f,.02f).size()==1&&edits.strokes().size()==beforeCopy+1,"Lasso copy failed");
        check(InkPage.shape(InkPage.KIND_ARROW,0xff000000,.01f,1f,0f,.99f,.001f).points().length==10,"Edge arrow was rejected");
        System.out.println("PASS: v1 compatibility, v2 pressure/shapes, immutable snapshots, undo/redo, clear recovery, whole/local erase, lasso move/copy, zoom coordinates, pen-only routing, cancellation, corrupt rejection, page isolation");
    }
}
