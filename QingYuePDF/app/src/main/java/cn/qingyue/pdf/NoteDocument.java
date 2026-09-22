package cn.qingyue.pdf;

import java.io.*;
import java.util.*;

/** Vector note data. Coordinates use points and may extend on either axis on infinite paper. */
public final class NoteDocument {
    public static final int STROKE=0,LINE=1,CIRCLE=2,RECTANGLE=3,ARROW=4,IMAGE=5;
    static final int MAX_ITEMS=10000,MAX_POINTS=1000000;
    private static final int MAGIC=0x514e4f54,VERSION=1;
    public final String template,size;
    public final double width,height;
    public double centerX,centerY,zoom=1;
    public boolean viewportSaved;
    private List<Item> items=new ArrayList<>();
    private final ArrayDeque<List<Item>> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private long nextId=1;

    public static final class Item {
        public final long id;
        public final int type,color;
        public final float width;
        /** Stroke vertices, or x1,y1,x2,y2 for geometry/image bounds. */
        public final double[] points;
        public final String image;
        private final double left,top,right,bottom;
        public Item(long id,int type,int color,float width,double[] points,String image){
            this.id=id;this.type=type;this.color=color;this.width=width;this.points=points.clone();this.image=image==null?"":image;
            validate();
            double l=points[0],t=points[1],r=l,b=t;for(int i=2;i<points.length;i+=2){l=Math.min(l,points[i]);r=Math.max(r,points[i]);t=Math.min(t,points[i+1]);b=Math.max(b,points[i+1]);}
            if(type==ARROW){double[] a=arrowHeads(points,width);for(int i=0;i<4;i+=2){l=Math.min(l,a[i]);r=Math.max(r,a[i]);t=Math.min(t,a[i+1]);b=Math.max(b,a[i+1]);}}
            left=l;top=t;right=r;bottom=b;
        }
        private void validate(){
            if(id<=0||type<STROKE||type>IMAGE||!finite(width)||width<=0||width>200||points.length<2||points.length%2!=0||points.length>MAX_POINTS*2)throw new IllegalArgumentException("无效的笔记图形");
            if(type!=STROKE&&points.length!=4)throw new IllegalArgumentException("无效的图形坐标");
            for(double v:points)if(!finite(v))throw new IllegalArgumentException("无效的笔记坐标");
            if(type==IMAGE&&(!image.matches("[a-zA-Z0-9_-]+\\.(jpg|jpeg|png|webp)")||points[2]<=points[0]||points[3]<=points[1]))throw new IllegalArgumentException("无效的笔记图片");
        }
        public Item bounds(double x,double y,double w,double h){return new Item(id,type,color,width,new double[]{x,y,x+w,y+h},image);}
        public double left(){return left;}
        public double top(){return top;}
        public double right(){return right;}
        public double bottom(){return bottom;}
        public boolean near(double x,double y,double radius){
            if(type==IMAGE)return x>=left()-radius&&x<=right()+radius&&y>=top()-radius&&y<=bottom()+radius;
            double r=radius+width*.5;
            if(x<left()-r||x>right()+r||y<top()-r||y>bottom()+r)return false;
            if(type==STROKE||type==LINE||type==ARROW){
                if(points.length==2)return Math.hypot(x-points[0],y-points[1])<=r;
                for(int i=2;i<points.length;i+=2)if(segmentDistance(x,y,points[i-2],points[i-1],points[i],points[i+1])<=r)return true;
                if(type==ARROW){double[] a=arrowHeads(points,width);return segmentDistance(x,y,points[2],points[3],a[0],a[1])<=r||segmentDistance(x,y,points[2],points[3],a[2],a[3])<=r;}
                return false;
            }
            double l=left(),t=top(),rr=right(),b=bottom();
            if(type==RECTANGLE)return segmentDistance(x,y,l,t,rr,t)<=r||segmentDistance(x,y,rr,t,rr,b)<=r||segmentDistance(x,y,rr,b,l,b)<=r||segmentDistance(x,y,l,b,l,t)<=r;
            double cx=(l+rr)/2,cy=(t+b)/2,rx=(rr-l)/2,ry=(b-t)/2;
            if(rx==0||ry==0)return segmentDistance(x,y,l,t,rr,b)<=r;
            double angle=Math.atan2((y-cy)/ry,(x-cx)/rx);
            return Math.hypot(x-cx-rx*Math.cos(angle),y-cy-ry*Math.sin(angle))<=r;
        }
    }
    private NoteDocument(String template,String size){
        if(!Arrays.asList("blank","ruled","grid").contains(template))throw new IllegalArgumentException("未知的纸张背景");
        this.template=template;this.size=size;double[] mm;
        switch(size){case "A3":mm=new double[]{297,420};break;case "A4":mm=new double[]{210,297};break;case "A5":mm=new double[]{148,210};break;case "B4":mm=new double[]{250,353};break;case "B5":mm=new double[]{176,250};break;case "Letter":mm=new double[]{215.9,279.4};break;case "infinite":mm=new double[]{0,0};break;default:throw new IllegalArgumentException("未知的纸张大小");}
        width=mm[0]*72/25.4;height=mm[1]*72/25.4;centerX=width/2;centerY=height/2;
    }
    public static NoteDocument create(String template,String size){return new NoteDocument(template,size);}
    public boolean infinite(){return width==0;}
    public List<Item> items(){return Collections.unmodifiableList(items);}
    public long newId(){if(nextId==Long.MAX_VALUE)throw new IllegalStateException("笔记项目已达上限");return nextId++;}
    public Item find(long id){for(Item item:items)if(item.id==id)return item;return null;}
    public void add(Item item){List<Item> next=new ArrayList<>(items);next.add(item);commit(next);}
    public void replace(Item item){List<Item> next=new ArrayList<>(items);for(int i=0;i<next.size();i++)if(next.get(i).id==item.id){next.set(i,item);commit(next);return;}}
    public void remove(Set<Long> ids){List<Item> next=new ArrayList<>();for(Item item:items)if(!ids.contains(item.id))next.add(item);if(next.size()!=items.size())commit(next);}
    public void remove(long id){remove(Collections.singleton(id));}
    private void commit(List<Item> next){validateItems(next);undo.addLast(items);if(undo.size()>30)undo.removeFirst();items=next;redo.clear();}
    public boolean canUndo(){return !undo.isEmpty();}
    public boolean canRedo(){return !redo.isEmpty();}
    public boolean undo(){if(undo.isEmpty())return false;redo.addLast(items);items=undo.removeLast();return true;}
    public boolean redo(){if(redo.isEmpty())return false;undo.addLast(items);items=redo.removeLast();return true;}
    public NoteDocument copy(){NoteDocument d=new NoteDocument(template,size);d.items=new ArrayList<>(items);d.nextId=nextId;d.centerX=centerX;d.centerY=centerY;d.zoom=zoom;d.viewportSaved=viewportSaved;return d;}
    private static void validateItems(List<Item> items){
        if(items.size()>MAX_ITEMS)throw new IllegalArgumentException("这份笔记的图形过多，请新建一份笔记");
        int count=0;Set<Long> ids=new HashSet<>();
        for(Item item:items){item.validate();if(!ids.add(item.id))throw new IllegalArgumentException("重复的图形编号");count+=item.points.length/2;if(count>MAX_POINTS)throw new IllegalArgumentException("这份笔记的笔迹过多，请新建一份笔记");}
    }
    public static void write(OutputStream stream,NoteDocument d)throws IOException{
        validateItems(d.items);if(!finite(d.centerX)||!finite(d.centerY)||!finite(d.zoom)||d.zoom<.025||d.zoom>32)throw new IOException("无效的笔记视图坐标");DataOutputStream out=new DataOutputStream(new BufferedOutputStream(stream));out.writeInt(MAGIC);out.writeInt(VERSION);out.writeUTF(d.template);out.writeUTF(d.size);out.writeBoolean(d.viewportSaved);out.writeDouble(d.centerX);out.writeDouble(d.centerY);out.writeDouble(d.zoom);out.writeInt(d.items.size());
        for(Item item:d.items){out.writeLong(item.id);out.writeInt(item.type);out.writeInt(item.color);out.writeFloat(item.width);out.writeUTF(item.image);out.writeInt(item.points.length);for(double p:item.points)out.writeDouble(p);}out.flush();
    }
    public static NoteDocument read(InputStream stream)throws IOException{
        try{DataInputStream in=new DataInputStream(new BufferedInputStream(stream));if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("不支持的笔记版本");NoteDocument d=create(in.readUTF(),in.readUTF());d.viewportSaved=in.readBoolean();d.centerX=in.readDouble();d.centerY=in.readDouble();d.zoom=in.readDouble();if(!finite(d.centerX)||!finite(d.centerY)||!finite(d.zoom)||d.zoom<.025||d.zoom>32)throw new IOException("笔记视图数据损坏");int count=in.readInt();if(count<0||count>MAX_ITEMS)throw new IOException("笔记项目数量无效");int total=0;
            for(int n=0;n<count;n++){long id=in.readLong();int type=in.readInt(),color=in.readInt();float width=in.readFloat();String image=in.readUTF();int len=in.readInt();if(len<2||len%2!=0||len>MAX_POINTS*2||(total+=len/2)>MAX_POINTS)throw new IOException("笔记坐标数量无效");double[] p=new double[len];for(int j=0;j<len;j++)p[j]=in.readDouble();d.items.add(new Item(id,type,color,width,p,image));if(id==Long.MAX_VALUE)throw new IOException("笔记项目编号无效");d.nextId=Math.max(d.nextId,id+1);}validateItems(d.items);if(in.read()!=-1)throw new IOException("笔记末尾数据损坏");return d;
        }catch(IllegalArgumentException e){throw new IOException("笔记数据损坏",e);}
    }
    public static double segmentDistance(double x,double y,double ax,double ay,double bx,double by){double dx=bx-ax,dy=by-ay;double denominator=dx*dx+dy*dy;double t=denominator==0?0:Math.max(0,Math.min(1,((x-ax)*dx+(y-ay)*dy)/denominator));return Math.hypot(x-ax-t*dx,y-ay-t*dy);}
    static double[] arrowHeads(double[] p,float width){double a=Math.atan2(p[3]-p[1],p[2]-p[0]),length=Math.min(Math.hypot(p[2]-p[0],p[3]-p[1])*.4,Math.max(12,width*4));return new double[]{p[2]-length*Math.cos(a-.5),p[3]-length*Math.sin(a-.5),p[2]-length*Math.cos(a+.5),p[3]-length*Math.sin(a+.5)};}
    static boolean finite(double value){return !Double.isNaN(value)&&!Double.isInfinite(value);}
}
