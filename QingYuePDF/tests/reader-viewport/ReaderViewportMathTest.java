package cn.qingyue.pdf;

public final class ReaderViewportMathTest {
    public static void main(String[] args) {
        float pageWidth=970,panX=-118;
        float anchorX=ReaderViewportMath.horizontalAnchor(pageWidth,panX);
        near(ReaderViewportMath.panX(pageWidth,anchorX),panX,"horizontal anchor roundtrip");
        double pageTop=2.1,pageHeight=1.42,viewport=.73,scroll=2.58;
        double anchorY=ReaderViewportMath.verticalAnchor(scroll,pageTop,pageHeight,viewport);
        near(ReaderViewportMath.scroll(pageTop,pageHeight,viewport,anchorY),scroll,"vertical roundtrip");
        float pagePixels=1275,panY=207;
        float horizontalY=ReaderViewportMath.horizontalPageAnchor(pagePixels,panY);
        near(ReaderViewportMath.panY(pagePixels,horizontalY),panY,"horizontal paging roundtrip");
        // A new view width keeps the same page-relative center even when pixels change.
        near(ReaderViewportMath.horizontalAnchor(1130,ReaderViewportMath.panX(1130,anchorX)),anchorX,
                "width change retains anchor");
        double restored=ReaderViewportMath.scroll(pageTop,1.7,viewport,anchorY);
        near(ReaderViewportMath.verticalAnchor(restored,pageTop,1.7,viewport),anchorY,
                "late PDF page-ratio correction retains anchor");
        // The screen center is already in page 2, but the reader's 35%-from-top
        // current-page probe still belongs to page 1. A saved snapshot must keep
        // both the viewport position and the page label after recreation.
        double boundaryScroll=.70,boundaryViewport=.80,firstPageTop=0,firstPageHeight=1;
        check(boundaryScroll+ReaderViewportMath.selectionProbe(boundaryViewport)<1,
                "current-page probe stays on first page");
        check(boundaryScroll+boundaryViewport/2>1,"screen center crosses page boundary");
        double selectionY=ReaderViewportMath.selectionAnchor(boundaryScroll,
                firstPageTop,firstPageHeight,boundaryViewport);
        near(ReaderViewportMath.selectionScroll(firstPageTop,firstPageHeight,
                boundaryViewport,selectionY),boundaryScroll,"cross-page selection roundtrip");
        double tallerViewport=1.1;
        double tallerScroll=ReaderViewportMath.selectionScroll(firstPageTop,firstPageHeight,
                tallerViewport,selectionY);
        near(tallerScroll+ReaderViewportMath.selectionProbe(tallerViewport),selectionY,
                "resize retains selected page probe");
        System.out.println("ReaderViewportMathTest OK");
    }
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    private static void near(double actual,double expected,String label){
        if(Math.abs(actual-expected)>0.0001)throw new AssertionError(label+": "+actual+" != "+expected);
    }
}
