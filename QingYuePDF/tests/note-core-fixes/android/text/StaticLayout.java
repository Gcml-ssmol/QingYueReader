package android.text;
public class StaticLayout extends Layout {
    private final int height;
    public StaticLayout(String text,TextPaint paint,int width,Alignment alignment,float spacingMultiplier,float spacingAdd,boolean includePad){
        int lines=0,charsPerLine=Math.max(1,(int)(width/Math.max(1,paint.size())));
        for(String line:text.split("\\n",-1))lines+=Math.max(1,(line.length()+charsPerLine-1)/charsPerLine);
        height=(int)Math.ceil(lines*paint.size()*1.25);
    }
    public int getHeight(){return height;}
}
