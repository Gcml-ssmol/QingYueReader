package cn.qingyue.pdf;

import java.io.*;
import java.util.*;

/**
 * Platform independent ink model. Coordinates and widths are fractions of page width.
 * Version 1 files remain readable and are still written for legacy-only strokes.
 */
public final class InkPage {
    public static final int MAX_POINTS=1000000, MAX_STROKES=10000;
    public static final int KIND_FREEHAND=0, KIND_LINE=1, KIND_RECTANGLE=2,
            KIND_ELLIPSE=3, KIND_ARROW=4;
    private static final int MAGIC=0x51494e4b, VERSION_1=1, VERSION_2=2;
    private static final int HISTORY_LIMIT=30;

    public static final class Stroke {
        public final int color;
        public final float width;
        public final int kind;
        final float[] points;
        final float[] pressures;

        /** Legacy/source-compatible uniform-width freehand stroke. */
        public Stroke(int color,float width,float[] points) {
            this(color,width,points,KIND_FREEHAND,null);
        }

        /** Stroke with an editable kind and optional pressure per point. */
        public Stroke(int color,float width,float[] points,int kind,float[] pressures) {
            if(!finite(width)||width<=0||width>0.1f||points==null||points.length<2
                    ||points.length%2!=0||points.length/2>MAX_POINTS||kind<KIND_FREEHAND
                    ||kind>KIND_ARROW) throw new IllegalArgumentException("Invalid stroke");
            for(int i=0;i<points.length;i++)if(!finite(points[i])||points[i]<0
                    ||points[i]>(i%2==0?1.0f:2147483648f))
                throw new IllegalArgumentException("Invalid point");
            if(pressures!=null&&pressures.length!=points.length/2)
                throw new IllegalArgumentException("Pressure count does not match points");
            if(pressures!=null)for(float pressure:pressures)if(!finite(pressure)
                    ||pressure<0.05f||pressure>1.5f)
                throw new IllegalArgumentException("Invalid pressure");
            this.color=color;this.width=width;this.kind=kind;this.points=points.clone();
            this.pressures=pressures==null||allUnit(pressures)?null:pressures.clone();
        }

        public float[] points(){return points.clone();}
        public float[] pressures(){return pressures==null?null:pressures.clone();}
        public boolean hasPressure(){return pressures!=null;}

        private static boolean allUnit(float[] values){
            for(float value:values)if(Math.abs(value-1f)>0.015f)return false;return true;
        }
    }

    private List<Stroke> strokes=Collections.emptyList();
    private final ArrayDeque<List<Stroke>> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    public InkPage(List<Stroke> initial){strokes=snapshot(initial);}
    public List<Stroke> strokes(){return strokes;}
    public boolean canUndo(){return !undo.isEmpty();}
    public boolean canRedo(){return !redo.isEmpty();}
    public int undoDepth(){return undo.size();}
    public int redoDepth(){return redo.size();}
    public void replace(List<Stroke> next){
        List<Stroke> copy=snapshot(next);if(sameIdentity(strokes,copy))return;
        undo.push(strokes);if(undo.size()>HISTORY_LIMIT)undo.removeLast();redo.clear();strokes=copy;
    }
    public void add(Stroke stroke){List<Stroke> next=new ArrayList<>(strokes);next.add(stroke);replace(next);}
    public boolean clear(){if(strokes.isEmpty())return false;replace(Collections.<Stroke>emptyList());return true;}
    public boolean undo(){if(!canUndo())return false;redo.push(strokes);strokes=undo.pop();return true;}
    public boolean redo(){if(!canRedo())return false;undo.push(strokes);strokes=redo.pop();return true;}

    /** Value comparison used by recovery code; persistence never relies on object identity. */
    public static boolean sameContent(List<Stroke> first,List<Stroke> second){
        if(first==second)return true;if(first==null||second==null||first.size()!=second.size())return false;
        for(int i=0;i<first.size();i++){
            Stroke a=first.get(i),b=second.get(i);if(a.color!=b.color||Float.floatToIntBits(a.width)!=Float.floatToIntBits(b.width)
                    ||a.kind!=b.kind||!Arrays.equals(a.points,b.points)||!Arrays.equals(a.pressures,b.pressures))return false;
        }
        return true;
    }

    /** Returns stroke indices touched by or enclosed by a page-coordinate lasso. */
    public List<Integer> select(float[] polygon){
        if(polygon==null||polygon.length<6||polygon.length%2!=0)
            throw new IllegalArgumentException("A lasso needs at least three points");
        validatePoints(polygon);
        ArrayList<Integer> selected=new ArrayList<>();
        for(int i=0;i<strokes.size();i++)if(touchesPolygon(strokes.get(i),polygon))selected.add(i);
        return Collections.unmodifiableList(selected);
    }

