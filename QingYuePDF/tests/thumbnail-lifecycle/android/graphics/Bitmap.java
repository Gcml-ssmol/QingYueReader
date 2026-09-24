package android.graphics;
public final class Bitmap {
    public enum Config {ARGB_8888}
    private final int width,height;private volatile boolean recycled;
    private Bitmap(int width,int height){this.width=width;this.height=height;}
    public static Bitmap createBitmap(int width,int height,Config config){return new Bitmap(width,height);}
    public int getAllocationByteCount(){return width*height*4;}
    public void eraseColor(int color){if(recycled)throw new IllegalStateException("recycled");}
    public void draw(){if(recycled)throw new IllegalStateException("recycled bitmap cannot be drawn");}
    public boolean isRecycled(){return recycled;}
    public void recycle(){recycled=true;}
}
