package android.text;
public class TextPaint extends android.graphics.Paint {
    private float size=20;
    public TextPaint(int flags){super(flags);}
    @Override public void setTextSize(float value){size=value;super.setTextSize(value);}
    public float size(){return size;}
}