    /** Moves or scales selected strokes around an anchor as one undoable operation. */
    public boolean transform(Collection<Integer> indices,float anchorX,float anchorY,
            float scale,float deltaX,float deltaY){
        if(indices==null||indices.isEmpty())return false;
        if(!finite(anchorX)||!finite(anchorY)||!finite(scale)||!finite(deltaX)||!finite(deltaY)
                ||scale<=0||scale>100)throw new IllegalArgumentException("Invalid transform");
        HashSet<Integer> wanted=new HashSet<>(indices);ArrayList<Stroke> next=new ArrayList<>(strokes);
        boolean changed=false;
        for(int i=0;i<strokes.size();i++)if(wanted.contains(i)){
            Stroke source=strokes.get(i);float[] moved=source.points.clone();
            for(int p=0;p<moved.length;p+=2){
                moved[p]=clamp((moved[p]-anchorX)*scale+anchorX+deltaX,0,1);
                moved[p+1]=Math.max(0,(moved[p+1]-anchorY)*scale+anchorY+deltaY);
            }
            next.set(i,new Stroke(source.color,Math.min(0.1f,source.width*scale),moved,
                    source.kind,source.pressures));changed=true;
        }
        if(changed)replace(next);return changed;
    }

    /** Duplicates selected strokes and offsets the copies. */
    public List<Integer> copy(Collection<Integer> indices,float deltaX,float deltaY){
        if(indices==null||indices.isEmpty())return Collections.emptyList();
        HashSet<Integer> wanted=new HashSet<>(indices);ArrayList<Stroke> next=new ArrayList<>(strokes);
        ArrayList<Integer> copies=new ArrayList<>();
        for(int i=0;i<strokes.size();i++)if(wanted.contains(i)){
            Stroke source=strokes.get(i);float[] moved=source.points.clone();
            for(int p=0;p<moved.length;p+=2){moved[p]=clamp(moved[p]+deltaX,0,1);moved[p+1]=Math.max(0,moved[p+1]+deltaY);}
            next.add(new Stroke(source.color,source.width,moved,source.kind,source.pressures));copies.add(next.size()-1);
        }
        if(!copies.isEmpty())replace(next);return Collections.unmodifiableList(copies);
    }

    public boolean delete(Collection<Integer> indices){
        if(indices==null||indices.isEmpty())return false;HashSet<Integer>wanted=new HashSet<>(indices);
        ArrayList<Stroke> next=new ArrayList<>();for(int i=0;i<strokes.size();i++)if(!wanted.contains(i))next.add(strokes.get(i));
        if(next.size()==strokes.size())return false;replace(next);return true;
    }

    /** Locally removes only the portions swept by an eraser segment. */
    public boolean erasePartial(float ax,float ay,float bx,float by,float radius){
        if(!finite(ax)||!finite(ay)||!finite(bx)||!finite(by)||!finite(radius)||radius<=0)
            throw new IllegalArgumentException("Invalid eraser sweep");
        ArrayList<Stroke> next=new ArrayList<>();boolean changed=false;
        for(Stroke stroke:strokes){
            if(!hit(stroke,ax,ay,bx,by,radius)){next.add(stroke);continue;}
            changed=true;int first=next.size();splitOutside(stroke,ax,ay,bx,by,radius,next);
            if(stroke.kind==KIND_RECTANGLE||stroke.kind==KIND_ELLIPSE)joinClosedSeam(next,first);
        }
        if(changed)replace(next);return changed;
    }

