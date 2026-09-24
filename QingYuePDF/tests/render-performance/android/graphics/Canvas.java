package android.graphics;
public final class Canvas {
    public long draws;public Path path;public int color;public Paint.Style style;
    public void drawPath(Path p,Paint paint){draws++;path=p;color=paint.color;style=paint.style;}
    public void drawCircle(float x,float y,float r,Paint paint){draws++;path=null;color=paint.color;style=paint.style;}
}
