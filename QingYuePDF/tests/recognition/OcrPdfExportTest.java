package cn.qingyue.pdf;

import android.content.Context;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.LinkedHashMap;

/** Real PDFBox round trip: invisible Unicode text on rotated/cropped pages. */
public final class OcrPdfExportTest {
    private static final class Positions extends PDFTextStripper {
        float x=Float.NaN,y=Float.NaN;
        Positions()throws Exception { super(); }
        @Override protected void processTextPosition(TextPosition position) {
            if(Float.isNaN(x)){x=position.getXDirAdj();y=position.getYDirAdj();}
            super.processTextPosition(position);
        }
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void near(float value,float expected){check(Math.abs(value-expected)<.01f,"coordinate "+value+" != "+expected);}
    public static void main(String[] args)throws Exception {
        File root=new File(System.getProperty("ocr.test.root")),font=new File(System.getProperty("ocr.test.font"));
        PDFBoxResourceLoader.init(new Context());
        root.mkdirs();File source=new File(root,"source.pdf"),output=new File(root,"searchable.pdf");
        try(PDDocument pdf=new PDDocument()){
            for(int rotation:new int[]{0,90,270}){
                PDPage page=new PDPage(new PDRectangle(10,20,200,300));page.setCropBox(new PDRectangle(10,20,200,300));
                page.setRotation(rotation);pdf.addPage(page);
            }
            pdf.save(source);
        }
        OcrHocr.Page result=OcrHocr.parse("<div class='ocr_page' title='bbox 0 0 1000 1000'>"
                +"<span class='ocrx_word' title='bbox 100 200 550 260'>中文English</span></div>");
        byte[] originalHash=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source.toPath()));
        LinkedHashMap<Integer,OcrHocr.Page> pages=new LinkedHashMap<>();pages.put(0,result);pages.put(1,result);pages.put(2,result);
        try { OcrPdfExporter.export(source,source,pages,font,new File(root,"scratch"),new OcrCancellation());
            throw new AssertionError("source overwrite accepted"); }
        catch(java.io.IOException expected) { check(expected.getMessage().contains("不能覆盖"),"wrong overwrite failure"); }
        OcrPdfExporter.export(source,output,pages,font,new File(root,"scratch"),new OcrCancellation());
        try(PDDocument pdf=PDDocument.load(output)){
            check(pdf.getNumberOfPages()==3,"original pages retained");
            PDFTextStripper stripper=new PDFTextStripper();
            for(int page=1;page<=3;page++){
                stripper.setStartPage(page);stripper.setEndPage(page);
                String extracted=stripper.getText(pdf);
                check(extracted.contains("中文English"),"missing Unicode text on page "+page+": "+extracted);
                Positions position=new Positions();position.setStartPage(page);position.setEndPage(page);
                position.getText(pdf);
                near(position.x,page==1?20:30);near(position.y,page==1?78:52);
            }
        }
        try(PDDocument pdf=PDDocument.load(source)){
            check(new PDFTextStripper().getText(pdf).trim().isEmpty(),"source PDF was modified");
        }
        check(java.util.Arrays.equals(originalHash,MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source.toPath()))),"source bytes changed");
        PDRectangle crop=new PDRectangle(10,20,200,300);float[] point;
        point=OcrPdfExporter.toPagePoint(crop,0,.25f,.5f);near(point[0],60);near(point[1],170);
        point=OcrPdfExporter.toPagePoint(crop,90,.25f,.5f);near(point[0],110);near(point[1],95);
        point=OcrPdfExporter.toPagePoint(crop,180,.25f,.5f);near(point[0],160);near(point[1],170);
        point=OcrPdfExporter.toPagePoint(crop,270,.25f,.5f);near(point[0],110);near(point[1],245);
        System.out.println("OcrPdfExportTest passed");
    }
}
