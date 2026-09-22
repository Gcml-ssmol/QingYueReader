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
        System.out.println("PdfSearchSmoke passed: actual PDFBox AAR, English + Chinese CMap, page indices, blank text, no match, 200-page cap, replacement cancellation (desktop Android shims; not device UI validation).");
    }
}
