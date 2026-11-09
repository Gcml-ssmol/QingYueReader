package android.graphics;
import java.io.InputStream;
import javax.imageio.ImageIO;
public final class BitmapFactory {
    public static final class Options { public boolean inJustDecodeBounds;public int outWidth,outHeight; }
    public static Bitmap decodeStream(InputStream in,Rect rect,Options options){
        try{java.awt.image.BufferedImage image=ImageIO.read(in);
            if(image!=null){options.outWidth=image.getWidth();options.outHeight=image.getHeight();}
            return null;
        }catch(java.io.IOException failed){return null;}
    }
}
