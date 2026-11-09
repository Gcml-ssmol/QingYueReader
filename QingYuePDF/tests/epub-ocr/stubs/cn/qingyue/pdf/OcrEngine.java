package cn.qingyue.pdf;
public final class OcrEngine {
    public static final class Result {
        public final String text,hocr,version;public final int confidence;
        public Result(String text,String hocr,int confidence,String version){
            this.text=text==null?"":text;this.hocr=hocr==null?"":hocr;
            this.confidence=confidence;this.version=version;}
    }
}
