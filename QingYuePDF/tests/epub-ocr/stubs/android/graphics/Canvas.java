package android.graphics;
public final class Canvas {
    private final Bitmap target;private float angle;
    public Canvas(Bitmap target){this.target=target;}
    public void drawColor(int color){target.eraseColor(color);}
    public void rotate(float angle,float cx,float cy){this.angle=angle;}
    public void drawBitmap(Bitmap source,float x,float y,Paint paint){target.copyFrom(source);target.rotated=Math.abs(angle)>.01f;}
}
