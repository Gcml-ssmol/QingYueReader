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
        System.out.println("PASS: persistence, immutable snapshots, undo/redo, clear/erase recovery, swept eraser, zoom coordinates, pen-only classification, pen/palm event routing, cancellation, corrupt data rejection, page isolation");
    }
}
