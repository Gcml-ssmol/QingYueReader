package cn.qingyue.pdf;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small, forward-rejecting sidecar for bookmarks and selectable-text highlights. */
public final class ReaderDocument {
    private static final int MAGIC=0x51524452, VERSION=2;
    private static final int MAX_BOOKMARKS=5000, MAX_MARKUPS=20000, MAX_BOXES=200000;

    public static final class Box {
        /** Coordinates use the displayed page width as one unit and a top-left origin. */
        public final float left,top,right,bottom;
        public Box(float left,float top,float right,float bottom){
            if(!finite(left)||!finite(top)||!finite(right)||!finite(bottom)||left<0||right>1
                    ||top<0||bottom<top||right<left)throw new IllegalArgumentException("Invalid box");
            this.left=left;this.top=top;this.right=right;this.bottom=bottom;
        }
        public boolean intersects(float l,float t,float r,float b){return right>=l&&left<=r&&bottom>=t&&top<=b;}
    }

    public static final class Bookmark {
        public final String id,label;public final int page;public final long createdAt;
        public Bookmark(String id,int page,String label,long createdAt){
            this.id=required(id,80);this.label=clean(label,240);if(page<0)throw new IllegalArgumentException("page");
            this.page=page;this.createdAt=Math.max(0,createdAt);
        }
    }

    public static final class Markup {
        public final String id,quote;public final int page,color;public final long createdAt;
        public final List<Box> boxes;
        public Markup(String id,int page,int color,String quote,List<Box> boxes,long createdAt){
            this.id=required(id,80);if(page<0)throw new IllegalArgumentException("page");this.page=page;
            this.color=color;this.quote=clean(quote,4096);if(boxes==null||boxes.isEmpty()||boxes.size()>4096)throw new IllegalArgumentException("boxes");
            this.boxes=Collections.unmodifiableList(new ArrayList<>(boxes));this.createdAt=Math.max(0,createdAt);
        }
    }
    /** Page-width-normalized position and font size, with a top-left display origin. */
    public static final class TextBox {
        public final String id,text;public final int page,color;public final float x,y,size;public final long createdAt;
        public TextBox(String id,int page,String text,int color,float x,float y,float size,long createdAt){
            this.id=required(id,80);if(page<0||!finite(x)||!finite(y)||!finite(size)||x<0||x>1||y<0||size<=0||size>.2f)throw new IllegalArgumentException("Invalid text box");
            this.page=page;this.text=cleanText(text);if(this.text.isEmpty())throw new IllegalArgumentException("Empty text box");this.color=color;this.x=x;this.y=y;this.size=size;this.createdAt=Math.max(0,createdAt);
        }
    }
    private static String cleanText(String value){if(value==null)return "";String result=value.replace("\r\n","\n").replace('\r','\n').replace('\u0000',' ').replace("\t","    ");if(result.length()>8192)throw new IllegalArgumentException("文字框最多 8192 个字符（制表符按四个空格计算）");return result;}

    private final ArrayList<Bookmark> bookmarks=new ArrayList<>();
    private final ArrayList<Markup> markups=new ArrayList<>();
    private final ArrayList<TextBox> textBoxes=new ArrayList<>();

