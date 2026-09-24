package cn.qingyue.pdf;

import android.graphics.*;
import java.util.*;

/** Production compositor/plan checks with path-call counters, not device frame-rate measurements. */
public final class RenderPerformanceTest {
    private static final Paint paint=new Paint();private static final Canvas canvas=new Canvas();
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static InkPage.Stroke stroke(int points,float y,boolean pressure){
        float[] vertices=new float[points*2],pressures=pressure?new float[points]:null;
        for(int i=0;i<points;i++){vertices[i*2]=.1f+.8f*i/Math.max(1,points-1);vertices[i*2+1]=y;if(pressure)pressures[i]=.6f+i%6*.1f;}
        return new InkPage.Stroke(0x55ffcc00,.01f,vertices,0,pressures);
    }
    public static void main(String[] args){
        repeatedDrawing();overflow();boundsAndEdits();notePrecision();tilePriority();
        System.out.println("PASS: bounded frame-stable cache, geometry identity/edit/undo, alpha/draw equivalence, viewport culling, far-coordinate note precision, visible-tile priority. Desktop counters only; no device FPS claim.");
    }
    private static void repeatedDrawing(){
        List<InkPage.Stroke> strokes=new ArrayList<>();for(int n=0;n<160;n++)strokes.add(stroke(201,.1f+n*.007f,true));
        Path.created=Path.verbs=0;
        for(int frame=0;frame<100;frame++)for(InkPage.Stroke s:strokes)InkStrokeRenderer.draw(canvas,paint,s);
        long oldBuilds=Path.created,oldVerbs=Path.verbs;Path.created=Path.verbs=0;
        InkStrokeRenderer.Cache cache=new InkStrokeRenderer.Cache();
        for(int frame=0;frame<100;frame++){cache.beginFrame();for(InkPage.Stroke s:strokes)cache.draw(canvas,paint,s);}
        check(oldBuilds==16000&&Path.created==160,"steady-page path reconstruction count");
        check(Path.verbs*100==oldVerbs,"cached path no longer has equivalent construction geometry");
        check(canvas.color==0x55ffcc00&&canvas.style==Paint.Style.FILL,"marker alpha/single fill changed");
        System.out.println("160 pressure strokes x 201 points x 100 frames: Path builds "+oldBuilds+" -> "+Path.created+", path verbs "+oldVerbs+" -> "+Path.verbs);
        InkStrokeRenderer.draw(canvas,paint,strokes.get(10));long expected=canvas.path.fingerprint;
        cache.draw(canvas,paint,strokes.get(10));check(canvas.path.fingerprint==expected,"cached pressure geometry differs from export compositor");
    }
    private static void overflow(){
        List<InkPage.Stroke> strokes=new ArrayList<>();for(int n=0;n<400;n++)strokes.add(stroke(12,.1f+n*.003f,false));
        InkStrokeRenderer.Cache cache=new InkStrokeRenderer.Cache();
        for(int frame=0;frame<60;frame++){cache.beginFrame();for(InkPage.Stroke s:strokes)cache.draw(canvas,paint,s);}
        long expected=400+59L*(400-256);check(cache.builds()==expected,"sequential overflow thrashed retained paths: "+cache.builds());
        check(cache.size()<=256&&cache.retainedPoints()<=60000,"cache limit exceeded");
        System.out.println("400 short strokes (over 256-entry cap) x 60 frames: Path builds 24000 -> "+cache.builds());
        InkStrokeRenderer.Cache weighted=new InkStrokeRenderer.Cache(1000,256);List<InkPage.Stroke> longStrokes=new ArrayList<>();for(int n=0;n<5;n++)longStrokes.add(stroke(300,.2f,false));
        for(int f=0;f<30;f++){weighted.beginFrame();for(InkPage.Stroke s:longStrokes)weighted.draw(canvas,paint,s);check(weighted.retainedPoints()<=1000,"point budget exceeded");}
        check(weighted.builds()==5+29*2,"point-budget overflow thrashed");
        int retained=weighted.retainedPoints();weighted.draw(canvas,paint,stroke(2000,.2f,true));check(weighted.retainedPoints()==retained,"oversized stroke evicted whole cache");
        weighted.beginFrame();InkPage.Stroke newPage=stroke(100,.4f,false);weighted.draw(canvas,paint,newPage);long builds=weighted.builds();weighted.beginFrame();weighted.draw(canvas,paint,newPage);check(weighted.builds()==builds,"new page cannot enter cache");
        weighted.clear();check(weighted.size()==0&&weighted.retainedPoints()==0,"dispose cache references retained");
    }
    private static void boundsAndEdits(){
        InkPage.Stroke visible=stroke(20,.5f,true),offscreen=stroke(20,3,true);
        InkPage ink=new InkPage(Arrays.asList(visible,offscreen));InkStrokeRenderer.Page page=new InkStrokeRenderer.Page();InkStrokeRenderer.Cache cache=new InkStrokeRenderer.Cache();
        canvas.draws=0;cache.beginFrame();page.draw(canvas,paint,cache,ink.strokes(),0,0,1,1);check(canvas.draws==1&&cache.builds()==1,"offscreen geometry built/drawn");
        long before=canvas.draws;page.draw(canvas,paint,cache,ink.strokes(),0,2.9f,1,3.1f);check(canvas.draws==before+1,"panned viewport does not reveal ink");
        InkPage.Stroke edited=new InkPage.Stroke(0xff123456,.02f,new float[]{.2f,.7f,.8f,.7f});ink.replace(Collections.singletonList(edited));
        cache.beginFrame();page.draw(canvas,paint,cache,ink.strokes(),0,0,1,1);check(canvas.color==edited.color&&paint.width==.02f,"edit reused stale style/geometry");
        ink.undo();cache.beginFrame();page.draw(canvas,paint,cache,ink.strokes(),0,0,1,1);check(canvas.color==visible.color,"undo bounds/snapshot not refreshed");
        ink.clear();before=canvas.draws;page.draw(canvas,paint,cache,ink.strokes(),0,0,1,1);check(canvas.draws==before,"deleted geometry drawn");
        // A maximum-pressure edge still intersects, even when the center line is just outside.
        page.draw(canvas,paint,cache,Collections.singletonList(visible),0,.506f,1,.51f);check(canvas.draws==before+1,"pressure radius lost at clip edge");
        ArrayList<InkPage.Stroke> erasing=new ArrayList<>(Arrays.asList(offscreen,visible));page.draw(canvas,paint,cache,erasing,0,0,1,1);
        erasing.remove(0);before=canvas.draws;page.draw(canvas,paint,cache,erasing,0,0,1,1);check(canvas.draws==before+1,"whole-stroke eraser shifted stale bounds onto remaining stroke");
    }
    private static void notePrecision(){
        double[] points={1e12, -1e12,1e12+.125,-1e12+.25,1e12+.375,-1e12+.5};
        Object item=new Object();InkStrokeRenderer.Cache cache=new InkStrokeRenderer.Cache();
        for(double zoom:new double[]{.5,1,3,20}){
            cache.beginFrame();cache.drawNote(canvas,paint,item,points,0x55ffc300,8);
            double cx=1e12+.0625,cy=-1e12+.125;
            float translateX=(float)(500+(points[0]-cx)*zoom),translateY=(float)(400+(points[1]-cy)*zoom);
            float actualX=translateX+canvas.path.x*(float)zoom,actualY=translateY+canvas.path.y*(float)zoom;
            float expectedX=(float)(500+(points[4]-cx)*zoom),expectedY=(float)(400+(points[5]-cy)*zoom);
            check(Math.abs(actualX-expectedX)<.0001&&Math.abs(actualY-expectedY)<.0001,"far-world note path lost subpoint precision at "+zoom);
        }
        check(cache.builds()==1,"zoom rebuilt world-local note geometry");
        cache.drawNote(canvas,paint,new Object(),new double[]{1e12,-1e12,1e12+.75,-1e12+.5},0xff123456,4);
        check(canvas.path.x==.75f&&canvas.color==0xff123456,"replaced note item reused old path/color");
    }
    private static boolean intersects(PdfRenderPlan.Tile tile){return tile.pageRight()>.43f&&tile.pageLeft()<.57f&&tile.pageBottom()>.63f&&tile.pageTop()<.77f;}
    private static void tilePriority(){
        List<PdfRenderPlan.Tile> ordered=PdfRenderPlan.visible(0,4096,1.4142f,.43f,.63f,.57f,.77f);
        List<PdfRenderPlan.Tile> legacy=new ArrayList<>(ordered);Collections.sort(legacy,(a,b)->a.top!=b.top?Integer.compare(a.top,b.top):Integer.compare(a.left,b.left));
        int oldVisible=0,newVisible=0;for(int i=0;i<4;i++){if(intersects(legacy.get(i)))oldVisible++;if(intersects(ordered.get(i)))newVisible++;}
        check(newVisible==4&&oldVisible<newVisible,"first render batch still prioritizes offscreen margin");
        boolean margin=false;for(PdfRenderPlan.Tile tile:ordered){if(!intersects(tile))margin=true;else check(!margin,"visible tile after prefetch margin");}
        System.out.println("First four detail tiles intersecting viewport: "+oldVisible+" -> "+newVisible+"; total planned tiles unchanged="+ordered.size());
    }
}
