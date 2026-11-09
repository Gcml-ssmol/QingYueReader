package cn.qingyue.pdf;

/** Display-only ink adjustment. Low-alpha highlights retain their hue and blend behavior. */
final class NightInk {
    private static final double[] LINEAR = new double[256];
    static {
        for (int i=0;i<256;i++) {
            double v=i/255.0;
            LINEAR[i]=v<=0.04045?v/12.92:Math.pow((v+0.055)/1.055,2.4);
        }
    }
    private NightInk() { }
    static int display(int argb,int paper) {
        int alpha=(argb>>>24)&255;
        // A translucent marker is a color overlay, not body text. Do not turn it white.
        if(alpha<160||contrastOnPaper(argb,paper)>=4.5)return argb;
        int red=(argb>>>16)&255,green=(argb>>>8)&255,blue=argb&255;
        for(int lift=16;lift<=256;lift+=16){
            int amount=Math.min(255,lift);
            int candidate=(alpha<<24)|((red+(255-red)*amount/255)<<16)
                    |((green+(255-green)*amount/255)<<8)|(blue+(255-blue)*amount/255);
            if(contrastOnPaper(candidate,paper)>=4.5||amount==255)return candidate;
        }
        return argb;
    }
    static double contrastOnPaper(int ink,int paper) {
        int a=(ink>>>24)&255;
        int r=blend((ink>>>16)&255,(paper>>>16)&255,a);
        int g=blend((ink>>>8)&255,(paper>>>8)&255,a);
        int b=blend(ink&255,paper&255,a);
        double foreground=luminance(r,g,b);
        double background=luminance((paper>>>16)&255,(paper>>>8)&255,paper&255);
        return (Math.max(foreground,background)+0.05)/(Math.min(foreground,background)+0.05);
    }
    private static int blend(int fg,int bg,int alpha){return (fg*alpha+bg*(255-alpha)+127)/255;}
    private static double luminance(int r,int g,int b){
        return LINEAR[r]*0.2126+LINEAR[g]*0.7152+LINEAR[b]*0.0722;
    }
}
