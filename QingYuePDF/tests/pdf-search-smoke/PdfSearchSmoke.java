package cn.qingyue.pdf;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import android.content.Context;

/** Desktop parser smoke only. Lightweight Android shims do not constitute a device test. */
public final class PdfSearchSmoke {
    static class Capture implements PdfTextSearch.Listener {
        CountDownLatch complete = new CountDownLatch(1);
        List<PdfTextSearch.Result> results = new ArrayList<>();
        int total, searchable, progress; boolean limited; String error;
        public void onProgress(int done, int total, List<PdfTextSearch.Result> batch) {
            if(done < progress)throw new AssertionError("Progress decreased");
            progress=done;results.addAll(batch);
        }
        public void onComplete(int total, int searchable, boolean limited, String error) {
            this.total=total;this.searchable=searchable;this.limited=limited;this.error=error;complete.countDown();
        }
    }
    static Capture run(String file,String query)throws Exception {
        try(PdfTextSearch search=new PdfTextSearch(new Context())) {
            Capture capture=new Capture();search.start(new File(System.getProperty("smoke.fixtures"),file),query,capture);
            if(!capture.complete.await(20,TimeUnit.SECONDS)) {
                java.lang.reflect.Field field=PdfTextSearch.class.getDeclaredField("running");field.setAccessible(true);
                ((Future<?>)field.get(search)).get(1,TimeUnit.SECONDS);
                throw new AssertionError("No completion");
            }
            if(capture.error!=null)throw new AssertionError(capture.error);
            return capture;
        }
    }
    static void check(boolean value,String msg){if(!value)throw new AssertionError(msg);}
    public static void main(String[] args)throws Exception {
        Capture english=run("sample.pdf","pDf");
        check(english.total==4&&english.searchable==3,"Page counts "+english.total+"/"+english.searchable);
        check(english.results.size()==2,"English result pages");
        check(english.results.get(0).page==0&&english.results.get(0).matches==2,"First page/count");
        check(english.results.get(0).hits.size()==2,"Concrete hit count");
        for(PdfTextSearch.Hit hit:english.results.get(0).hits){
            check(!hit.boxes.isEmpty(),"Search hit lost glyph coordinates");
            for(ReaderDocument.Box box:hit.boxes)check(box.left>=0&&box.right<=1&&box.top>=0&&box.bottom>=box.top,"Search box outside normalized page");
        }
        check(english.results.get(1).page==3,"Final page navigation index");
        Capture chinese=run("sample.pdf","随机变量");
        check(chinese.results.size()==1&&chinese.results.get(0).page==1,"Chinese CMap extraction");
        check(chinese.results.get(0).snippet.contains("随机变量"),"Chinese snippet");
        Capture empty=run("empty.pdf","PDF");
        check(empty.total==3&&empty.searchable==0&&empty.results.isEmpty(),"No text layer");
        Capture missing=run("sample.pdf","not-present");
        check(missing.results.isEmpty()&&missing.searchable==3,"No match vs no text");
        Capture capped=run("many.pdf","PDF");
        check(capped.limited&&capped.results.size()==200&&capped.results.get(199).page==199,"Result limit");
        try(PdfTextSearch search=new PdfTextSearch(new Context())) {
            Capture old=new Capture(),fresh=new Capture();
            search.start(new File(System.getProperty("smoke.fixtures"),"many.pdf"),"PDF",old);
            search.start(new File(System.getProperty("smoke.fixtures"),"sample.pdf"),"随机变量",fresh);
            check(fresh.complete.await(10,TimeUnit.SECONDS),"Replacement completes");
            check(fresh.results.size()==1&&old.complete.getCount()==1,"Replacement discards old completion");
        }
        try(PdfTextSearch search=new PdfTextSearch(new Context())) {
            PdfTextSearch.PageText text=search.extractPage(new File(System.getProperty("smoke.fixtures"),"sample.pdf"),0);
            check(text.hasText()&&!text.tokens.isEmpty(),"Selectable page text missing");
            for(PdfTextSearch.Token token:text.tokens)check(token.start>=0&&token.end>token.start&&token.end<=text.text.length(),"Selectable token range invalid");
            check(text.tokens.size()>text.text.split("\\s+").length,"Selection still uses whole words");
            PdfTextSearch.Token first=text.tokens.get(0),second=text.tokens.get(1);
            check(first.end<=second.start,"Glyph reading order invalid");
            check(PdfTextGeometry.nearestGlyph(text.tokens,(first.box.left+first.box.right)/2,(first.box.top+first.box.bottom)/2)==0,"Glyph hit test failed");
        }
        List<ReaderDocument.Box> lines=Arrays.asList(
            new ReaderDocument.Box(.10f,.10f,.15f,.13f),new ReaderDocument.Box(.16f,.10f,.21f,.13f),
            null,new ReaderDocument.Box(.10f,.20f,.15f,.23f),new ReaderDocument.Box(.16f,.20f,.21f,.23f));
        PdfTextSearch.PageText page=PdfTextGeometry.selectablePage(0,"AB\n中文",lines);
        check(page.tokens.size()==4&&page.text.equals("AB 中文"),"Multiline glyph mapping failed: "+page.text);
        int from=PdfTextGeometry.nearestGlyph(page.tokens,.18f,.115f),to=PdfTextGeometry.nearestGlyph(page.tokens,.11f,.215f);
        check(page.text.substring(page.tokens.get(Math.min(from,to)).start,page.tokens.get(Math.max(from,to)).end).equals("B 中"),"Cross-line selection failed");
        check(PdfTextGeometry.boxesForRange(page.tokens,Math.min(from,to),Math.max(from,to)).size()==2,"Cross-line highlight geometry failed");
        check(PdfTextGeometry.nearestGlyph(page.tokens,.25f,.115f)==1,"Whitespace gap should snap to same line");
        List<ReaderDocument.Box> uneven=Arrays.asList(
            new ReaderDocument.Box(.10f,.10f,.15f,.13f),new ReaderDocument.Box(.90f,.10f,.95f,.13f),
            null,new ReaderDocument.Box(.10f,.20f,.15f,.23f));
        PdfTextSearch.PageText shortLine=PdfTextGeometry.selectablePage(0,"AB\n中",uneven);
        check(PdfTextGeometry.nearestGlyph(shortLine.tokens,.95f,.215f)==2,"Short second line whitespace selected previous line");
        check(PdfTextGeometry.nearestGlyph(shortLine.tokens,.99f,.115f)==1,"Long first line end whitespace selected next line");
        ReaderDocument.Box held=page.tokens.get(0).box;
        float downX=held.right,downY=held.bottom+.055f;
        for(float pageWidth:new float[]{180f,1080f}){
            float[] still=PdfTextGeometry.dragPoint(downX,downY,downX,downY,held);
            check(PdfTextGeometry.nearestGlyph(page.tokens,still[0],still[1])==0,"Handle startup jumped at width "+pageWidth);
            float movePixels=(page.tokens.get(2).box.top-held.top)*pageWidth;
            float[] moved=PdfTextGeometry.dragPoint(downX,downY+movePixels/pageWidth,downX,downY,held);
            check(PdfTextGeometry.nearestGlyph(page.tokens,moved[0],moved[1])==2,"Handle failed to cross line at width "+pageWidth);
        }
        System.out.println("PdfSearchSmoke passed: actual PDFBox AAR, per-hit coordinates, selectable text ranges, English + Chinese CMap, page indices, blank text, no match, 200-page cap, replacement cancellation (desktop Android shims; not device UI validation).");
    }
}
