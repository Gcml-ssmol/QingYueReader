package cn.qingyue.pdf;

import android.graphics.Canvas;
import android.graphics.Paint;
import java.util.ArrayList;
import java.util.List;

/** Shared layout for editable page text in reader and PNG export. */
final class PdfTextBoxes {
    private PdfTextBoxes(){}
    static List<String> lines(String text){String[] source=text.split("\\n",-1);ArrayList<String> result=new ArrayList<>();for(String line:source)result.add(line);return result;}
    static float lineHeight(ReaderDocument.TextBox box){return box.size*1.25f;}
    static float estimatedWidth(ReaderDocument.TextBox box){float width=0;for(String line:lines(box.text))width=Math.max(width,line.codePointCount(0,line.length())*box.size*.95f);return Math.max(width,.06f);}
    static void draw(Canvas canvas,Paint paint,ReaderDocument.TextBox box){
        draw(canvas,paint,box,box.color);
    }
    static void draw(Canvas canvas,Paint paint,ReaderDocument.TextBox box,int displayColor){
        paint.setStyle(Paint.Style.FILL);paint.setColor(displayColor);paint.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL));paint.setTextAlign(Paint.Align.LEFT);paint.setTextSize(box.size);
        int index=0;for(String line:lines(box.text)){canvas.drawText(line,box.x,box.y+box.size+index*lineHeight(box),paint);index++;}
    }
    static boolean hit(ReaderDocument.TextBox box,float x,float y){
        return x>=box.x-.01f&&x<=box.x+estimatedWidth(box)+.02f&&y>=box.y-.01f&&y<=box.y+lines(box.text).size()*lineHeight(box)+.015f;
    }
}
