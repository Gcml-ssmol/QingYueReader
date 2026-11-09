package android.graphics;
public final class Bitmap {
    public enum Config { ARGB_8888 }
    private final int width,height;private final int[] pixels;private boolean recycled;
    public boolean rotated;
    public Bitmap(int width,int height){this.width=width;this.height=height;pixels=new int[width*height];
        java.util.Arrays.fill(pixels,Color.WHITE);}
    public static Bitmap createBitmap(int width,int height,Config config){return new Bitmap(width,height);}
    public int getWidth(){return width;}public int getHeight(){return height;}
    public boolean isRecycled(){return recycled;}public void recycle(){recycled=true;}
    public void setPixel(int x,int y,int value){pixels[y*width+x]=value;}
    public int getPixel(int x,int y){return pixels[y*width+x];}
    public void getPixels(int[] target,int offset,int stride,int x,int y,int w,int h){
        for(int row=0;row<h;row++)System.arraycopy(pixels,(y+row)*width+x,target,offset+row*stride,w);
    }
    public void eraseColor(int color){java.util.Arrays.fill(pixels,color);}
    void copyFrom(Bitmap source){System.arraycopy(source.pixels,0,pixels,0,pixels.length);}
}
