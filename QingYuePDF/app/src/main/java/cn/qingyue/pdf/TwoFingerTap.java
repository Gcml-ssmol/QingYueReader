package cn.qingyue.pdf;

/** Strict two-finger tap recognizer, independent of Android for gesture-policy tests. */
public final class TwoFingerTap {
    public static final long JOIN_MS=120, TOTAL_MS=280, RELEASE_MS=110;
    private static final int IDLE=0,FIRST=1,PAIR=2,LIFTED=3,REJECTED=4;
    private final float slop;
    private int state=IDLE,first=-1,second=-1,lifted=-1;
    private long started,firstLift;
    private float firstX,firstY,secondX,secondY,initialSpan;

    public TwoFingerTap(float slop){if(!(slop>0))throw new IllegalArgumentException("slop");this.slop=slop;}
    public void reset(){state=IDLE;first=second=lifted=-1;}
    public void reject(){state=REJECTED;}
    public boolean firstPending(){return state==FIRST;}
    public boolean pairPending(){return state==PAIR||state==LIFTED;}

    /** Action values match MotionEvent. Coordinates must stay in a stable screen space. */
    public boolean event(int action,int changedId,long time,int[] ids,int[] tools,float[] xs,float[] ys){
        if(action==0){
            reset();if(ids.length!=1||tools[0]!=1){reject();return false;}
            state=FIRST;first=ids[0];firstX=xs[0];firstY=ys[0];started=time;return false;
        }
        if(state==IDLE||state==REJECTED)return false;
        if(action==3||ids.length>2||time<started||time-started>TOTAL_MS){reject();return false;}
        for(int tool:tools)if(tool!=1){reject();return false;}
        int a=indexOf(ids,first),b=indexOf(ids,second);
        if(a>=0&&moved(xs[a],ys[a],firstX,firstY)||b>=0&&moved(xs[b],ys[b],secondX,secondY)){reject();return false;}
        if(state==FIRST){
            if(a<0||time-started>JOIN_MS){reject();return false;}
            if(action==5){
                int other=indexOf(ids,changedId);
                if(ids.length!=2||other<0||changedId==first){reject();return false;}
                second=changedId;secondX=xs[other];secondY=ys[other];initialSpan=span(xs[a],ys[a],secondX,secondY);state=PAIR;
            }else if(action==1||ids.length!=1)reject();
            return false;
        }
        if(state==PAIR){
            if(ids.length!=2||a<0||b<0||action==5||Math.abs(span(xs[a],ys[a],xs[b],ys[b])-initialSpan)>slop){reject();return false;}
            if(action==6){
                if(changedId!=first&&changedId!=second){reject();return false;}
                lifted=changedId;firstLift=time;state=LIFTED;
            }else if(action==1)reject();
            return false;
        }
        int remaining=lifted==first?second:first;
        if(ids.length!=1||ids[0]!=remaining||time-firstLift>RELEASE_MS||action==5||action==6){reject();return false;}
        if(action==1&&changedId==remaining){reset();return true;}
        return false;
    }
    private boolean moved(float x,float y,float ox,float oy){return Float.isNaN(x)||Float.isInfinite(x)||Float.isNaN(y)||Float.isInfinite(y)||span(x,y,ox,oy)>slop;}
    private static float span(float ax,float ay,float bx,float by){return (float)Math.hypot(ax-bx,ay-by);}
    private static int indexOf(int[] ids,int id){for(int i=0;i<ids.length;i++)if(ids[i]==id)return i;return -1;}
}
