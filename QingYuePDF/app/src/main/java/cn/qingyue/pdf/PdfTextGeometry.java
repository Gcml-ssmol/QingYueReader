package cn.qingyue.pdf;

import java.text.Normalizer;
import java.util.*;

/** Normalizes extracted text while retaining a conservative mapping to page boxes. */
final class PdfTextGeometry {
    static final class Match {
        final String text;final List<ReaderDocument.Box> boxes;
        Match(String text,List<ReaderDocument.Box> boxes){this.text=text;this.boxes=boxes;}
    }
    private PdfTextGeometry(){}

    /** One selectable range per displayed code point, in PDFTextStripper reading order. */
    static PdfTextSearch.PageText selectablePage(int page,String source,List<ReaderDocument.Box> sourceBoxes){
        Normalized normalized=normalize(source,sourceBoxes);
        ArrayList<PdfTextSearch.Token> glyphs=new ArrayList<>();
        for(int i=0;i<normalized.display.length();){
            int end=i+Character.charCount(normalized.display.codePointAt(i));
            ReaderDocument.Box box=union(normalized.boxes,i,end);
            if(box!=null&&!Character.isWhitespace(normalized.display.codePointAt(i)))
                glyphs.add(new PdfTextSearch.Token(normalized.display.substring(i,end),box,i,end));
            i=end;
        }
        return new PdfTextSearch.PageText(page,normalized.display,glyphs);
    }

    static int nearestGlyph(List<PdfTextSearch.Token> glyphs,float x,float y){
        int nearest=-1;float bestVertical=Float.MAX_VALUE,bestHorizontal=Float.MAX_VALUE;
        for(int i=0;i<glyphs.size();i++){
            ReaderDocument.Box b=glyphs.get(i).box;
            float dx=Math.max(Math.max(b.left-x,0),x-b.right);
            float dy=Math.max(Math.max(b.top-y,0),y-b.bottom);
            // A long line must not steal a touch in the whitespace of a shorter adjacent line.
            if(dy<bestVertical-0.0001f||(Math.abs(dy-bestVertical)<=0.0001f&&dx<bestHorizontal)){
                bestVertical=dy;bestHorizontal=dx;nearest=i;
            }
        }
        return nearest;
    }

    static List<ReaderDocument.Box> boxesForRange(List<PdfTextSearch.Token> glyphs,int first,int last){
        ArrayList<ReaderDocument.Box> boxes=new ArrayList<>();
        for(int i=first;i<=last;i++)addMerged(boxes,glyphs.get(i).box);
        return boxes;
    }

    /** Preserve the finger-to-glyph offset while moving a handle below the text. */
    static float[] dragPoint(float x,float y,float downX,float downY,ReaderDocument.Box glyph){
        return new float[]{x+(glyph.left+glyph.right)*.5f-downX,
                y+(glyph.top+glyph.bottom)*.5f-downY};
    }

    static List<Match> find(String source,List<ReaderDocument.Box> sourceBoxes,String query,int limit){
        if(limit<=0)return Collections.emptyList();Normalized haystack=normalize(source,sourceBoxes),needle=normalize(query,null);
        if(haystack.folded.length()==0||needle.folded.length()==0)return Collections.emptyList();
        ArrayList<Match> found=new ArrayList<>();int from=0;
        while(from<=haystack.folded.length()-needle.folded.length()){
            int at=haystack.folded.indexOf(needle.folded,from);if(at<0)break;
            int displayStart=haystack.foldedDisplay[Math.min(at,haystack.foldedDisplay.length-1)];
            int foldedEnd=at+needle.folded.length()-1;
            int displayEnd=haystack.foldedDisplayEnd[Math.min(foldedEnd,haystack.foldedDisplayEnd.length-1)];
            displayStart=Math.max(0,Math.min(displayStart,haystack.display.length()));displayEnd=Math.max(displayStart,Math.min(displayEnd,haystack.display.length()));
            ArrayList<ReaderDocument.Box> boxes=new ArrayList<>();
            for(int i=displayStart;i<displayEnd&&i<haystack.boxes.size();i++)addMerged(boxes,haystack.boxes.get(i));
            found.add(new Match(haystack.display.substring(displayStart,displayEnd),Collections.unmodifiableList(boxes)));
            if(found.size()>=limit)break;
            from=at+needle.folded.length();
        }
        return Collections.unmodifiableList(found);
    }