    public List<Bookmark> bookmarks(){return Collections.unmodifiableList(new ArrayList<>(bookmarks));}
    public List<Markup> markups(){return Collections.unmodifiableList(new ArrayList<>(markups));}
    public List<Markup> markups(int page){ArrayList<Markup> result=new ArrayList<>();for(Markup item:markups)if(item.page==page)result.add(item);return Collections.unmodifiableList(result);}
    public List<TextBox> textBoxes(){return Collections.unmodifiableList(new ArrayList<>(textBoxes));}
    public void replaceTextBoxes(List<TextBox> boxes){if(boxes==null||boxes.size()>5000)throw new IllegalArgumentException("Invalid text boxes");HashSet<String> ids=new HashSet<>();for(TextBox box:boxes)if(box==null||!ids.add(box.id))throw new IllegalArgumentException("Duplicate text box");textBoxes.clear();textBoxes.addAll(boxes);}
    public List<TextBox> textBoxes(int page){ArrayList<TextBox> result=new ArrayList<>();for(TextBox item:textBoxes)if(item.page==page)result.add(item);return Collections.unmodifiableList(result);}
    public TextBox addTextBox(int page,String text,int color,float x,float y,float size){if(textBoxes.size()>=5000)throw new IllegalStateException("Too many text boxes");TextBox item=new TextBox(UUID.randomUUID().toString(),page,text,color,x,y,size,System.currentTimeMillis());textBoxes.add(item);return item;}
    public TextBox updateTextBox(TextBox old,String text,int color,float x,float y,float size){if(old==null)throw new IllegalArgumentException("text box");for(int i=0;i<textBoxes.size();i++)if(textBoxes.get(i).id.equals(old.id)){TextBox item=new TextBox(old.id,old.page,text,color,x,y,size,old.createdAt);textBoxes.set(i,item);return item;}throw new IllegalArgumentException("Unknown text box");}
    /** Detached snapshot suitable for worker-thread persistence/export. Items are immutable. */
    public ReaderDocument copy(){ReaderDocument result=new ReaderDocument();result.bookmarks.addAll(bookmarks);result.markups.addAll(markups);result.textBoxes.addAll(textBoxes);return result;}
    /** Preserve stable IDs and coordinates while their original pages move. */
    public ReaderDocument insertingPage(int index,int oldCount)throws IOException {
        if(index<0||index>oldCount)throw new IOException("插页位置无效");
        ReaderDocument result=new ReaderDocument();
        for(Bookmark b:bookmarks)result.bookmarks.add(new Bookmark(b.id,shift(b.page,index,oldCount),b.label,b.createdAt));
        for(Markup m:markups)result.markups.add(new Markup(m.id,shift(m.page,index,oldCount),m.color,m.quote,m.boxes,m.createdAt));
        for(TextBox t:textBoxes)result.textBoxes.add(new TextBox(t.id,shift(t.page,index,oldCount),t.text,t.color,t.x,t.y,t.size,t.createdAt));
        return result;
    }
    private static int shift(int page,int index,int oldCount)throws IOException {if(page<0||page>=oldCount)throw new IOException("批注页码超出原文档，已取消插页");return page>=index?page+1:page;}
    public boolean bookmarked(int page){for(Bookmark item:bookmarks)if(item.page==page)return true;return false;}
    public Bookmark addBookmark(int page,String label){
        if(bookmarks.size()>=MAX_BOOKMARKS)throw new IllegalStateException("Too many bookmarks");
        Bookmark item=new Bookmark(UUID.randomUUID().toString(),page,bookmarkName(label),System.currentTimeMillis());bookmarks.add(item);return item;
    }
    /** Keep stable identity, target page and creation time when the visible name changes. */
    public boolean renameBookmark(String id,String label){String name=bookmarkName(label);for(int i=0;i<bookmarks.size();i++){Bookmark old=bookmarks.get(i);if(old.id.equals(id)){bookmarks.set(i,new Bookmark(old.id,old.page,name,old.createdAt));return true;}}return false;}
    static String bookmarkName(String value){
        StringBuilder result=new StringBuilder();boolean space=false;
        if(value!=null)for(int offset=0;offset<value.length();){int cp=value.codePointAt(offset);offset+=Character.charCount(cp);if(Character.isISOControl(cp)||Character.isWhitespace(cp)||Character.getType(cp)==Character.FORMAT){space=result.length()>0;continue;}if(space&&result.length()<240)result.append(' ');space=false;if(result.length()+Character.charCount(cp)>240)break;result.appendCodePoint(cp);}
        String name=result.toString().trim();if(name.isEmpty())throw new IllegalArgumentException("请输入书签名称");return name;
    }
    public Markup addMarkup(int page,int color,String quote,List<Box> boxes){
        if(markups.size()>=MAX_MARKUPS)throw new IllegalStateException("Too many markups");
        Markup item=new Markup(UUID.randomUUID().toString(),page,color,quote,boxes,System.currentTimeMillis());markups.add(item);return item;
    }
    public boolean remove(String id){
        for(int i=0;i<bookmarks.size();i++)if(bookmarks.get(i).id.equals(id)){bookmarks.remove(i);return true;}
        for(int i=0;i<markups.size();i++)if(markups.get(i).id.equals(id)){markups.remove(i);return true;}
        for(int i=0;i<textBoxes.size();i++)if(textBoxes.get(i).id.equals(id)){textBoxes.remove(i);return true;}return false;
    }
    public boolean removeBookmarkAt(int page){boolean changed=false;for(int i=bookmarks.size()-1;i>=0;i--)if(bookmarks.get(i).page==page){bookmarks.remove(i);changed=true;}return changed;}

