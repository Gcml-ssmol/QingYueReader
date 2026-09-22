package cn.qingyue.pdf;

/** Navigation intent is separate from ink input and applies in pen-only mode too. */
public final class ReaderNavigation {
    private ReaderNavigation(){}
    public static int horizontalPage(float dx,float dy,float threshold,float initialPan,float maxPan){
        if(Math.abs(dx)<threshold||Math.abs(dx)<Math.abs(dy)*1.6f)return 0;
        // A zoomed page consumes horizontal gestures until its starting edge is reached.
        if(dx<0&&initialPan>-maxPan+2)return 0;
        if(dx>0&&initialPan<maxPan-2)return 0;
        return dx<0?1:-1;
    }
}