    private static final class Normalized {
        final String display,folded;final int[] foldedDisplay,foldedDisplayEnd;final List<ReaderDocument.Box> boxes;
        Normalized(String display,String folded,int[] map,int[] end,List<ReaderDocument.Box> boxes){this.display=display;this.folded=folded;this.foldedDisplay=map;this.foldedDisplayEnd=end;this.boxes=boxes;}
    }

    private static Normalized normalize(String value,List<ReaderDocument.Box> sourceBoxes){
        if(value==null)value="";StringBuilder display=new StringBuilder(value.length());ArrayList<ReaderDocument.Box> boxes=new ArrayList<>();
        boolean pendingSpace=false;ReaderDocument.Box pendingBox=null;int previous=-1;
        for(int offset=0;offset<value.length();){
            int start=offset;offset+=Character.charCount(value.codePointAt(offset));
            while(offset<value.length()){
                int type=Character.getType(value.codePointAt(offset));
                if(type!=Character.NON_SPACING_MARK&&type!=Character.COMBINING_SPACING_MARK&&type!=Character.ENCLOSING_MARK)break;
                offset+=Character.charCount(value.codePointAt(offset));
            }
            String cluster=Normalizer.normalize(value.substring(start,offset),Normalizer.Form.NFKC);
            ReaderDocument.Box box=union(sourceBoxes,start,offset);
            for(int at=0;at<cluster.length();){
                int cp=cluster.codePointAt(at);at+=Character.charCount(cp);
                if(cp==0x200b||cp==0xfeff||cp==0x00ad)continue;
                if(Character.isWhitespace(cp)||Character.isSpaceChar(cp)){pendingSpace=display.length()>0;if(pendingBox==null)pendingBox=box;continue;}
                if(pendingSpace&&!(isCjk(previous)&&isCjk(cp))){display.append(' ');boxes.add(pendingBox);}
                int before=display.length();display.appendCodePoint(cp);while(boxes.size()<display.length())boxes.add(box);
                previous=cp;pendingSpace=false;pendingBox=null;
            }
        }
        StringBuilder folded=new StringBuilder(display.length());ArrayList<Integer> map=new ArrayList<>(),ends=new ArrayList<>();
        for(int i=0;i<display.length();){int next=i+Character.charCount(display.codePointAt(i));String lower=display.substring(i,next).toLowerCase(Locale.ROOT);folded.append(lower);for(int n=0;n<lower.length();n++){map.add(i);ends.add(next);}i=next;}
        int[] indices=new int[map.size()],endIndices=new int[map.size()];for(int i=0;i<indices.length;i++){indices[i]=map.get(i);endIndices[i]=ends.get(i);}
        return new Normalized(display.toString(),folded.toString(),indices,endIndices,Collections.unmodifiableList(boxes));
    }

    private static ReaderDocument.Box union(List<ReaderDocument.Box> boxes,int start,int end){
        if(boxes==null||boxes.isEmpty())return null;ReaderDocument.Box result=null;
        for(int i=start;i<end&&i<boxes.size();i++){ReaderDocument.Box box=boxes.get(i);if(box==null)continue;result=result==null?box:new ReaderDocument.Box(Math.min(result.left,box.left),Math.min(result.top,box.top),Math.max(result.right,box.right),Math.max(result.bottom,box.bottom));}
        return result;
    }
    private static void addMerged(List<ReaderDocument.Box> boxes,ReaderDocument.Box next){
        if(next==null)return;if(!boxes.isEmpty()){
            ReaderDocument.Box previous=boxes.get(boxes.size()-1);float height=Math.max(previous.bottom-previous.top,next.bottom-next.top);
            if(Math.abs(previous.top-next.top)<=Math.max(.004f,height*.45f)&&next.left<=previous.right+Math.max(.003f,height*.35f)){
                boxes.set(boxes.size()-1,new ReaderDocument.Box(Math.min(previous.left,next.left),Math.min(previous.top,next.top),Math.max(previous.right,next.right),Math.max(previous.bottom,next.bottom)));return;
            }
        }boxes.add(next);
    }
    private static boolean isCjk(int cp){return (cp>=0x3400&&cp<=0x9fff)||(cp>=0xf900&&cp<=0xfaff)||(cp>=0x20000&&cp<=0x323af)||(cp>=0x3040&&cp<=0x30ff)||(cp>=0xac00&&cp<=0xd7af);}
}
