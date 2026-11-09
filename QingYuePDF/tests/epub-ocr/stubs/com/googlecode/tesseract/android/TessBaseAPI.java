package com.googlecode.tesseract.android;
import android.graphics.Bitmap;
public final class TessBaseAPI {
    public static final int OEM_LSTM_ONLY=1;
    public static final class PageSegMode {public static final int PSM_AUTO=3;}
    public static int created,inits,clears,recycles,stops,recognitions;
    public static String baselineText="原图中文正文",rotatedText="去斜中文正文更完整";
    public static int baselineConfidence=70,rotatedConfidence=82;
    public static Runnable onText;
    private Bitmap image;
    public TessBaseAPI(){created++;}
    public static void reset(){created=inits=clears=recycles=stops=recognitions=0;
        baselineText="原图中文正文";rotatedText="去斜中文正文更完整";
        baselineConfidence=70;rotatedConfidence=82;onText=null;}
    public boolean init(String path,String languages,int mode){inits++;return true;}
    public void setPageSegMode(int mode){}public boolean setVariable(String key,String value){return true;}
    public void setImage(Bitmap bitmap){image=bitmap;recognitions++;}
    public String getHOCRText(int page){return "<hocr/>";}
    public String getUTF8Text(){if(onText!=null)onText.run();return image.rotated?rotatedText:baselineText;}
    public int meanConfidence(){return image.rotated?rotatedConfidence:baselineConfidence;}
    public String getVersion(){return "fake-5";}
    public void clear(){clears++;}public void recycle(){recycles++;}public void stop(){stops++;}
}