    /** Creates an editable straight/rectangle/ellipse/arrow stroke. */
    public static Stroke shape(int kind,int color,float width,float x0,float y0,float x1,float y1){
        if(kind<KIND_LINE||kind>KIND_ARROW)throw new IllegalArgumentException("Unknown shape");
        x0=clamp(x0,0,1);x1=clamp(x1,0,1);y0=Math.max(0,y0);y1=Math.max(0,y1);
        float[] points;
        if(kind==KIND_LINE)points=new float[]{x0,y0,x1,y1};
        else if(kind==KIND_RECTANGLE)points=new float[]{x0,y0,x1,y0,x1,y1,x0,y1,x0,y0};
        else if(kind==KIND_ELLIPSE){
            int n=48;points=new float[(n+1)*2];float cx=(x0+x1)/2,cy=(y0+y1)/2,rx=Math.abs(x1-x0)/2,ry=Math.abs(y1-y0)/2;
            for(int i=0;i<=n;i++){double angle=Math.PI*2*i/n;points[i*2]=cx+(float)Math.cos(angle)*rx;points[i*2+1]=cy+(float)Math.sin(angle)*ry;}
        }else{
            float dx=x1-x0,dy=y1-y0,length=(float)Math.hypot(dx,dy),head=Math.min(0.045f,Math.max(width*5,length*0.22f));
            float ux=length==0?1:dx/length,uy=length==0?0:dy/length,px=-uy,py=ux;
            points=new float[]{x0,y0,x1,y1,clamp(x1-ux*head+px*head*.45f,0,1),Math.max(0,y1-uy*head+py*head*.45f),
                    x1,y1,clamp(x1-ux*head-px*head*.45f,0,1),Math.max(0,y1-uy*head-py*head*.45f)};
        }
        return new Stroke(color,width,points,kind,null);
    }

    private static void splitOutside(Stroke stroke,float ax,float ay,float bx,float by,float radius,List<Stroke> out){
        float effective=radius+stroke.width/2;if(stroke.points.length==2){
            if(distance(stroke.points[0],stroke.points[1],ax,ay,bx,by)>effective)out.add(stroke);return;
        }
        if(stroke.kind==KIND_ARROW&&stroke.points.length==10){
            for(int[] pair:new int[][]{{0,1},{1,2},{1,4}}){
                int a=pair[0],b=pair[1];float[] points={stroke.points[a*2],stroke.points[a*2+1],stroke.points[b*2],stroke.points[b*2+1]};
                float[] pressure=stroke.pressures==null?null:new float[]{stroke.pressures[a],stroke.pressures[b]};
                splitOutsidePath(stroke,points,pressure,ax,ay,bx,by,effective,out);
            }
            return;
        }
        splitOutsidePath(stroke,stroke.points,stroke.pressures,ax,ay,bx,by,effective,out);
    }

    /** A single non-zero-winding fill prevents translucent pressure segments darkening at joins. */
    public interface OutlineSink<E extends Exception> {
        void move(float x,float y)throws E;
        void line(float x,float y)throws E;
        void curve(float x1,float y1,float x2,float y2,float x3,float y3)throws E;
        void close()throws E;
    }
    public static <E extends Exception> void outline(Stroke stroke,OutlineSink<E> sink)throws E {
        float[] p=stroke.points;int n=p.length/2;
        for(int i=0;i<n;i++){
            float x=p[i*2],y=p[i*2+1],r=stroke.width*pressure(stroke,i)/2,k=r*.55228475f;
            sink.move(x+r,y);sink.curve(x+r,y+k,x+k,y+r,x,y+r);
            sink.curve(x-k,y+r,x-r,y+k,x-r,y);sink.curve(x-r,y-k,x-k,y-r,x,y-r);
            sink.curve(x+k,y-r,x+r,y-k,x+r,y);sink.close();
            if(i==0)continue;
            float ax=p[i*2-2],ay=p[i*2-1],dx=x-ax,dy=y-ay,len=(float)Math.hypot(dx,dy);
            if(len<=0)continue;
            float ar=stroke.width*pressure(stroke,i-1)/2,nx=-dy/len,ny=dx/len;
            sink.move(ax-nx*ar,ay-ny*ar);sink.line(x-nx*r,y-ny*r);
            sink.line(x+nx*r,y+ny*r);sink.line(ax+nx*ar,ay+ny*ar);sink.close();
        }
    }
    private static float pressure(Stroke stroke,int point){return stroke.pressures==null?1:Math.max(.15f,Math.min(1.5f,stroke.pressures[point]));}
    private static void splitOutsidePath(Stroke stroke,float[] points,float[] sourcePressures,float ax,float ay,float bx,float by,float effective,List<Stroke> out){
        ArrayList<Float> segment=new ArrayList<>(),pressures=new ArrayList<>();int pointCount=points.length/2;
        for(int i=0;i<pointCount-1;i++){
            float x0=points[i*2],y0=points[i*2+1],x1=points[i*2+2],y1=points[i*2+3];
            float p0=sourcePressures==null?1:sourcePressures[i],p1=sourcePressures==null?1:sourcePressures[i+1];
            double[] cut=eraseInterval(x0,y0,x1,y1,ax,ay,bx,by,effective);
            if(cut==null){if(segment.isEmpty())appendInkPoint(segment,pressures,x0,y0,p0);appendInkPoint(segment,pressures,x1,y1,p1);continue;}
            if(cut[0]>0){if(segment.isEmpty())appendInkPoint(segment,pressures,x0,y0,p0);
                appendInkPoint(segment,pressures,(float)(x0+(x1-x0)*cut[0]),(float)(y0+(y1-y0)*cut[0]),(float)(p0+(p1-p0)*cut[0]));}
            flushSegment(stroke,segment,pressures,out);
            if(cut[1]<1){appendInkPoint(segment,pressures,(float)(x0+(x1-x0)*cut[1]),(float)(y0+(y1-y0)*cut[1]),(float)(p0+(p1-p0)*cut[1]));
                appendInkPoint(segment,pressures,x1,y1,p1);}
        }
        flushSegment(stroke,segment,pressures,out);
    }

