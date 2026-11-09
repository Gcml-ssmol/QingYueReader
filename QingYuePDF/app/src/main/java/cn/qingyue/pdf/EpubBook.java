package cn.qingyue.pdf;

import java.util.*;
import java.io.File;
import java.util.regex.Pattern;

/** Source-aware, bounded Chinese novel reflow. PDF page indices are zero-based. */
public final class EpubBook {
    public static final class Heading {
        public final String title; public final int sourcePage; public final String matchText,anchorText;
        public Heading(String title,int sourcePage){this(title,sourcePage,null);}
        /** matchText identifies a line on sourcePage; null starts at the page boundary. */
        public Heading(String title,int sourcePage,String matchText){this.title=title;this.sourcePage=sourcePage;
            this.matchText=matchText==null||matchText.trim().isEmpty()?null:matchText;
            this.anchorText=this.matchText;}
    }
    public enum Status { TEXT_LAYER, SIDECAR, OCR, IMAGE_FALLBACK, CONFIRMED_BLANK }
    public static final class Line {
        public final String text;public final float left,top,right,bottom,confidence;
        public Line(String text,float left,float top,float right,float bottom,float confidence){
            this.text=text==null?"":text;this.left=left;this.top=top;this.right=right;this.bottom=bottom;this.confidence=confidence;
        }
        public boolean hasGeometry(){return right>left&&bottom>top;}
    }
    public static final class Page {
        public final int sourcePage;public final float width,height;public final Status status;
        public final List<Line> lines;public final byte[] imageJpeg;public final File imageFile;
        public Page(int sourcePage,float width,float height,Status status,List<Line> lines,byte[] imageJpeg){
            this(sourcePage,width,height,status,lines,imageJpeg,null);
        }
        public static Page withImageFile(int sourcePage,float width,float height,Status status,List<Line> lines,File imageFile){
            return new Page(sourcePage,width,height,status,lines,null,imageFile);
        }
        private Page(int sourcePage,float width,float height,Status status,List<Line> lines,byte[] imageJpeg,File imageFile){
            if(status==null||lines==null)throw new IllegalArgumentException("页面状态无效");
            this.sourcePage=sourcePage;this.width=width;this.height=height;this.status=status;
            this.lines=Collections.unmodifiableList(new ArrayList<>(lines));this.imageJpeg=imageJpeg==null?null:imageJpeg.clone();
            this.imageFile=imageFile;
            if(status==Status.IMAGE_FALLBACK&&(imageJpeg==null||imageJpeg.length==0)
                    &&(imageFile==null||!imageFile.isFile()))throw new IllegalArgumentException("原页图缺失");
        }
    }
    public static final class Element {
        public enum Kind { PARAGRAPH, IMAGE, BLANK, PAGE_MARK }
        public final Kind kind;public final String text;public final int sourcePage;
        public final float left,top,right,bottom,confidence;public final Status status;
        Element(Kind kind,String text,int sourcePage,Status status,float left,float top,float right,float bottom,float confidence){
            this.kind=kind;this.text=text;this.sourcePage=sourcePage;this.status=status;
            this.left=left;this.top=top;this.right=right;this.bottom=bottom;this.confidence=confidence;
        }
        Element(Kind kind,String text,Page page){this(kind,text,page.sourcePage,page.status,0,0,page.width,page.height,-1);}
    }
    public static final class Chapter {
        public final String title;public final int sourcePage;public final List<String> paragraphs;public final List<Element> elements;
        Chapter(String title,int sourcePage,List<Element> elements){
            this.title=title;this.sourcePage=sourcePage;this.elements=Collections.unmodifiableList(new ArrayList<>(elements));
            List<String> p=new ArrayList<>();for(Element e:elements)if(e.kind==Element.Kind.PARAGRAPH)p.add(e.text);
            this.paragraphs=Collections.unmodifiableList(p);
        }
    }
    private static final Pattern CHAPTER=Pattern.compile("^(?:第[零〇一二三四五六七八九十百千万两0-9０-９]+[章节回卷部篇集]|序章|楔子|尾声|后记|番外)(?:[ 　:：·.、\\-—].{0,35}|.{0,20})?$",Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER=Pattern.compile("^[—–-]*[0-9０-９一二三四五六七八九十百千万]+[—–-]*$");
    private static final int MAX_CHARS=8_000_000;
    private final String title,author;private final List<Chapter> chapters;private final List<Page> pages;private final int characters;
    private EpubBook(String title,String author,List<Chapter> chapters,List<Page> pages,int characters){
        this.title=title;this.author=author;this.chapters=Collections.unmodifiableList(chapters);
        this.pages=Collections.unmodifiableList(pages);this.characters=characters;
    }
    public String title(){return title;}public String author(){return author;}public List<Chapter> chapters(){return chapters;}
    public List<Page> pages(){return pages;}public int characters(){return characters;}
    public static EpubBook fromPages(String title,String author,List<String> texts,int first){
        return fromPages(title,author,texts,first,Collections.<Heading>emptyList());
    }
    public static EpubBook fromPages(String title,String author,List<String> texts,int first,List<Heading> headings){
        if(texts==null||texts.isEmpty())throw new IllegalArgumentException("没有页面");List<Page> pages=new ArrayList<>();
        for(int i=0;i<texts.size();i++)pages.add(new Page(first+i,0,0,Status.TEXT_LAYER,EpubOcrText.plainLines(texts.get(i)),null));
        return fromStructuredPages(title,author,pages,headings);
    }
    public static EpubBook fromStructuredPages(String title,String author,List<Page> pages,List<Heading> headings){
        if(pages==null||pages.isEmpty())throw new IllegalArgumentException("没有页面");
        List<Page> copy=new ArrayList<>(pages);for(int i=1;i<copy.size();i++)
            if(copy.get(i).sourcePage!=copy.get(i-1).sourcePage+1)throw new IllegalArgumentException("页面顺序不连续");
        Map<String,Set<Integer>> furniture=new HashMap<>();
        for(Page p:copy){List<Line> ls=nonempty(p.lines);if(ls.size()<2)continue;
            for(int e=0;e<2;e++){Line l=ls.get(e==0?0:ls.size()-1);if(!edgePosition(p,l,e==1))continue;
                String key=(e==0?"T":"B")+(e==1&&NUMBER.matcher(l.text.trim()).matches()?"#NUMBER":l.text.trim());
                if(!furniture.containsKey(key))furniture.put(key,new HashSet<Integer>());
                furniture.get(key).add(p.sourcePage);}}
        Map<Integer,List<Heading>> starts=new HashMap<>();boolean auto=headings==null;
        if(headings!=null)for(Heading h:headings)if(h!=null&&h.title!=null&&!h.title.trim().isEmpty()
                &&h.sourcePage>=copy.get(0).sourcePage&&h.sourcePage<=copy.get(copy.size()-1).sourcePage){
            if(!starts.containsKey(h.sourcePage))starts.put(h.sourcePage,new ArrayList<Heading>());
            starts.get(h.sourcePage).add(h);}
        List<Chapter> result=new ArrayList<>();List<Element> elements=new ArrayList<>();
        String chapterTitle=safe(title);int chapterPage=copy.get(0).sourcePage,total=0;
        Pending pending=new Pending();Line previous=null;Page previousPage=null;
        for(Page page:copy){
            List<Heading> pageStarts=starts.get(page.sourcePage);
            if(pageStarts!=null)for(Heading h:pageStarts)if(h.matchText==null){
                flush(pending,elements);if(hasContent(elements))result.add(new Chapter(chapterTitle,chapterPage,elements));
                elements=new ArrayList<>();chapterTitle=h.title.trim();chapterPage=page.sourcePage;previous=null;}
            elements.add(new Element(Element.Kind.PAGE_MARK,"",page));
            if(page.status==Status.CONFIRMED_BLANK){flush(pending,elements);elements.add(new Element(Element.Kind.BLANK,"已确认空白页",page));previous=null;previousPage=page;continue;}
            if(page.status==Status.IMAGE_FALLBACK){flush(pending,elements);elements.add(new Element(Element.Kind.IMAGE,"原页图片，不能重排",page));previous=null;previousPage=page;continue;}
            List<Line> ls=page.lines;float normalLeft=normalLeft(ls);
            for(int i=0;i<ls.size();i++){
                Line line=ls.get(i);if(line==null)continue;String value=line.text.trim();
                if(value.isEmpty()){flush(pending,elements);previous=null;continue;}
                if(isFurniture(page,line,i==0,i==ls.size()-1,furniture,copy.size()))continue;
                Heading matched=null;if(pageStarts!=null)for(Heading h:pageStarts)if(h.matchText!=null&&sameTitle(value,h.matchText)){matched=h;break;}
                if(matched!=null||(auto&&isChapter(value))){
                    flush(pending,elements);if(hasContent(elements))result.add(new Chapter(chapterTitle,chapterPage,elements));
                    elements=new ArrayList<>();chapterTitle=matched==null?value:matched.title.trim();chapterPage=page.sourcePage;
                    elements.add(new Element(Element.Kind.PAGE_MARK,"",page));previous=null;continue;}
                if(pageStarts!=null&&i==0)for(Heading h:pageStarts)if(h.matchText==null&&sameTitle(value,h.title)){value="";break;}
                if(value.isEmpty())continue;
                if(pending.length()>0&&newParagraph(previous,line,previousPage,page,normalLeft))flush(pending,elements);
                if(pending.length()>0&&needsSpace(pending.charAt(pending.length()-1),value.charAt(0)))pending.append(' ');
                pending.append(value,line,page);total+=value.length();if(total>MAX_CHARS)throw new IllegalArgumentException("正文超过 800 万字符上限");
                if(pending.length()>10000)flush(pending,elements);
                previous=line;previousPage=page;
            }
            if(page.imageJpeg!=null||page.imageFile!=null){flush(pending,elements);
                elements.add(new Element(Element.Kind.IMAGE,"原页图片，供核对插图或复杂版面；不能重排",page));
                previous=null;previousPage=page;}
        }
        flush(pending,elements);if(hasContent(elements))result.add(new Chapter(chapterTitle,chapterPage,elements));
        if(result.isEmpty())throw new IllegalArgumentException("所选页面没有可导出的内容");
        return new EpubBook(safe(title),author==null?"":author.trim(),result,copy,total);
    }
    private static List<Line> nonempty(List<Line> input){List<Line> out=new ArrayList<>();for(Line l:input)if(l!=null&&!l.text.trim().isEmpty())out.add(l);return out;}
    private static boolean hasContent(List<Element> elements){for(Element e:elements)if(e.kind!=Element.Kind.PAGE_MARK)return true;return false;}
    private static boolean edgePosition(Page p,Line l,boolean bottom){return l.hasGeometry()&&p.height>0&&(bottom?l.top>=p.height*.82f:l.bottom<=p.height*.18f);}
    private static boolean isFurniture(Page p,Line l,boolean first,boolean last,Map<String,Set<Integer>> seen,int count){
        if(!(first||last)||!edgePosition(p,l,last))return false;String s=l.text.trim();if(s.length()>80||isChapter(s))return false;
        Set<Integer> matches=seen.get((last?"B":"T")+(last&&NUMBER.matcher(s).matches()?"#NUMBER":s));
        return count>=3&&matches!=null&&matches.size()>=Math.max(3,(count+2)/3);
    }
    private static float normalLeft(List<Line> ls){List<Float> x=new ArrayList<>();for(Line l:ls)if(l.hasGeometry()&&l.text.trim().length()>=4)x.add(l.left);
        if(x.isEmpty())return Float.NaN;Collections.sort(x);return x.get(x.size()/4);}
    private static boolean newParagraph(Line prev,Line line,Page prevPage,Page page,float normalLeft){
        String text=line.text.trim();if(text.startsWith("“")||text.startsWith("「")||text.startsWith("\""))return true;
        if(line.text.startsWith("  ")||line.text.startsWith("　"))return true;
        if(line.hasGeometry()&&!Float.isNaN(normalLeft)&&line.left-normalLeft>=Math.max(8f,line.bottom-line.top)*.8f)return true;
        if(prevPage!=null&&prevPage.sourcePage!=page.sourcePage){
            if(prev==null||!prev.hasGeometry()||!line.hasGeometry()||prevPage.height<=0||page.height<=0)return true;
            float right=page.width>0?page.width:prevPage.width;
            return !(prev.bottom>=prevPage.height*.70f&&line.top<=page.height*.30f&&right>0
                    &&prev.right>=right*.72f&&!endsSentence(prev.text));}
        if(prev!=null&&prev.hasGeometry()&&line.hasGeometry()){
            float h=Math.max(1f,prev.bottom-prev.top);if(line.top-prev.bottom>h*.95f)return true;
            if(line.left+Math.max(8f,h)<prev.left)return true;}
        return false;
    }
    private static boolean endsSentence(String s){return !s.isEmpty()&&"。！？.!?…\"”」』".indexOf(s.charAt(s.length()-1))>=0;}
    private static boolean isChapter(String s){return s.length()<=42&&(CHAPTER.matcher(s).matches()||s.matches("^[一二三四五六七八九十百千万]+[、.．].{1,35}$"));}
    private static boolean sameTitle(String a,String b){if(b==null)return false;return a.replaceAll("\\s+","").equals(b.replaceAll("\\s+",""));}
    private static boolean needsSpace(char a,char b){return a<128&&b<128&&Character.isLetterOrDigit(a)&&Character.isLetterOrDigit(b);}
    private static final class Pending {
        final StringBuilder text=new StringBuilder();Line first;Page page;float right,bottom;
        int length(){return text.length();}char charAt(int i){return text.charAt(i);}void append(char c){text.append(c);}
        void append(String value,Line line,Page current){
            if(text.length()==0){first=line;page=current;right=line.right;bottom=line.bottom;}
            else if(page.sourcePage==current.sourcePage){right=Math.max(right,line.right);bottom=Math.max(bottom,line.bottom);}
            text.append(value);
        }
        void clear(){text.setLength(0);first=null;page=null;}
    }
    private static void flush(Pending pending,List<Element> out){if(pending.length()>0){
        Line line=pending.first;Page page=pending.page;
        out.add(new Element(Element.Kind.PARAGRAPH,pending.text.toString(),page.sourcePage,page.status,
                line.left,line.top,pending.right,pending.bottom,line.confidence));pending.clear();}}
    private static String safe(String s){return s==null||s.trim().isEmpty()?"未命名":s.trim();}
}
