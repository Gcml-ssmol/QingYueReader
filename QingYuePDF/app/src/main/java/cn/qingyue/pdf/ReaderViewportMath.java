package cn.qingyue.pdf;

/** Pure page-anchor geometry for restoring a PDF viewport after a size change. */
final class ReaderViewportMath {
    private ReaderViewportMath() { }
    static float horizontalAnchor(float pageWidth,float panX){
        return 0.5f-panX/pageWidth;
    }
    static float panX(float pageWidth,float anchorX){return (0.5f-anchorX)*pageWidth;}
    static double verticalAnchor(double scroll,double pageTop,double pageHeight,double viewport){
        return (scroll+viewport/2-pageTop)/pageHeight;
    }
    static double scroll(double pageTop,double pageHeight,double viewport,double anchorY){
        return pageTop+anchorY*pageHeight-viewport/2;
    }
    /** The reader's current-page probe is above center in continuous mode. */
    static double selectionProbe(double viewport){return Math.min(viewport*0.35,0.5);}
    static double selectionAnchor(double scroll,double pageTop,double pageHeight,double viewport){
        return (scroll+selectionProbe(viewport)-pageTop)/pageHeight;
    }
    static double selectionScroll(double pageTop,double pageHeight,double viewport,double anchorY){
        return pageTop+anchorY*pageHeight-selectionProbe(viewport);
    }
    static float horizontalPageAnchor(float pagePixels,float panY){
        return 0.5f-panY/pagePixels;
    }
    static float panY(float pagePixels,float anchorY){return (0.5f-anchorY)*pagePixels;}
}
