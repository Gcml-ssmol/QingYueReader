package cn.qingyue.pdf;

/** Bounds bitmap allocation, including malformed or unusually proportioned pages. */
public final class PageMath {
    private PageMath() {}
    public static int[] dimensions(int pageWidth,int pageHeight,int requestedWidth) {
        if(pageWidth<=0||pageHeight<=0||requestedWidth<=0)throw new IllegalArgumentException("Invalid page dimensions");
        double ratio=(double)pageHeight/pageWidth;
        double width=Math.min(Math.min(requestedWidth,8192),Math.sqrt(6000000/ratio));
        width=Math.min(width,8192/ratio);
        return new int[]{Math.max(1,(int)width),Math.max(1,(int)(width*ratio))};
    }
}
