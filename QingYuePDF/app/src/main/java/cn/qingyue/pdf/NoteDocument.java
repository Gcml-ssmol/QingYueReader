package cn.qingyue.pdf;

import java.io.*;
import java.util.*;

/** Vector note data. Coordinates use points and may extend on either axis on infinite paper. */
public final class NoteDocument {
    public static final int STROKE=0,LINE=1,CIRCLE=2,RECTANGLE=3,ARROW=4,IMAGE=5,TEXT=6;
    static final int MAX_ITEMS=10000,MAX_POINTS=1000000;
    private static final int MAGIC=0x514e4f54,VERSION=3,MAX_PAGES=500;
    public final String template,size;
    public final double width,height;
    public double centerX,centerY,zoom=1;
    public boolean viewportSaved;
    private List<Item> items=new ArrayList<>();
    private final ArrayDeque<List<Item>> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private long nextId=1;
    private final ArrayList<Page> pages=new ArrayList<>();
    private int currentPage;
    private static final class Page {
        List<Item> items; double x,y,zoom; boolean saved;
        Page(List<Item> items,double x,double y,double zoom,boolean saved){this.items=items;this.x=x;this.y=y;this.zoom=zoom;this.saved=saved;}
    }

    public static final class Item {
        public final long id;
        public final int type,color;
        public final float width;
        /** Stroke vertices, or x1,y1,x2,y2 for geometry/image bounds. */
        public final double[] points;
        public final String image,text;
        private final double left,top,right,bottom;
        public Item(long id,int type,int color,float width,double[] points,String image){this(id,type,color,width,points,image,"");}
        public Item(long id,int type,int color,float width,double[] points,String image,String text){
            this.id=id;this.type=type;this.color=color;this.width=width;this.points=points.clone();this.image=image==null?"":image;this.text=text==null?"":text;
            validate();
            double l=points[0],t=points[1],r=l,b=t;for(int i=2;i<points.length;i+=2){l=Math.min(l,points[i]);r=Math.max(r,points[i]);t=Math.min(t,points[i+1]);b=Math.max(b,points[i+1]);}
            if(type==ARROW){double[] a=arrowHeads(points,width);for(int i=0;i<4;i+=2){l=Math.min(l,a[i]);r=Math.max(r,a[i]);t=Math.min(t,a[i+1]);b=Math.max(b,a[i+1]);}}
            left=l;top=t;right=r;bottom=b;
        }
        private void validate(){
            if(id<=0||type<STROKE||type>TEXT||!finite(width)||width<=0||width>200||points.length<2||points.length%2!=0||points.length>MAX_POINTS*2)throw new IllegalArgumentException("无效的笔记图形");
            if(type!=STROKE&&points.length!=4)throw new IllegalArgumentException("无效的图形坐标");
            for(double v:points)if(!finite(v))throw new IllegalArgumentException("无效的笔记坐标");
            if(type==IMAGE&&(!image.matches("[a-zA-Z0-9_-]+\\.(jpg|jpeg|png|webp)")||points[2]<=points[0]||points[3]<=points[1]))throw new IllegalArgumentException("无效的笔记图片");
            if(type==TEXT&&(text.isEmpty()||text.length()>16000||points[2]-points[0]<24||points[3]<=points[1]))throw new IllegalArgumentException("无效的文本框");
            // For TEXT only, image stores an optional local qingyue://source URI (v2 wire slot).
            if(type==TEXT&&!image.isEmpty()&&(!image.startsWith("qingyue://source?")||image.length()>60000))throw new IllegalArgumentException("无效的笔记来源链接");
        }
        public Item bounds(double x,double y,double w,double h){return new Item(id,type,color,width,new double[]{x,y,x+w,y+h},image,text);}
        public Item withText(String value,float size,int ink,double height){return new Item(id,TEXT,ink,size,new double[]{left,top,right,top+height},image,value);}
        public double left(){return left;}
        public double top(){return top;}
        public double right(){return right;}
        public double bottom(){return bottom;}
        public boolean near(double x,double y,double radius){
            if(type==IMAGE||type==TEXT)return x>=left()-radius&&x<=right()+radius&&y>=top()-radius&&y<=bottom()+radius;
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
        width=mm[0]*72/25.4;height=mm[1]*72/25.4;centerX=width/2;centerY=height/2;pages.add(new Page(items,centerX,centerY,zoom,false));
    }
    public static NoteDocument create(String template,String size){return new NoteDocument(template,size);}
    public boolean infinite(){return width==0;}
    public List<Item> items(){return Collections.unmodifiableList(items);}
    public int pageCount(){return pages.size();}
    public int currentPage(){return currentPage;}
    public List<Item> pageItems(int index){if(index<0||index>=pages.size())throw new IndexOutOfBoundsException();return Collections.unmodifiableList(index==currentPage?items:pages.get(index).items);}
    private void storePage(){Page p=pages.get(currentPage);p.items=items;p.x=centerX;p.y=centerY;p.zoom=zoom;p.saved=viewportSaved;}
    public void selectPage(int index){if(index<0||index>=pages.size())throw new IndexOutOfBoundsException();if(index==currentPage)return;storePage();currentPage=index;Page p=pages.get(index);items=p.items;centerX=p.x;centerY=p.y;zoom=p.zoom;viewportSaved=p.saved;undo.clear();redo.clear();}
    public int addPage(){return insertPage(pages.size());}
    /** Copies the active paper after itself. Image items keep this note's image filename. */
    public int duplicatePage(){
        if(pages.size()>=MAX_PAGES)throw new IllegalStateException("页数已达上限");
        storePage();Page source=pages.get(currentPage);
        ArrayList<Item> copied=new ArrayList<>(source.items.size());
        for(Item item:source.items)copied.add(new Item(newId(),item.type,item.color,item.width,item.points,item.image,item.text));
        int inserted=currentPage+1;
        pages.add(inserted,new Page(copied,source.x,source.y,source.zoom,source.saved));
        selectPage(inserted);return inserted;
    }
    /** Clearing an active page is one undoable edit. */
    public void clearPage(){if(!items.isEmpty())commit(new ArrayList<>());}
    /** Deletes the active page, keeping at least one sheet in the note. */
    public int deletePage(){
        if(pages.size()==1)throw new IllegalStateException("至少保留一页纸张");
        pages.remove(currentPage);currentPage=Math.min(currentPage,pages.size()-1);
        Page page=pages.get(currentPage);items=page.items;centerX=page.x;centerY=page.y;zoom=page.zoom;viewportSaved=page.saved;
        undo.clear();redo.clear();return currentPage;
    }
    /** Inserts an empty page before index (0..pageCount), selecting the new page. */
    public int insertPage(int index){if(index<0||index>pages.size())throw new IndexOutOfBoundsException();if(pages.size()>=MAX_PAGES)throw new IllegalStateException("页数已达上限");storePage();pages.add(index,new Page(new ArrayList<>(),width/2,height/2,1,false));if(currentPage>=index)currentPage++;selectPage(index);return currentPage;}
    public void movePage(int from,int to){if(from<0||to<0||from>=pages.size()||to>=pages.size())throw new IndexOutOfBoundsException();if(from==to)return;storePage();Page active=pages.get(currentPage);Page moved=pages.remove(from);pages.add(to,moved);currentPage=pages.indexOf(active);undo.clear();redo.clear();}
    public long newId(){if(nextId==Long.MAX_VALUE)throw new IllegalStateException("笔记项目已达上限");return nextId++;}
    public Item find(long id){for(Item item:items)if(item.id==id)return item;return null;}
    public void add(Item item){List<Item> next=new ArrayList<>(items);next.add(item);commit(next);}
    public void replace(Item item){List<Item> next=new ArrayList<>(items);for(int i=0;i<next.size();i++)if(next.get(i).id==item.id){next.set(i,item);commit(next);return;}}
    /** Applies group transforms, paste, delete or layer order as one history entry. */
    public void applyItems(List<Item> next){if(next==null)throw new IllegalArgumentException("无效的笔记项目");if(!items.equals(next))commit(new ArrayList<>(next));}
    public void remove(Set<Long> ids){List<Item> next=new ArrayList<>();for(Item item:items)if(!ids.contains(item.id))next.add(item);if(next.size()!=items.size())commit(next);}
    public void remove(long id){remove(Collections.singleton(id));}
    /** Commits one eraser gesture as one history entry. Preview pieces may share their source id. */
    public boolean applyErasure(List<Item> preview){
        if(preview==null||preview.equals(items))return false;
        ArrayList<Item> next=new ArrayList<>(preview.size());HashSet<Long> used=new HashSet<>();
        for(Item item:preview){
            long id=item.id;
            if(!used.add(id)){id=newId();used.add(id);item=new Item(id,item.type,item.color,item.width,item.points,item.image,item.text);}
            next.add(item);
        }
        commit(next);return true;
    }
    /** Pure geometry transform for live eraser preview; image items are untouched. */
    public static List<Item> eraseSweep(List<Item> source,double ax,double ay,double bx,double by,double radius){
        if(source==null||!finite(ax)||!finite(ay)||!finite(bx)||!finite(by)||!finite(radius)||radius<=0)
            throw new IllegalArgumentException("无效的橡皮轨迹");
        ArrayList<Item> result=new ArrayList<>();
        for(Item item:source){
            if(item.type==IMAGE||item.type==TEXT){result.add(item);continue;}
            double effective=radius+item.width/2.0;
            if(Math.max(ax,bx)+effective<item.left()||Math.min(ax,bx)-effective>item.right()||
                    Math.max(ay,by)+effective<item.top()||Math.min(ay,by)-effective>item.bottom()){
                result.add(item);continue;
            }
            List<double[]> paths=erasablePaths(item);boolean hit=false;
            for(double[] path:paths){if(path.length==2)hit=segmentDistance(path[0],path[1],ax,ay,bx,by)<=effective;
                else for(int i=2;i<path.length&&!hit;i+=2)hit=segmentsNear(path[i-2],path[i-1],path[i],path[i+1],ax,ay,bx,by,effective);
                if(hit)break;}
            if(!hit){result.add(item);continue;}
            int firstPiece=result.size();
            for(double[] path:paths){if(path.length==2){if(segmentDistance(path[0],path[1],ax,ay,bx,by)>effective)result.add(new Item(item.id,STROKE,item.color,item.width,path,""));continue;}
            ArrayList<Double> kept=new ArrayList<>();
            for(int i=2;i<path.length;i+=2){
                double x0=path[i-2],y0=path[i-1],x1=path[i],y1=path[i+1];
                double[] cut=eraseInterval(x0,y0,x1,y1,ax,ay,bx,by,effective);
                if(cut==null){if(kept.isEmpty()){kept.add(x0);kept.add(y0);}kept.add(x1);kept.add(y1);continue;}
                if(cut[0]>0){if(kept.isEmpty()){kept.add(x0);kept.add(y0);}kept.add(x0+(x1-x0)*cut[0]);kept.add(y0+(y1-y0)*cut[0]);}
                flushErasedPiece(item,kept,result);
                if(cut[1]<1){kept.add(x0+(x1-x0)*cut[1]);kept.add(y0+(y1-y0)*cut[1]);kept.add(x1);kept.add(y1);}
            }
            flushErasedPiece(item,kept,result);
            }
            if(item.type==CIRCLE||item.type==RECTANGLE)joinClosedSeam(result,firstPiece);
        }
        long points=0;if(result.size()>MAX_ITEMS)throw new IllegalArgumentException("这份笔记的图形过多，请新建一份笔记");
        for(Item item:result){points+=item.points.length/2;if(points>MAX_POINTS)throw new IllegalArgumentException("这份笔记的笔迹过多，请新建一份笔记");}
        return result;
    }
    private static void flushErasedPiece(Item source,ArrayList<Double> points,List<Item> out){
        if(!points.isEmpty()){
            double[] coordinates=new double[points.size()];for(int i=0;i<coordinates.length;i++)coordinates[i]=points.get(i);
            out.add(new Item(source.id,STROKE,source.color,source.width,coordinates,""));
            points.clear();
        }
    }
    private static void joinClosedSeam(ArrayList<Item> pieces,int firstIndex){
        if(pieces.size()-firstIndex<2)return;Item first=pieces.get(firstIndex),last=pieces.get(pieces.size()-1);
        if(Math.hypot(last.points[last.points.length-2]-first.points[0],last.points[last.points.length-1]-first.points[1])>1e-8)return;
        double[] joined=Arrays.copyOf(last.points,last.points.length+first.points.length-2);
        System.arraycopy(first.points,2,joined,last.points.length,first.points.length-2);
        pieces.set(firstIndex,new Item(first.id,STROKE,first.color,first.width,joined,""));pieces.remove(pieces.size()-1);
    }
    static List<double[]> erasablePaths(Item item){
        double[] p=item.points;if(item.type==STROKE||item.type==LINE)return Collections.singletonList(p);
        if(item.type==ARROW){double[] a=arrowHeads(p,item.width);return Arrays.asList(new double[]{p[0],p[1],p[2],p[3]},
                new double[]{p[2],p[3],a[0],a[1]},new double[]{p[2],p[3],a[2],a[3]});}
        double l=item.left(),t=item.top(),r=item.right(),b=item.bottom();
        if(item.type==RECTANGLE)return Collections.singletonList(new double[]{l,t,r,t,r,b,l,b,l,t});
        int segments=64;double[] outline=new double[(segments+1)*2];
        for(int i=0;i<=segments;i++){double angle=2*Math.PI*i/segments;outline[i*2]=(l+r)/2+(r-l)/2*Math.cos(angle);outline[i*2+1]=(t+b)/2+(b-t)/2*Math.sin(angle);}
        return Collections.singletonList(outline);
    }
    static boolean segmentsNear(double ax,double ay,double bx,double by,double cx,double cy,double dx,double dy,double radius){
        double ux=bx-ax,uy=by-ay,vx=dx-cx,vy=dy-cy,den=ux*vy-uy*vx;
        if(Math.abs(den)>1e-12){double wx=cx-ax,wy=cy-ay,s=(wx*vy-wy*vx)/den,t=(wx*uy-wy*ux)/den;if(s>=0&&s<=1&&t>=0&&t<=1)return true;}
        return segmentDistance(ax,ay,cx,cy,dx,dy)<=radius||segmentDistance(bx,by,cx,cy,dx,dy)<=radius||
                segmentDistance(cx,cy,ax,ay,bx,by)<=radius||segmentDistance(dx,dy,ax,ay,bx,by)<=radius;
    }
    /** A capsule is convex, so its intersection with each ink segment is one interval. */
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
    private void commit(List<Item> next){validateItems(next);undo.addLast(items);if(undo.size()>30)undo.removeFirst();items=next;redo.clear();}
    public boolean canUndo(){return !undo.isEmpty();}
    public boolean canRedo(){return !redo.isEmpty();}
    public boolean undo(){if(undo.isEmpty())return false;redo.addLast(items);items=undo.removeLast();return true;}
    public boolean redo(){if(redo.isEmpty())return false;undo.addLast(items);items=redo.removeLast();return true;}
    public NoteDocument copy(){storePage();NoteDocument d=new NoteDocument(template,size);d.pages.clear();for(Page p:pages)d.pages.add(new Page(new ArrayList<>(p.items),p.x,p.y,p.zoom,p.saved));d.currentPage=currentPage;d.items=d.pages.get(currentPage).items;d.nextId=nextId;d.centerX=centerX;d.centerY=centerY;d.zoom=zoom;d.viewportSaved=viewportSaved;return d;}
    private static void validateItems(List<Item> items){
        if(items.size()>MAX_ITEMS)throw new IllegalArgumentException("这份笔记的图形过多，请新建一份笔记");
        int count=0;Set<Long> ids=new HashSet<>();
        for(Item item:items){item.validate();if(!ids.add(item.id))throw new IllegalArgumentException("重复的图形编号");count+=item.points.length/2;if(count>MAX_POINTS)throw new IllegalArgumentException("这份笔记的笔迹过多，请新建一份笔记");}
    }
    public static void write(OutputStream stream,NoteDocument d)throws IOException{
        d.storePage();Set<Long> allIds=new HashSet<>();for(Page page:d.pages)for(Item item:page.items)if(!allIds.add(item.id))throw new IOException("跨页重复的图形编号");DataOutputStream out=new DataOutputStream(new BufferedOutputStream(stream));out.writeInt(MAGIC);out.writeInt(VERSION);out.writeUTF(d.template);out.writeUTF(d.size);out.writeInt(d.pages.size());out.writeInt(d.currentPage);
        for(Page page:d.pages){validateItems(page.items);if(!finite(page.x)||!finite(page.y)||!finite(page.zoom)||page.zoom<.025||page.zoom>32)throw new IOException("无效的笔记视图坐标");out.writeBoolean(page.saved);out.writeDouble(page.x);out.writeDouble(page.y);out.writeDouble(page.zoom);out.writeInt(page.items.size());for(Item item:page.items){out.writeLong(item.id);out.writeInt(item.type);out.writeInt(item.color);out.writeFloat(item.width);out.writeUTF(item.image);out.writeInt(item.points.length);for(double p:item.points)out.writeDouble(p);out.writeUTF(item.text);}}out.flush();
    }
    public static NoteDocument read(InputStream stream)throws IOException{
        try{DataInputStream in=new DataInputStream(new BufferedInputStream(stream));if(in.readInt()!=MAGIC)throw new IOException("不支持的笔记版本");int version=in.readInt();if(version<1||version>VERSION)throw new IOException("不支持的笔记版本");NoteDocument d=create(in.readUTF(),in.readUTF());int pageCount=version>=3?in.readInt():1,active=version>=3?in.readInt():0;if(pageCount<1||pageCount>MAX_PAGES||active<0||active>=pageCount)throw new IOException("笔记页数无效");d.pages.clear();Set<Long> allIds=new HashSet<>();
            for(int page=0;page<pageCount;page++){boolean saved=in.readBoolean();double x=in.readDouble(),y=in.readDouble(),scale=in.readDouble();if(!finite(x)||!finite(y)||!finite(scale)||scale<.025||scale>32)throw new IOException("笔记视图数据损坏");int count=in.readInt();if(count<0||count>MAX_ITEMS)throw new IOException("笔记项目数量无效");int total=0;ArrayList<Item> pageItems=new ArrayList<>();for(int n=0;n<count;n++){long id=in.readLong();int type=in.readInt(),color=in.readInt();float width=in.readFloat();String image=in.readUTF();int len=in.readInt();if(len<2||len%2!=0||len>MAX_POINTS*2||(total+=len/2)>MAX_POINTS)throw new IOException("笔记坐标数量无效");double[] p=new double[len];for(int j=0;j<len;j++)p[j]=in.readDouble();String text=version==1?"":in.readUTF();if(!allIds.add(id))throw new IOException("重复的图形编号");pageItems.add(new Item(id,type,color,width,p,image,text));if(id==Long.MAX_VALUE)throw new IOException("笔记项目编号无效");d.nextId=Math.max(d.nextId,id+1);}validateItems(pageItems);d.pages.add(new Page(pageItems,x,y,scale,saved));}d.currentPage=active;Page p=d.pages.get(active);d.items=p.items;d.centerX=p.x;d.centerY=p.y;d.zoom=p.zoom;d.viewportSaved=p.saved;if(in.read()!=-1)throw new IOException("笔记末尾数据损坏");return d;
        }catch(IllegalArgumentException e){throw new IOException("笔记数据损坏",e);}
    }
    public static double segmentDistance(double x,double y,double ax,double ay,double bx,double by){double dx=bx-ax,dy=by-ay;double denominator=dx*dx+dy*dy;double t=denominator==0?0:Math.max(0,Math.min(1,((x-ax)*dx+(y-ay)*dy)/denominator));return Math.hypot(x-ax-t*dx,y-ay-t*dy);}
    static double[] arrowHeads(double[] p,float width){double a=Math.atan2(p[3]-p[1],p[2]-p[0]),length=Math.min(Math.hypot(p[2]-p[0],p[3]-p[1])*.4,Math.max(12,width*4));return new double[]{p[2]-length*Math.cos(a-.5),p[3]-length*Math.sin(a-.5),p[2]-length*Math.cos(a+.5),p[3]-length*Math.sin(a+.5)};}
    static boolean finite(double value){return !Double.isNaN(value)&&!Double.isInfinite(value);}
}
