package cn.qingyue.pdf;

public final class OcrHocrTest {
    public static void main(String[] args) {
        String sample="<div class='ocr_page' title='image x; bbox 0 0 1000 600'>"
                +"<span class='ocrx_word' title='bbox 100 30 220 60'>中文</span>"
                +"<span class='ocrx_word' title='bbox 230 30 300 60'>A&amp;B</span></div>";
        OcrHocr.Page page=OcrHocr.parse(sample);
        check(page.width==1000 && page.height==600 && page.words.size()==2,"page and words");
        check("中文".equals(page.words.get(0).text),"Chinese word");
        check("A&B".equals(page.words.get(1).text),"entity");
        OcrHocr.Page shifted=OcrHocr.parse("<div class='ocr_page' title='bbox 10 20 210 120'>"
                +"<span class='ocrx_word' title='bbox 20 30 80 60'>&#x4E2D;&#25991;</span></div>");
        check(shifted.width==200 && shifted.height==100 && shifted.words.get(0).left==10,"offset boxes");
        check("中文".equals(shifted.words.get(0).text),"numeric entities");
        OcrHocr.Page clipped=OcrHocr.parse("<div class='ocr_page' title='bbox 10 20 210 120'>"
                +"<span class='ocrx_word' title='bbox 9 30 80 60'>outside</span>"
                +"<span class='ocrx_word' title='bbox 20 30 80 60'>inside</span></div>");
        check(clipped.words.size()==1&&"inside".equals(clipped.words.get(0).text),"out-of-page word ignored");
        try { OcrHocr.parse("<span class='ocrx_word'>no page</span>"); throw new AssertionError("invalid hOCR accepted"); }
        catch(IllegalArgumentException expected) { }
        try { OcrHocr.parse("<div class='ocr_page' title='bbox 0 0 0 100'></div>"); throw new AssertionError("zero-width page accepted"); }
        catch(IllegalArgumentException expected) { }
        System.out.println("OcrHocrTest passed");
    }
    private static void check(boolean ok,String label) { if(!ok)throw new AssertionError(label); }
}
