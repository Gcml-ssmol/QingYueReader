package android.graphics;
import java.io.OutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
public class Bitmap {
    public enum CompressFormat { JPEG }
    private final BufferedImage image;
    private boolean recycled;
    public Bitmap(boolean blank) {
        image=new BufferedImage(160,220,BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g=image.createGraphics();g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,160,220);
        if(!blank){g.setColor(java.awt.Color.BLACK);g.fillRect(20,40,100,20);}g.dispose();
    }
    public int getWidth(){return image.getWidth();}public int getHeight(){return image.getHeight();}
    public int getPixel(int x,int y){return image.getRGB(x,y);}
    public boolean compress(CompressFormat format,int quality,OutputStream out){
        try{return ImageIO.write(image,"jpg",out);}catch(java.io.IOException failed){return false;}}
    public boolean isRecycled(){return recycled;}public void recycle(){recycled=true;}
}
