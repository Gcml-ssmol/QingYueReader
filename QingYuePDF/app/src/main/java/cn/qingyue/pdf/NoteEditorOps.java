package cn.qingyue.pdf;

import java.util.*;

/** Page-local, v3-compatible selection and item transforms. */
final class NoteEditorOps {
    static final int HANDWRITING=1, GRAPHICS=2, TEXT=4, IMAGE=8, ALL=15;
    private NoteEditorOps(){}

    static int kind(NoteDocument.Item item){
        if(item.type==NoteDocument.STROKE)return HANDWRITING;
        if(item.type==NoteDocument.TEXT)return TEXT;
        if(item.type==NoteDocument.IMAGE)return IMAGE;
        return GRAPHICS;
    }
    static Set<Long> select(List<NoteDocument.Item> items,List<double[]> path,boolean freeform,int filter){
        LinkedHashSet<Long> ids=new LinkedHashSet<>();if(path==null||path.size()<(freeform?3:2))return ids;
        double l=Double.POSITIVE_INFINITY,t=l,r=Double.NEGATIVE_INFINITY,b=r;
        for(double[] point:path){l=Math.min(l,point[0]);t=Math.min(t,point[1]);r=Math.max(r,point[0]);b=Math.max(b,point[1]);}
        if(r-l<1||b-t<1)return ids;
        List<double[]> polygon=freeform?path:Arrays.asList(
                new double[]{l,t},new double[]{r,t},new double[]{r,b},new double[]{l,b});
        for(NoteDocument.Item item:items){
            double radius=item.type==NoteDocument.IMAGE||item.type==NoteDocument.TEXT?0:item.width/2.0;
            if((kind(item)&filter)==0||item.right()+radius<l||item.left()-radius>r||item.bottom()+radius<t||item.top()-radius>b)continue;
            if(intersectsPolygon(item,polygon))ids.add(item.id);
        }
        return ids;
    }
    private static boolean intersectsPolygon(NoteDocument.Item item,List<double[]> polygon){
        if(item.type==NoteDocument.IMAGE||item.type==NoteDocument.TEXT){
            double[][] corners={{item.left(),item.top()},{item.right(),item.top()},
                    {item.right(),item.bottom()},{item.left(),item.bottom()}};
            for(double[] corner:corners)if(inside(corner[0],corner[1],polygon))return true;
            for(double[] p:polygon)if(p[0]>=item.left()&&p[0]<=item.right()&&p[1]>=item.top()&&p[1]<=item.bottom())return true;
            for(int i=0;i<4;i++)for(int j=0;j<polygon.size();j++){
                double[] a=corners[i],b=corners[(i+1)%4],c=polygon.get(j),d=polygon.get((j+1)%polygon.size());
                if(cross(a[0],a[1],b[0],b[1],c[0],c[1],d[0],d[1]))return true;
            }
            return false;
        }
        // Vector shapes are outlines. Their bounding-box interior is empty paper.
        double radius=item.width/2.0;
        for(double[] points:NoteDocument.erasablePaths(item)){
            for(int i=0;i<points.length;i+=2)if(inside(points[i],points[i+1],polygon))return true;
            if(points.length==2){for(int j=0;j<polygon.size();j++){
                double[] a=polygon.get(j),b=polygon.get((j+1)%polygon.size());
                if(NoteDocument.segmentDistance(points[0],points[1],a[0],a[1],b[0],b[1])<=radius)return true;
            }}
            for(int i=2;i<points.length;i+=2)for(int j=0;j<polygon.size();j++){
                double[] a=polygon.get(j),b=polygon.get((j+1)%polygon.size());
                if(NoteDocument.segmentsNear(points[i-2],points[i-1],points[i],points[i+1],a[0],a[1],b[0],b[1],radius))return true;
            }
        }
        return false;
    }
    private static boolean inside(double x,double y,List<double[]> polygon){
        boolean result=false;for(int i=0,j=polygon.size()-1;i<polygon.size();j=i++){
            double[] a=polygon.get(i),b=polygon.get(j);
            if((a[1]>y)!=(b[1]>y)&&x<(b[0]-a[0])*(y-a[1])/(b[1]-a[1])+a[0])result=!result;
        }return result;
    }
    private static boolean cross(double ax,double ay,double bx,double by,double cx,double cy,double dx,double dy){
        double a=orientation(ax,ay,bx,by,cx,cy),b=orientation(ax,ay,bx,by,dx,dy);
        double c=orientation(cx,cy,dx,dy,ax,ay),d=orientation(cx,cy,dx,dy,bx,by);
        return a*b<=0&&c*d<=0&&Math.max(Math.min(ax,bx),Math.min(cx,dx))<=Math.min(Math.max(ax,bx),Math.max(cx,dx))&&Math.max(Math.min(ay,by),Math.min(cy,dy))<=Math.min(Math.max(ay,by),Math.max(cy,dy));
    }
    private static double orientation(double ax,double ay,double bx,double by,double cx,double cy){return (bx-ax)*(cy-ay)-(by-ay)*(cx-ax);}
    static NoteDocument.Item translated(NoteDocument.Item item,double dx,double dy){
        double[] points=item.points.clone();for(int i=0;i<points.length;i+=2){points[i]+=dx;points[i+1]+=dy;}
        return new NoteDocument.Item(item.id,item.type,item.color,item.width,points,item.image,item.text);
    }
    static NoteDocument.Item scaled(NoteDocument.Item item,double x,double y,double factor){
        if(!NoteDocument.finite(factor)||factor<=0)throw new IllegalArgumentException("无效的缩放比例");
        double[] points=item.points.clone();for(int i=0;i<points.length;i+=2){points[i]=x+(points[i]-x)*factor;points[i+1]=y+(points[i+1]-y)*factor;}
        float width=item.type==NoteDocument.IMAGE?item.width:(float)Math.max(.2,Math.min(item.type==NoteDocument.TEXT?72:200,item.width*factor));
        return new NoteDocument.Item(item.id,item.type,item.color,width,points,item.image,item.text);
    }
    static NoteDocument.Item recolored(NoteDocument.Item item,int rgb){
        if(item.type==NoteDocument.IMAGE)return item;
        int color=(item.color&0xff000000)|(rgb&0x00ffffff);
        return new NoteDocument.Item(item.id,item.type,color,item.width,item.points,item.image,item.text);
    }
    static double[] bounds(List<NoteDocument.Item> items,Set<Long> ids){
        double l=Double.POSITIVE_INFINITY,t=l,r=Double.NEGATIVE_INFINITY,b=r;
        for(NoteDocument.Item item:items)if(ids.contains(item.id)){l=Math.min(l,item.left());t=Math.min(t,item.top());r=Math.max(r,item.right());b=Math.max(b,item.bottom());}
        return l==Double.POSITIVE_INFINITY?null:new double[]{l,t,r,b};
    }
    static List<NoteDocument.Item> wholeStrokeErase(List<NoteDocument.Item> source,double ax,double ay,double bx,double by,double radius){
        ArrayList<NoteDocument.Item> kept=new ArrayList<>(source.size());
        for(NoteDocument.Item item:source){
            if(item.type==NoteDocument.IMAGE||item.type==NoteDocument.TEXT){kept.add(item);continue;}
            List<NoteDocument.Item> cut=NoteDocument.eraseSweep(Collections.singletonList(item),ax,ay,bx,by,radius);
            if(cut.size()==1&&cut.get(0)==item)kept.add(item);
        }
        return kept;
    }
}