    private static void appendInkPoint(ArrayList<Float> points,ArrayList<Float> pressures,float x,float y,float pressure){
        points.add(x);points.add(y);pressures.add(pressure);
    }
    private static void joinClosedSeam(ArrayList<Stroke> pieces,int firstIndex){
        if(pieces.size()-firstIndex<2)return;Stroke first=pieces.get(firstIndex),last=pieces.get(pieces.size()-1);
        if(Math.hypot(last.points[last.points.length-2]-first.points[0],last.points[last.points.length-1]-first.points[1])>1e-6)return;
        float[] points=Arrays.copyOf(last.points,last.points.length+first.points.length-2);
        System.arraycopy(first.points,2,points,last.points.length,first.points.length-2);
        float[] pressures=null;if(last.pressures!=null||first.pressures!=null){
            pressures=new float[points.length/2];int n=last.points.length/2;
            for(int i=0;i<n;i++)pressures[i]=last.pressures==null?1:last.pressures[i];
            for(int i=1;i<first.points.length/2;i++)pressures[n+i-1]=first.pressures==null?1:first.pressures[i];
        }
        pieces.set(firstIndex,new Stroke(first.color,first.width,points,KIND_FREEHAND,pressures));pieces.remove(pieces.size()-1);
    }

    /** The capsule intersection with a straight segment is one interval because a capsule is convex. */
    private static double[] eraseInterval(double x0,double y0,double x1,double y1,double ax,double ay,double bx,double by,double radius){
        if(Math.max(x0,x1)+radius<Math.min(ax,bx)||Math.min(x0,x1)-radius>Math.max(ax,bx)||
                Math.max(y0,y1)+radius<Math.min(ay,by)||Math.min(y0,y1)-radius>Math.max(ay,by))return null;
        double squared=radius*radius,lo=0,hi=1;
        for(int i=0;i<48;i++){double a=lo+(hi-lo)/3,b=hi-(hi-lo)/3;
            if(eraserDistanceSquared(x0,y0,x1,y1,ax,ay,bx,by,a)<eraserDistanceSquared(x0,y0,x1,y1,ax,ay,bx,by,b))hi=b;else lo=a;}
        double min=(lo+hi)/2;
        if(eraserDistanceSquared(x0,y0,x1,y1,ax,ay,bx,by,min)>squared)return null;
        double start=0,end=1;
        if(eraserDistanceSquared(x0,y0,x1,y1,ax,ay,bx,by,0)>squared){lo=0;hi=min;for(int i=0;i<48;i++){double mid=(lo+hi)/2;if(eraserDistanceSquared(x0,y0,x1,y1,ax,ay,bx,by,mid)>squared)lo=mid;else hi=mid;}start=hi;}
        if(eraserDistanceSquared(x0,y0,x1,y1,ax,ay,bx,by,1)>squared){lo=min;hi=1;for(int i=0;i<48;i++){double mid=(lo+hi)/2;if(eraserDistanceSquared(x0,y0,x1,y1,ax,ay,bx,by,mid)>squared)hi=mid;else lo=mid;}end=lo;}
        return end-start>1e-12?new double[]{start,end}:null;
    }
    private static double eraserDistanceSquared(double x0,double y0,double x1,double y1,double ax,double ay,double bx,double by,double t){
        double x=x0+(x1-x0)*t,y=y0+(y1-y0)*t,dx=bx-ax,dy=by-ay,den=dx*dx+dy*dy;
        double u=den==0?0:Math.max(0,Math.min(1,((x-ax)*dx+(y-ay)*dy)/den));
        double ex=x-ax-u*dx,ey=y-ay-u*dy;return ex*ex+ey*ey;
    }

