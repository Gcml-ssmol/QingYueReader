package android.graphics;
public final class Paint {
    public enum Style{FILL,STROKE} public enum Cap{ROUND} public enum Join{ROUND}
    public int color;public float width;public Style style;
    public void setColor(int c){color=c;}public void setStrokeWidth(float w){width=w;}
    public void setStyle(Style s){style=s;}public void setStrokeCap(Cap c){}public void setStrokeJoin(Join j){}
}
