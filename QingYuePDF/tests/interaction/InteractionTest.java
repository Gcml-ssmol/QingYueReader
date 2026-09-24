package cn.qingyue.pdf;

import java.io.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.*;

/** JVM state-machine and vector-geometry regression; no Android device rendering is claimed. */
public final class InteractionTest {
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    private static void near(double a,double b,String message){check(Math.abs(a-b)<.00001,message+": "+a+" / "+b);}
    public static void main(String[] args)throws Exception{
        ownership();pinches();pageEdges();resizeAnchors();markerCoverage();
        System.out.println("PASS: pen/palm ownership, post-pen isolation, pointer-ID changes, pinch slop/translation, edge paging and deliberate creation, resize anchors, one-fill alpha coverage and persisted marker colors");
    }
    private static void ownership(){
        TouchNavigation.Guard g=new TouchNavigation.Guard();
        check(g.allow(0,1000,false,true),"ordinary first finger accepted");
        check(!g.allow(5,1020,true,true),"pen arriving after a palm owns sequence");
        check(!g.allow(2,1030,true,true),"mixed pen and palm cannot navigate");
        check(!g.allow(6,1050,true,true),"pen lift does not transfer gesture to palm");
        check(!g.allow(0,1200,false,true),"a newly reported palm immediately after pen is blocked");
        check(!g.allow(2,1500,false,true),"remaining palm remains blocked even after timer");
        check(g.allow(0,1600,false,true),"fresh finger gesture after pen isolation works");
        check(!g.allow(2,1610,false,false),"oversized contact blocks whole sequence");
        check(!g.allow(2,1900,false,true),"shrinking a palm does not revive blocked sequence");
        check(g.allow(0,2000,false,true),"next independent gesture works");
        g.pen(2020);check(!g.allow(0,2050,false,true),"stylus hover suppresses palm before pen down");
        check(TouchNavigation.safeContact(1,20,.1f,1),"finger contact");
        check(!TouchNavigation.safeContact(1,60,.1f,1),"large palm contact");
        check(!TouchNavigation.safeContact(1,20,.6f,1),"normalized palm contact");
        check(!TouchNavigation.safeContact(2,0,0,1),"stylus is never a pinch finger");
        InkInput.Router r=new InkInput.Router();
        check(r.route(5,9,2,true,false)==InkInput.BEGIN,"stylus takes over prior finger");
        check(r.route(5,10,1,true,true)==InkInput.IGNORE,"palm cannot steal active pen");
        check(r.route(2,10,1,true,true)==InkInput.MOVE&&r.activeId==9,"MOVE remains owned by stylus ID");
        check(r.route(6,10,1,true,true)==InkInput.IGNORE,"palm lift leaves pen intact");
        check(r.route(6,9,2,true,true)==InkInput.END,"only active pen lift ends ink");
    }
    private static void pinches(){
        TouchNavigation.Pinch p=new TouchNavigation.Pinch(1);p.begin(4,9,0,0,100,0);
        check(!p.move(4,9,-2,1,103,1,true),"undo candidate never moves viewport");
        check(!p.move(4,9,-2,1,103,1,false),"tiny jitter cannot zoom after tap expires");
        check(!p.move(4,9,20,0,120,0,false),"two-finger pan activation rebases without jump");
        check(p.move(9,4,130,0,30,0,false),"reordered pointer array keeps same pair");
        near(p.scale,1,"parallel two-finger drag does not zoom");near(p.x-p.oldX,10,"translation retains delta");
        check(p.move(4,9,20,0,140,0,false),"pinch can activate after pan");near(p.scale,1,"pinch activation avoids scale jump");
        check(p.move(4,9,10,0,150,0,false),"deliberate pinch continues");near(p.scale,140.0/120,"real pinch ratio");
        p.reset();check(!p.move(4,12,10,0,250,0,false),"replacement pointer rebases");
        check(!p.move(4,12,10,0,251,0,false),"replacement does not inherit previous span");
        p.begin(4,9,0,0,10,0);check(!p.move(4,9,0,0,18,0,false),"near-coincident touches cannot explode scale");
    }
    private static void pageEdges(){
        TouchNavigation.PageDrag d=new TouchNavigation.PageDrag();
        d.add(-150,-150);check(d.turn(-150,0,90)==0,"zoomed content pans before page turns");
        d.add(-50,-20);check(d.turn(-200,0,90)==0,"only unused edge motion counts");
        d.add(-70,0);check(d.turn(-270,0,90)==1,"left/up edge pull goes to next page");
        check(d.turn(-270,260,90)==0,"cross-axis drag cannot flip");
        d.reset();d.add(100,0);check(d.turn(100,0,90)==-1,"right/down goes to previous page");
        d.reset();d.add(-200,0);
        check(d.canAddPage(true,false,1,400,-200,2,90),"deliberate hand-tool edge pull retains creation");
        check(!d.canAddPage(false,false,1,400,-200,2,90),"writing tool finger cannot add pages");
        check(!d.canAddPage(true,true,1,400,-200,2,90),"pen cannot accidentally add pages");
        check(!d.canAddPage(true,false,2,400,-200,2,90),"zoomed drag cannot add pages");
        check(!d.canAddPage(true,false,1,100,-200,2,90),"brief palm fling cannot add pages");
        d.add(10,0);check(d.turn(100,0,90)==0,"reversal clears edge pull");
    }
    private static void resizeAnchors(){
        double initial=3.27,oldHeight=900,scale=800,nextHeight=1000,nextScale=800;
        double next=TouchNavigation.resizedScroll(initial,oldHeight,scale,nextHeight,nextScale);
        near(initial+oldHeight/(2*scale),next+nextHeight/(2*nextScale),"content center survives toolbar resize");
        near(initial,TouchNavigation.resizedScroll(next,nextHeight,nextScale,oldHeight,scale),"resize round trip no drift");
    }
    private static void markerCoverage()throws Exception{
        float[] points=new float[402],pressure=new float[201];
        for(int i=0;i<201;i++){points[i*2]=.2f+i*.003f;points[i*2+1]=.5f;pressure[i]=.6f+(i%9)*.07f;}
        final Path2D.Float path=new Path2D.Float(Path2D.WIND_NON_ZERO);
        int color=InkInput.markerColor(0xffffc300);
        InkPage.Stroke stroke=new InkPage.Stroke(color,.08f,points,InkPage.KIND_FREEHAND,pressure);
        InkPage.outline(stroke,new InkPage.OutlineSink<RuntimeException>(){
            public void move(float x,float y){path.moveTo(x,y);}public void line(float x,float y){path.lineTo(x,y);}
            public void curve(float a,float b,float c,float d,float e,float f){path.curveTo(a,b,c,d,e,f);}public void close(){path.closePath();}
        });
        BufferedImage bitmap=new BufferedImage(500,500,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=bitmap.createGraphics();g.scale(500,500);g.setColor(new Color(color,true));g.fill(path);g.dispose();
        check((bitmap.getRGB(250,250)>>>24)==85,"dense pressure joins apply alpha exactly once");
        check((bitmap.getRGB(250,150)>>>24)==0,"outline does not fill outside stroke");
        ByteArrayOutputStream ink=new ByteArrayOutputStream();InkPage.write(ink,Collections.singletonList(stroke));
        check(InkPage.read(new ByteArrayInputStream(ink.toByteArray())).get(0).color==color,"PDF marker alpha survives storage");
        NoteDocument note=NoteDocument.create("blank","A4");note.add(new NoteDocument.Item(note.newId(),NoteDocument.STROKE,color,18,new double[]{10,20,100,20},""));
        ByteArrayOutputStream data=new ByteArrayOutputStream();NoteDocument.write(data,note);
        check(NoteDocument.read(new ByteArrayInputStream(data.toByteArray())).items().get(0).color==color,"note preview/export/thumbnail item alpha survives storage");
    }
}
