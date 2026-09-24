package android.graphics;
/** Counts native-path construction calls; does not claim Android GPU timing. */
public final class Path {
    public static long created,verbs;
    public float x,y;public long fingerprint=1;
    public Path(){created++;}
    private void point(float x,float y){this.x=x;this.y=y;fingerprint=31*fingerprint+Float.floatToIntBits(x);fingerprint=31*fingerprint+Float.floatToIntBits(y);}
    public void moveTo(float x,float y){verbs++;point(x,y);}
    public void lineTo(float x,float y){verbs++;point(x,y);}
    public void cubicTo(float a,float b,float c,float d,float e,float f){verbs++;point(a,b);point(c,d);point(e,f);}
    public void close(){verbs++;fingerprint=31*fingerprint+7;}
}
