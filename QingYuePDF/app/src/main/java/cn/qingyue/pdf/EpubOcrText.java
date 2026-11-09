package cn.qingyue.pdf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Restores line indentation from Tesseract word boxes; falls back to its plain text. */
public final class EpubOcrText {
    private EpubOcrText() { }
    /** Preserve every plain-text line when geometric evidence is missing or incomplete. */
    public static List<EpubBook.Line> plainLines(String plain) {
        String value=plain==null?"":plain.replace("\r\n","\n").replace('\r','\n');
        List<EpubBook.Line> result=new ArrayList<>();
        for(String line:value.split("\n",-1))result.add(new EpubBook.Line(line,0,0,0,0,-1));
        return result;
    }
    public static List<EpubBook.Line> structuredLines(String plain,String hocr,int confidence) {
        return structuredLines(plain,hocr,confidence,0,0);
    }
    /** hOCR pixels are mapped into the PDF page's point coordinate system. */
    public static List<EpubBook.Line> structuredLines(String plain,String hocr,int confidence,float width,float height) {
        if(hocr==null||hocr.isEmpty())return plainLines(plain);
        OcrHocr.Page page;
        try { page=OcrHocr.parse(hocr); } catch(RuntimeException bad){ return plainLines(plain); }
        if(page.words.isEmpty())return plainLines(plain);
        List<Line> geometry=group(page);
        StringBuilder joined=new StringBuilder();List<EpubBook.Line> result=new ArrayList<>();
        List<Integer> lefts=new ArrayList<>(),heights=new ArrayList<>();
        for(Line l:geometry)if(l.text.length()>=5){lefts.add(l.left);heights.add(l.bottom-l.top);}
        if(lefts.isEmpty())return plainLines(plain);
        Collections.sort(lefts);Collections.sort(heights);
        int normalLeft=lefts.get(lefts.size()/4),lineHeight=heights.get(heights.size()/2);
        for(Line l:geometry){
            String text=(l.text.length()>=5&&l.left-normalLeft>=Math.max(8,lineHeight)?"  ":"")+l.text;
            if(joined.length()>0)joined.append('\n');joined.append(text);
            float sx=width>0?width/page.width:1f,sy=height>0?height/page.height:1f;
            result.add(new EpubBook.Line(text,l.left*sx,l.top*sy,l.right*sx,l.bottom*sy,confidence));
        }
        return compact(joined.toString()).equals(compact(plain==null?"":plain))?result:plainLines(plain);
    }
    public static String reflowHints(String plain, String hocr) {
        if (plain == null) plain = "";
        if (hocr == null || hocr.isEmpty()) return plain;
        OcrHocr.Page page;
        try { page = OcrHocr.parse(hocr); }
        catch (RuntimeException invalid) { return plain; }
        if (page.words.isEmpty()) return plain;
        List<Line> lines = group(page);
        if (lines.isEmpty()) return plain;
        List<Integer> lefts = new ArrayList<>(), heights = new ArrayList<>();
        for (Line line : lines) if (line.text.length() >= 5) {
            lefts.add(line.left); heights.add(line.bottom - line.top);
        }
        if (lefts.isEmpty()) return plain;
        Collections.sort(lefts); Collections.sort(heights);
        int normalLeft = lefts.get(Math.max(0, lefts.size() / 4));
        int lineHeight = heights.get(heights.size() / 2);
        StringBuilder output = new StringBuilder();
        for (Line line : lines) {
            if (line.text.length() == 0) continue;
            if (output.length() > 0) output.append('\n');
            if (line.text.length() >= 5 && line.left - normalLeft >= Math.max(8, lineHeight))
                output.append("  ");
            output.append(line.text);
        }
        if (!compact(output.toString()).equals(compact(plain))) return plain;
        return output.toString();
    }
    private static List<Line> group(OcrHocr.Page page) {
        List<OcrHocr.Word> sorted=new ArrayList<>(page.words);
        Collections.sort(sorted,(a,b)->a.top!=b.top?Integer.compare(a.top,b.top):Integer.compare(a.left,b.left));
        List<Line> lines=new ArrayList<>();Line current=null;
        for(OcrHocr.Word word:sorted){
            if(current==null||word.top>current.bottom-Math.max(2,(current.bottom-current.top)/3)){
                current=new Line(word);lines.add(current);
            }else current.add(word);
        }
        return lines;
    }
    private static String compact(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) if (!Character.isWhitespace(text.charAt(i)))
            result.append(text.charAt(i));
        return result.toString();
    }
    private static final class Line {
        int left, right, top, bottom; final StringBuilder text = new StringBuilder();
        Line(OcrHocr.Word word) { left=word.left; right=word.right; top=word.top; bottom=word.bottom; text.append(word.text); }
        void add(OcrHocr.Word word) {
            if (text.length() > 0 && space(text.charAt(text.length()-1),word.text.charAt(0))) text.append(' ');
            text.append(word.text); left=Math.min(left,word.left); right=Math.max(right,word.right);
            top=Math.min(top,word.top); bottom=Math.max(bottom,word.bottom);
        }
        private static boolean space(char a, char b) { return a < 128 && b < 128
                && Character.isLetterOrDigit(a) && Character.isLetterOrDigit(b); }
    }
}
