package cn.qingyue.pdf;

/** Single-page fitting and bounded horizontal navigation, independent of Android. */
public final class HorizontalPaging {
    private HorizontalPaging(){}
    public static float fitWidth(float viewportWidth,float viewportHeight,double ratio,float inset){
        return Math.max(1,(float)Math.min(Math.max(1,viewportWidth-inset*2),Math.max(1,viewportHeight-inset*2)/ratio));
    }
    public static int target(int page,int count,float dx,float dy,float threshold,float initialPan,float maxPan){
        if(dx==0)return page;
        int delta=ReaderNavigation.horizontalPage(dx,dy,threshold,initialPan,maxPan);
        return Math.max(0,Math.min(count-1,page+delta));
    }
    public static float clampPan(float pan,float contentSize,float viewportSize,float inset){
        float limit=Math.max(0,(contentSize-viewportSize+inset*2)/2);
        return Math.max(-limit,Math.min(limit,pan));
    }
}