    public static ReaderDocument read(InputStream source)throws IOException {
        DataInputStream in=new DataInputStream(new BufferedInputStream(source));
        if(in.readInt()!=MAGIC)throw new IOException("Unknown reader sidecar");int version=in.readInt();if(version<1||version>VERSION)throw new IOException("Unknown reader sidecar");
        ReaderDocument result=new ReaderDocument();int bookmarkCount=in.readInt();
        if(bookmarkCount<0||bookmarkCount>MAX_BOOKMARKS)throw new IOException("Invalid bookmark count");
        try{
            for(int i=0;i<bookmarkCount;i++)result.bookmarks.add(new Bookmark(readString(in,80),in.readInt(),readString(in,240),in.readLong()));
            int markupCount=in.readInt(),totalBoxes=0;if(markupCount<0||markupCount>MAX_MARKUPS)throw new IOException("Invalid markup count");
            for(int i=0;i<markupCount;i++){
                String id=readString(in,80);int page=in.readInt(),color=in.readInt();long created=in.readLong();String quote=readString(in,4096);int count=in.readInt();
                if(count<1||count>4096||totalBoxes+count>MAX_BOXES)throw new IOException("Invalid box count");totalBoxes+=count;
                ArrayList<Box> boxes=new ArrayList<>();for(int b=0;b<count;b++)boxes.add(new Box(in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat()));
                result.markups.add(new Markup(id,page,color,quote,boxes,created));
            }
            if(version>=2){int count=in.readInt();if(count<0||count>5000)throw new IOException("Invalid text box count");for(int i=0;i<count;i++)result.textBoxes.add(new TextBox(readString(in,80),in.readInt(),readString(in,8192),in.readInt(),in.readFloat(),in.readFloat(),in.readFloat(),in.readLong()));}
        }catch(IllegalArgumentException problem){throw new IOException("Invalid reader sidecar",problem);}
        if(in.read()!=-1)throw new IOException("Trailing reader data");return result;
    }

    public void write(OutputStream destination)throws IOException {
        int total=0;for(Markup item:markups)total+=item.boxes.size();
        if(bookmarks.size()>MAX_BOOKMARKS||markups.size()>MAX_MARKUPS||total>MAX_BOXES||textBoxes.size()>5000)throw new IOException("Reader sidecar is too large");
        DataOutputStream out=new DataOutputStream(new BufferedOutputStream(destination));out.writeInt(MAGIC);out.writeInt(VERSION);
        out.writeInt(bookmarks.size());for(Bookmark item:bookmarks){writeString(out,item.id);out.writeInt(item.page);writeString(out,item.label);out.writeLong(item.createdAt);}
        out.writeInt(markups.size());for(Markup item:markups){writeString(out,item.id);out.writeInt(item.page);out.writeInt(item.color);out.writeLong(item.createdAt);writeString(out,item.quote);out.writeInt(item.boxes.size());for(Box box:item.boxes){out.writeFloat(box.left);out.writeFloat(box.top);out.writeFloat(box.right);out.writeFloat(box.bottom);}}
        out.writeInt(textBoxes.size());for(TextBox item:textBoxes){writeString(out,item.id);out.writeInt(item.page);writeString(out,item.text);out.writeInt(item.color);out.writeFloat(item.x);out.writeFloat(item.y);out.writeFloat(item.size);out.writeLong(item.createdAt);}
        out.flush();
    }

    private static void writeString(DataOutputStream out,String value)throws IOException {byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    private static String readString(DataInputStream in,int maxCharacters)throws IOException {
        int length=in.readInt();if(length<0||length>maxCharacters*4)throw new IOException("Invalid string length");byte[] data=new byte[length];in.readFully(data);
        String value=new String(data,StandardCharsets.UTF_8);if(value.length()>maxCharacters)throw new IOException("String too long");return value;
    }
    private static String required(String value,int max){String clean=clean(value,max);if(clean.length()==0)throw new IllegalArgumentException("Required value");return clean;}
    private static String clean(String value,int max){if(value==null)return "";String clean=value.replace('\u0000',' ').trim();return clean.length()>max?clean.substring(0,max):clean;}
    private static boolean finite(float value){return !Float.isNaN(value)&&!Float.isInfinite(value);}
}
