package android.graphics;
import java.io.OutputStream;
public class Bitmap {
    public enum Config {ARGB_8888} public enum CompressFormat {PNG}
    public static Bitmap lastCreated;private final int width,height;private boolean recycled;
    public Bitmap(int width,int height){this.width=width;this.height=height;}
    public static Bitmap createBitmap(int width,int height,Config config){return lastCreated=new Bitmap(width,height);}
    public void eraseColor(int color){} public int getWidth(){return width;} public int getHeight(){return height;}
    public boolean compress(CompressFormat format,int quality,OutputStream output){return true;}
    public boolean isRecycled(){return recycled;} public void recycle(){recycled=true;}
}