    private static void flushSegment(Stroke source,ArrayList<Float> segment,ArrayList<Float> pressure,List<Stroke> out){
        if(segment.size()>=2){
            float[] points=new float[segment.size()];for(int i=0;i<points.length;i++)points[i]=segment.get(i);
            float[] pressures=null;if(source.pressures!=null){pressures=new float[pressure.size()];for(int i=0;i<pressures.length;i++)pressures[i]=pressure.get(i);}
            out.add(new Stroke(source.color,source.width,points,KIND_FREEHAND,pressures));
        }
        segment.clear();pressure.clear();
    }

    private static boolean touchesPolygon(Stroke stroke,float[] polygon){
        for(int i=0;i<stroke.points.length;i+=2)if(inside(stroke.points[i],stroke.points[i+1],polygon))return true;
        for(int i=2;i<stroke.points.length;i+=2)for(int p=0;p<polygon.length;p+=2){
            int q=(p+2)%polygon.length;if(intersect(stroke.points[i-2],stroke.points[i-1],stroke.points[i],stroke.points[i+1],polygon[p],polygon[p+1],polygon[q],polygon[q+1]))return true;
        }
        return false;
    }
    private static boolean inside(float x,float y,float[] polygon){
        boolean result=false;for(int i=0,j=polygon.length-2;i<polygon.length;j=i,i+=2){
            float xi=polygon[i],yi=polygon[i+1],xj=polygon[j],yj=polygon[j+1];
            if((yi>y)!=(yj>y)&&x<(xj-xi)*(y-yi)/(yj-yi)+xi)result=!result;
        }return result;
    }

    private static List<Stroke> snapshot(List<Stroke> input){
        if(input==null||input.size()>MAX_STROKES)throw new IllegalArgumentException("Too many strokes");
        long points=0;for(Stroke s:input){if(s==null)throw new IllegalArgumentException("Null stroke");points+=s.points.length/2;}
        if(points>MAX_POINTS)throw new IllegalArgumentException("Too many points");
        return Collections.unmodifiableList(new ArrayList<>(input));
    }
    private static boolean sameIdentity(List<Stroke>a,List<Stroke>b){
        if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(a.get(i)!=b.get(i))return false;return true;
    }
    private static void validatePoints(float[] points){
        for(int i=0;i<points.length;i++)if(!finite(points[i])||points[i]<0||points[i]>(i%2==0?1:2147483648f))throw new IllegalArgumentException("Invalid lasso");
    }
    private static boolean finite(float n){return !Float.isNaN(n)&&!Float.isInfinite(n);}
    private static float clamp(float n,float min,float max){return Math.max(min,Math.min(max,n));}
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
        snapshot(strokes);boolean extended=false;for(Stroke stroke:strokes)if(stroke.kind!=KIND_FREEHAND||stroke.pressures!=null){extended=true;break;}
        DataOutputStream out=new DataOutputStream(output);out.writeInt(MAGIC);out.writeInt(extended?VERSION_2:VERSION_1);out.writeInt(strokes.size());
        for(Stroke s:strokes){
            out.writeInt(s.color);out.writeFloat(s.width);
            if(extended){out.writeByte(s.kind);out.writeBoolean(s.pressures!=null);}
            out.writeInt(s.points.length/2);for(float p:s.points)out.writeFloat(p);
            if(extended&&s.pressures!=null)for(float pressure:s.pressures)out.writeFloat(pressure);
        }out.flush();
    }
    public static List<Stroke> read(InputStream input)throws IOException {
        DataInputStream in=new DataInputStream(input);if(in.readInt()!=MAGIC)throw new IOException("Unknown ink format");
        int version=in.readInt();if(version!=VERSION_1&&version!=VERSION_2)throw new IOException("Unknown ink format");
        int count=in.readInt();if(count<0||count>MAX_STROKES)throw new IOException("Invalid stroke count");
        List<Stroke> result=new ArrayList<>();int total=0;
        for(int i=0;i<count;i++){
            int color=in.readInt();float width=in.readFloat();int kind=KIND_FREEHAND;boolean pressured=false;
            if(version>=VERSION_2){kind=in.readUnsignedByte();pressured=in.readBoolean();}
            int n=in.readInt();if(n<1||n>MAX_POINTS-total)throw new IOException("Invalid point count");total+=n;
            float[] p=new float[n*2];for(int j=0;j<p.length;j++)p[j]=in.readFloat();
            float[] pressures=null;if(pressured){pressures=new float[n];for(int j=0;j<n;j++)pressures[j]=in.readFloat();}
            try{result.add(new Stroke(color,width,p,kind,pressures));}catch(IllegalArgumentException e){throw new IOException("Invalid stroke",e);}
        }
        if(in.read()!=-1)throw new IOException("Trailing data");return snapshot(result);
    }
}
