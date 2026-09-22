package cn.qingyue.pdf;

/** Gesture regressions: input is in screen pixels and uses actual pointer identities. */
public final class TwoFingerTapTest {
    private static TwoFingerTap tap;
    private static boolean send(int action,int changed,long time,int[] ids,int[] tools,float... xy){
        float[] x=new float[ids.length],y=new float[ids.length];for(int i=0;i<ids.length;i++){x[i]=xy[i*2];y[i]=xy[i*2+1];}
        return tap.event(action,changed,time,ids,tools,x,y);
    }
    private static boolean fingers(int action,int changed,long time,int[] ids,float... xy){int[] tools=new int[ids.length];java.util.Arrays.fill(tools,1);return send(action,changed,time,ids,tools,xy);}
    private static void start(){tap=new TwoFingerTap(7);fingers(0,4,1000,new int[]{4},100,200);}
    private static void pair(){start();fingers(5,9,1040,new int[]{4,9},100,200,180,200);check(tap.pairPending(),"pair candidate");}
    private static boolean finish(long first,long last){fingers(6,4,first,new int[]{4,9},100,200,180,200);return fingers(1,9,last,new int[]{9},180,200);}
    private static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    public static void main(String[] args){
        pair();check(finish(1100,1130),"ordinary two-finger tap");check(!fingers(1,9,1140,new int[]{9},180,200),"only once");
        pair();fingers(2,9,1080,new int[]{9,4},182,201,101,201);fingers(6,9,1100,new int[]{9,4},182,201,101,201);check(fingers(1,4,1110,new int[]{4},101,201),"reordered pointers and reverse release");
        start();check(tap.firstPending(),"single touch is deferred");check(!fingers(1,4,1080,new int[]{4},100,200),"single tap is not undo");
        start();fingers(5,9,1121,new int[]{4,9},100,200,180,200);check(!finish(1160,1190),"late second finger");
        start();fingers(2,4,1030,new int[]{4},108,200);fingers(5,9,1040,new int[]{4,9},100,200,180,200);check(!finish(1100,1120),"first finger excursion returning to origin");
        pair();fingers(2,4,1070,new int[]{4,9},91,200,189,200);check(!finish(1100,1120),"pinch rejected");
        pair();fingers(2,4,1070,new int[]{4,9},95,200,185,200);check(!finish(1100,1120),"span change rejected even if individual movement is within slop");
        pair();fingers(2,4,1070,new int[]{4,9},100,209,180,209);check(!finish(1100,1120),"two-finger drag rejected");
        pair();check(!finish(1240,1290),"long total duration rejected");
        pair();check(!finish(1100,1211),"slow second lift rejected");
        pair();fingers(5,12,1080,new int[]{4,9,12},100,200,180,200,260,200);check(!finish(1100,1120),"third finger rejected");
        pair();send(2,4,1080,new int[]{4,9},new int[]{2,1},100,200,180,200);check(!finish(1100,1120),"stylus/palm rejected");
        tap=new TwoFingerTap(7);send(0,4,1000,new int[]{4},new int[]{4},100,200);fingers(5,9,1040,new int[]{4,9},100,200,180,200);check(!finish(1100,1120),"eraser pen rejected");
        pair();fingers(3,4,1080,new int[]{4,9},100,200,180,200);check(!finish(1100,1120),"cancel rejected");
        pair();fingers(2,9,1080,new int[]{9},180,200);check(!finish(1100,1120),"missing pointer rejected");
        pair();fingers(6,4,1100,new int[]{4,9},100,200,180,200);fingers(5,4,1120,new int[]{9,4},180,200,100,200);check(!finish(1140,1160),"re-touch rejected");
        pair();tap.reset();check(!finish(1100,1120),"settings reset cancels pending gesture");
        // A view moving up must not turn a stationary screen-space tap into a drag.
        pair();fingers(2,4,1080,new int[]{4,9},100,200,180,200);check(finish(1100,1120),"screen coordinates stay stable after toolbar collapse");
        start();fingers(5,9,1120,new int[]{4,9},100,200,180,200);check(finish(1170,1280),"inclusive timing boundaries");
        System.out.println("PASS: two-finger timing, motion and pinch rejection, identities, stylus/palm, third touch, cancellation, single-tap separation and duplicate prevention");
    }
}
