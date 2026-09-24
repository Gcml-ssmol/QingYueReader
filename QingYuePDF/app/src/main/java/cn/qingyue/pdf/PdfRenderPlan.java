package cn.qingyue.pdf;

import java.util.*;

/** Pure geometry for quantized visible-page detail tiles. */
public final class PdfRenderPlan {
    public static final int TILE_SIZE=512, MAX_RENDER_WIDTH=8192;
    private static final int[] LEVELS={2048,3072,4096,5120,6144,8192};
    public static final class Key {
        public final int page,renderWidth,column,row;
        public Key(int page,int renderWidth,int column,int row){this.page=page;this.renderWidth=renderWidth;this.column=column;this.row=row;}
        @Override public boolean equals(Object other){if(!(other instanceof Key))return false;Key k=(Key)other;return page==k.page&&renderWidth==k.renderWidth&&column==k.column&&row==k.row;}
        @Override public int hashCode(){int h=page;h=h*31+renderWidth;h=h*31+column;return h*31+row;}
        @Override public String toString(){return page+"@"+renderWidth+":"+column+","+row;}
    }
    public static final class Tile {
        public final Key key;public final int left,top,width,height;
        Tile(Key key,int left,int top,int width,int height){this.key=key;this.left=left;this.top=top;this.width=width;this.height=height;}
        public float pageLeft(){return left/(float)key.renderWidth;}
        public float pageTop(){return top/(float)key.renderWidth;}
        public float pageRight(){return (left+width)/(float)key.renderWidth;}
        public float pageBottom(){return (top+height)/(float)key.renderWidth;}
        public long bytes(){return (long)width*height*4;}
    }
    private PdfRenderPlan(){}

    /** Returns zero while the preview still has enough physical pixels. */
    public static int renderWidth(int previewWidth,float displayedWidth){
        if(previewWidth<=0||!(displayedWidth>0)||displayedWidth<=previewWidth*1.12f)return 0;
        int wanted=(int)Math.ceil(Math.min(MAX_RENDER_WIDTH,displayedWidth*1.12f));
        for(int level:LEVELS)if(level>=wanted)return level;return MAX_RENDER_WIDTH;
    }

    /**
     * Creates only tiles intersecting the visible page-coordinate rectangle. One unit is
     * page width; a one-tile margin prevents blank edges during a small follow-up pan.
     */
    public static List<Tile> visible(int page,int renderWidth,float pageRatio,
            float visibleLeft,float visibleTop,float visibleRight,float visibleBottom){
        if(page<0||renderWidth<=0||renderWidth>MAX_RENDER_WIDTH||!(pageRatio>0)||Float.isInfinite(pageRatio))throw new IllegalArgumentException("Invalid render plan");
        int pageHeight=Math.max(1,(int)Math.ceil(renderWidth*(double)pageRatio));
        float l=clamp(Math.min(visibleLeft,visibleRight),0,1),r=clamp(Math.max(visibleLeft,visibleRight),0,1);
        float t=clamp(Math.min(visibleTop,visibleBottom),0,pageRatio),b=clamp(Math.max(visibleTop,visibleBottom),0,pageRatio);
        int firstColumn=Math.max(0,(int)Math.floor(l*renderWidth/TILE_SIZE)-1),lastColumn=Math.min((renderWidth-1)/TILE_SIZE,(int)Math.floor(Math.max(0,r*renderWidth-1)/TILE_SIZE)+1);
        int firstRow=Math.max(0,(int)Math.floor(t*renderWidth/TILE_SIZE)-1),lastRow=Math.min((pageHeight-1)/TILE_SIZE,(int)Math.floor(Math.max(0,b*renderWidth-1)/TILE_SIZE)+1);
        ArrayList<Tile> result=new ArrayList<>();
        for(int row=firstRow;row<=lastRow;row++)for(int column=firstColumn;column<=lastColumn;column++){
            int left=column*TILE_SIZE,top=row*TILE_SIZE,width=Math.min(TILE_SIZE,renderWidth-left),height=Math.min(TILE_SIZE,pageHeight-top);
            result.add(new Tile(new Key(page,renderWidth,column,row),left,top,width,height));
        }
        // Render useful pixels before the surrounding prefetch margin; each worker batch is small.
        final float centerX=(l+r)/2,centerY=(t+b)/2;
        Collections.sort(result,(a,z)->{
            boolean av=a.pageRight()>l&&a.pageLeft()<r&&a.pageBottom()>t&&a.pageTop()<b;
            boolean zv=z.pageRight()>l&&z.pageLeft()<r&&z.pageBottom()>t&&z.pageTop()<b;
            if(av!=zv)return av?-1:1;
            double ad=Math.pow((a.pageLeft()+a.pageRight())/2-centerX,2)+Math.pow((a.pageTop()+a.pageBottom())/2-centerY,2);
            double zd=Math.pow((z.pageLeft()+z.pageRight())/2-centerX,2)+Math.pow((z.pageTop()+z.pageBottom())/2-centerY,2);
            return Double.compare(ad,zd);
        });
        return Collections.unmodifiableList(result);
    }
    private static float clamp(float value,float min,float max){return Math.max(min,Math.min(max,value));}
}
