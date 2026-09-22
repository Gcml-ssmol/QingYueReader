package cn.qingyue.pdf;

import java.io.*;
import java.util.*;

/** Platform independent ink model. Coordinates and widths are fractions of page width. */
public final class InkPage {
    public static final int MAX_POINTS=1000000, MAX_STROKES=10000;
    private static final int MAGIC=0x51494e4b, VERSION=1;
    public static final class Stroke {
        public final int color;
        public final float width;
        final float[] points;
        public Stroke(int color,float width,float[] points) {
            if(!finite(width)||width<=0||width>0.1f||points.length<2||points.length%2!=0||points.length/2>MAX_POINTS)
                throw new IllegalArgumentException("Invalid stroke");
            for(int i=0;i<points.length;i++)if(!finite(points[i])||points[i]<0||points[i]>(i%2==0?1.0f:2147483648f))
                throw new IllegalArgumentException("Invalid point");
            this.color=color;this.width=width;this.points=points.clone();
        }
    }
    private List<Stroke> strokes=Collections.emptyList();
    private final ArrayDeque<List<Stroke>> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    public InkPage(List<Stroke> initial){strokes=snapshot(initial);}
    public List<Stroke> strokes(){return strokes;}
    public boolean canUndo(){return !undo.isEmpty();}
    public boolean canRedo(){return !redo.isEmpty();}
    public void replace(List<Stroke> next){List<Stroke> copy=snapshot(next);undo.push(strokes);if(undo.size()>30)undo.removeLast();redo.clear();strokes=copy;}
    public void add(Stroke stroke){List<Stroke> next=new ArrayList<>(strokes);next.add(stroke);replace(next);}
    public boolean undo(){if(!canUndo())return false;redo.push(strokes);strokes=undo.pop();return true;}
    public boolean redo(){if(!canRedo())return false;undo.push(strokes);strokes=redo.pop();return true;}
    private static List<Stroke> snapshot(List<Stroke> input){
        if(input.size()>MAX_STROKES)throw new IllegalArgumentException("Too many strokes");
        long points=0;for(Stroke s:input)points+=s.points.length/2;
        if(points>MAX_POINTS)throw new IllegalArgumentException("Too many points");
        return Collections.unmodifiableList(new ArrayList<>(input));
    }
    private static boolean finite(float n){return !Float.isNaN(n)&&!Float.isInfinite(n);}
    public static float toPage(float position,float origin,float renderedWidth){return (position-origin)/renderedWidth;}
    public static boolean hit(Stroke s,float ax,float ay,float bx,float by,float radius){
        float r=radius+s.width/2;
        if(s.points.length==2)return distance(s.points[0],s.points[1],ax,ay,bx,by)<=r;
        for(int i=2;i<s.points.length;i+=2){
            float cx=s.points[i-2],cy=s.points[i-1],dx=s.points[i],dy=s.points[i+1];
            if(intersect(ax,ay,bx,by,cx,cy,dx,dy)||distance(cx,cy,ax,ay,bx,by)<=r||distance(dx,dy,ax,ay,bx,by)<=r||distance(ax,ay,cx,cy,dx,dy)<=r||distance(bx,by,cx,cy,dx,dy)<=r)return true;
        }return false;
    }
    private static float distance(float px,float py,float ax,float ay,float bx,float by){
        double dx=bx-ax,dy=by-ay,len=dx*dx+dy*dy;
        double t=len==0?0:Math.max(0,Math.min(1,((px-ax)*dx+(py-ay)*dy)/len));
        return (float)Math.hypot(px-ax-t*dx,py-ay-t*dy);
    }
    private static boolean intersect(float ax,float ay,float bx,float by,float cx,float cy,float dx,float dy){
        double a=cross(ax,ay,bx,by,cx,cy),b=cross(ax,ay,bx,by,dx,dy),c=cross(cx,cy,dx,dy,ax,ay),d=cross(cx,cy,dx,dy,bx,by);
        return ((a<0&&b>0)||(a>0&&b<0))&&((c<0&&d>0)||(c>0&&d<0));
    }
    private static double cross(float ax,float ay,float bx,float by,float cx,float cy){return (double)(bx-ax)*(cy-ay)-(double)(by-ay)*(cx-ax);}
    public static void write(OutputStream output,List<Stroke> strokes)throws IOException {
        snapshot(strokes);DataOutputStream out=new DataOutputStream(output);out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(strokes.size());
        for(Stroke s:strokes){out.writeInt(s.color);out.writeFloat(s.width);out.writeInt(s.points.length/2);for(float p:s.points)out.writeFloat(p);}out.flush();
    }
    public static List<Stroke> read(InputStream input)throws IOException {
        DataInputStream in=new DataInputStream(input);
        if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("Unknown ink format");
        int count=in.readInt();if(count<0||count>MAX_STROKES)throw new IOException("Invalid stroke count");
        List<Stroke> result=new ArrayList<>();int total=0;
        for(int i=0;i<count;i++){
            int color=in.readInt();float width=in.readFloat();int n=in.readInt();
            if(n<1||n>MAX_POINTS-total)throw new IOException("Invalid point count");total+=n;
            float[] p=new float[n*2];for(int j=0;j<p.length;j++)p[j]=in.readFloat();
            try{result.add(new Stroke(color,width,p));}catch(IllegalArgumentException e){throw new IOException("Invalid stroke",e);}
        }
        if(in.read()!=-1)throw new IOException("Trailing data");return snapshot(result);
    }
}
