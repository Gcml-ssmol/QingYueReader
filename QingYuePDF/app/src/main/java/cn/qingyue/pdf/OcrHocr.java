package cn.qingyue.pdf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, bounded reader for Tesseract's hOCR word boxes. No external entities are parsed. */
public final class OcrHocr {
    private static final Pattern PAGE = Pattern.compile("ocr_page[^>]*?bbox\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD = Pattern.compile("<span\\b([^>]*\\bocrx_word\\b[^>]*)>(.*?)</span>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BOX = Pattern.compile("\\bbbox\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);");
    public static final class Word {
        public final String text;
        public final int left, top, right, bottom;
        Word(String text, int left, int top, int right, int bottom) { this.text=text; this.left=left; this.top=top; this.right=right; this.bottom=bottom; }
    }
    public static final class Page {
        public final int width, height;
        public final List<Word> words;
        Page(int width, int height, List<Word> words) { this.width=width; this.height=height; this.words=Collections.unmodifiableList(words); }
    }
    private OcrHocr() { }
    public static Page parse(String hocr) {
        if (hocr == null || hocr.length() > 8_000_000) throw new IllegalArgumentException("OCR 坐标数据无效");
        Matcher page=PAGE.matcher(hocr);
        if (!page.find()) throw new IllegalArgumentException("OCR 缺少页面坐标");
        int originX=Integer.parseInt(page.group(1)),originY=Integer.parseInt(page.group(2));
        int width=Integer.parseInt(page.group(3))-originX, height=Integer.parseInt(page.group(4))-originY;
        if (width<1 || height<1 || width>100_000 || height>100_000) throw new IllegalArgumentException("OCR 页面尺寸无效");
        ArrayList<Word> words=new ArrayList<>(); Matcher match=WORD.matcher(hocr);
        while (match.find()) {
            if (words.size()>=100_000) throw new IllegalArgumentException("OCR 文字过多");
            Matcher box=BOX.matcher(match.group(1)); if (!box.find()) continue;
            int x0=Integer.parseInt(box.group(1))-originX, y0=Integer.parseInt(box.group(2))-originY;
            int x1=Integer.parseInt(box.group(3))-originX, y1=Integer.parseInt(box.group(4))-originY;
            if (x0<0 || y0<0 || x1<=x0 || y1<=y0 || x1>width || y1>height) continue;
            String text=entities(match.group(2).replaceAll("<[^>]*>", "")).trim();
            if (!text.isEmpty()) words.add(new Word(text,x0,y0,x1,y1));
        }
        return new Page(width,height,words);
    }
    private static String entities(String text) {
        Matcher entity=NUMERIC_ENTITY.matcher(text); StringBuffer decoded=new StringBuffer();
        while(entity.find()) {
            String raw=entity.group(1); int cp;
            try { cp=raw.startsWith("x")?Integer.parseInt(raw.substring(1),16):Integer.parseInt(raw); }
            catch(NumberFormatException invalid) { cp=0xfffd; }
            if(!Character.isValidCodePoint(cp)||cp==0||(cp>=0xd800&&cp<=0xdfff))cp=0xfffd;
            entity.appendReplacement(decoded,Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        entity.appendTail(decoded);
        return decoded.toString().replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&nbsp;"," ");
    }
}
