package cn.qingyue.pdf;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import java.util.*;

/** Whole-stroke compositor: geometry may be reused, but alpha is applied only once. */
public final class InkStrokeRenderer {
    private InkStrokeRenderer() { }
    private static final class Geometry {
        final Path path;final boolean fill;final int points;final float dotX,dotY;long frame;
        Geometry(Path path,boolean fill,int points,float x,float y){this.path=path;this.fill=fill;this.points=points;dotX=x;dotY=y;}
    }
    /** UI-thread cache keyed by immutable stroke/item identity, never by mutable page number. */
    public static final class Cache {
        private final int maxPoints,maxEntries;
        private final LinkedHashMap<Object,Geometry> entries=new LinkedHashMap<>(32,.75f,true);
        private int retainedPoints;private long builds,frame;
        public Cache(){this(60000,256);}
        Cache(int maxPoints,int maxEntries){this.maxPoints=maxPoints;this.maxEntries=maxEntries;}
        /** Pin geometry already used in this frame so sequential overflow cannot evict every hit. */
        public void beginFrame(){frame++;}
        private Geometry remember(Object key,Geometry geometry){
            builds++;
            if(key==null||geometry.points>maxPoints)return geometry;
            Iterator<Geometry> it=entries.values().iterator();
            while((retainedPoints+geometry.points>maxPoints||entries.size()>=maxEntries)&&it.hasNext()){
                Geometry candidate=it.next();if(candidate.frame!=frame){retainedPoints-=candidate.points;it.remove();}}
            if(retainedPoints+geometry.points>maxPoints||entries.size()>=maxEntries)return geometry;
            geometry.frame=frame;entries.put(key,geometry);retainedPoints+=geometry.points;
            return geometry;
        }
        public void draw(Canvas canvas,Paint paint,InkPage.Stroke stroke){
            draw(canvas,paint,stroke,stroke.color);
        }
        public void draw(Canvas canvas,Paint paint,InkPage.Stroke stroke,int displayColor){
            Geometry geometry=entries.get(stroke);if(geometry==null)geometry=remember(stroke,build(stroke));
            geometry.frame=frame;
            drawGeometry(canvas,paint,geometry,displayColor,stroke.width);
        }
        /** Caller translates to the first point using double subtraction before scaling. */
        public void drawNote(Canvas canvas,Paint paint,Object owner,double[] points,int color,float width){
            Geometry geometry=owner==null?null:entries.get(owner);
            if(geometry==null){Path path=null;if(points.length>2){path=new Path();path.moveTo(0,0);for(int i=2;i<points.length;i+=2)path.lineTo((float)(points[i]-points[0]),(float)(points[i+1]-points[1]));}
                geometry=remember(owner,new Geometry(path,false,points.length/2,0,0));}
            geometry.frame=frame;
            drawGeometry(canvas,paint,geometry,color,width);
        }
        public void clear(){entries.clear();retainedPoints=0;}
        long builds(){return builds;}int retainedPoints(){return retainedPoints;}int size(){return entries.size();}
    }
    /** Bounds are rebuilt only when InkPage replaces its immutable stroke-list snapshot. */
    public static final class Page {
        private List<InkPage.Stroke> snapshot;private float[] bounds;
        public void draw(Canvas canvas,Paint paint,Cache cache,List<InkPage.Stroke> strokes,float l,float t,float r,float b){
            draw(canvas,paint,cache,strokes,l,t,r,b,false,0);
        }
        public void draw(Canvas canvas,Paint paint,Cache cache,List<InkPage.Stroke> strokes,float l,float t,float r,float b,boolean night,int paper){
            if(snapshot!=strokes||bounds.length!=strokes.size()*4){snapshot=strokes;bounds=new float[strokes.size()*4];
                for(int n=0;n<strokes.size();n++){InkPage.Stroke s=strokes.get(n);float x=s.points[0],y=s.points[1],right=x,bottom=y;
                    for(int i=2;i<s.points.length;i+=2){x=Math.min(x,s.points[i]);y=Math.min(y,s.points[i+1]);right=Math.max(right,s.points[i]);bottom=Math.max(bottom,s.points[i+1]);}
                    float radius=s.width*(s.pressures==null?.5f:.75f);int at=n*4;bounds[at]=x-radius;bounds[at+1]=y-radius;bounds[at+2]=right+radius;bounds[at+3]=bottom+radius;}}
            for(int n=0;n<strokes.size();n++){int at=n*4;if(bounds[at]>r||bounds[at+1]>b||bounds[at+2]<l||bounds[at+3]<t)continue;InkPage.Stroke stroke=strokes.get(n);cache.draw(canvas,paint,stroke,night?NightInk.display(stroke.color,paper):stroke.color);}
        }
    }
    public static void draw(Canvas canvas,Paint paint,InkPage.Stroke stroke){drawGeometry(canvas,paint,build(stroke),stroke.color,stroke.width);}
    public static void draw(Canvas canvas,Paint paint,InkPage.Stroke stroke,int displayColor){drawGeometry(canvas,paint,build(stroke),displayColor,stroke.width);}
    private static Geometry build(InkPage.Stroke stroke){
        float[] points=stroke.points;final Path path=new Path();
        if(stroke.pressures!=null){
            InkPage.outline(stroke,new InkPage.OutlineSink<RuntimeException>(){
                public void move(float x,float y){path.moveTo(x,y);}
                public void line(float x,float y){path.lineTo(x,y);}
                public void curve(float a,float b,float c,float d,float e,float f){path.cubicTo(a,b,c,d,e,f);}
                public void close(){path.close();}
            });return new Geometry(path,true,points.length/2,0,0);
        }
        if(points.length==2)return new Geometry(null,false,1,points[0],points[1]);
        path.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);
        return new Geometry(path,false,points.length/2,0,0);
    }
    private static void drawGeometry(Canvas canvas,Paint paint,Geometry geometry,int color,float width){
        paint.setColor(color);paint.setStrokeWidth(width);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        if(geometry.path==null){paint.setStyle(Paint.Style.FILL);canvas.drawCircle(geometry.dotX,geometry.dotY,width/2,paint);}
        else{paint.setStyle(geometry.fill?Paint.Style.FILL:Paint.Style.STROKE);canvas.drawPath(geometry.path,paint);}
    }
}
