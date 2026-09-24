package cn.qingyue.pdf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.common.PDStream;
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Desktop vector-structure smoke. Android PdfRenderer/SAF behavior still requires device QA. */
public final class PdfExportSmoke {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static byte[] digest(File file)throws Exception{MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] block=new byte[8192];for(int n;(n=in.read(block))>=0;)hash.update(block,0,n);}return hash.digest();}
    private static int xObjects(PDDocument document)throws IOException{int count=0;for(PDPage page:document.getPages())if(page.getResources()!=null)for(com.tom_roush.pdfbox.cos.COSName name:page.getResources().getXObjectNames()){PDXObject ignored=page.getResources().getXObject(name);count++;}return count;}
    private static List<String> contentTokens(PDPage page)throws IOException{ArrayList<String> tokens=new ArrayList<>();for(Iterator<PDStream> streams=page.getContentStreams();streams.hasNext();){try(InputStream input=streams.next().createInputStream()){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[2048];for(int count;(count=input.read(block))>=0;)bytes.write(block,0,count);tokens.addAll(Arrays.asList(new String(bytes.toByteArray(),"ISO-8859-1").trim().split("\\s+")));}}return tokens;}
    private static void near(float actual,float expected,String message){check(Math.abs(actual-expected)<.001f,message+": "+actual+" != "+expected);}
    private static void pngMemoryBounds(){int[] ample=PdfAnnotationExporter.pngDimensions(1000,1400,4096,Long.MAX_VALUE),small=PdfAnnotationExporter.pngDimensions(1000,1400,4096,32000),tall=PdfAnnotationExporter.pngDimensions(1000,10000,4096,Long.MAX_VALUE);check((long)ample[0]*ample[1]<=6000000,"fixed PNG pixel cap");check((long)small[0]*small[1]<=1000,"heap-aware PNG pixel cap");check(small[0]<ample[0],"heap cap did not reduce PNG");check(tall[0]<=4096&&tall[1]<=4096,"PNG side cap");}
    private static void coordinates(){
        PDPage page=new PDPage();page.setCropBox(new PDRectangle(10,20,200,300));float[] point;
        page.setRotation(0);point=PdfAnnotationExporter.toPdf(page,.25f,.5f);near(point[0],60,"r0 x");near(point[1],220,"r0 y");
        page.setRotation(90);point=PdfAnnotationExporter.toPdf(page,.25f,.5f);near(point[0],160,"r90 x");near(point[1],95,"r90 y");
        page.setRotation(180);point=PdfAnnotationExporter.toPdf(page,.25f,.5f);near(point[0],160,"r180 x");near(point[1],120,"r180 y");
        page.setRotation(270);point=PdfAnnotationExporter.toPdf(page,.25f,.5f);near(point[0],60,"r270 x");near(point[1],245,"r270 y");
    }
    public static void main(String[] args)throws Exception {
        File source=new File(System.getProperty("smoke.fixtures"),"sample.pdf"),root=new File(System.getProperty("smoke.root")),ink=new File(root,"ink");check(ink.mkdirs()||ink.isDirectory(),"ink directory");byte[] before=digest(source);
        InkStore.save(ink,"export-0",Collections.singletonList(new InkPage.Stroke(0xffd97718,.008f,new float[]{.1f,.1f,.8f,.3f})));
        ReaderDocument metadata=new ReaderDocument();metadata.addMarkup(0,0xfff6c343,"PDF",Collections.singletonList(new ReaderDocument.Box(.1f,.1f,.25f,.14f)));metadata.addTextBox(0,"Hello 世界\n第二行",0xff123456,.18f,.2f,.027f);
        int originalXObjects;try(PDDocument original=PDDocument.load(source)){originalXObjects=xObjects(original);}
        ByteArrayOutputStream output=new ByteArrayOutputStream();PdfAnnotationExporter.Report report=PdfAnnotationExporter.exportPdf(new Context(),source,ink,"export",metadata,PdfAnnotationExporter.PageRange.all(4),output,()->false,null);
        check(report.pages==4&&report.inkStrokes==1&&report.textHighlights==1,"report counts");check(Arrays.equals(before,digest(source)),"source PDF changed");
        try(FileOutputStream visual=new FileOutputStream(new File(root,"text-export-visual.pdf"))){output.writeTo(visual);}
        try(PDDocument exported=PDDocument.load(output.toByteArray())){check(exported.getNumberOfPages()==4,"all-page count");String text=new PDFTextStripper().getText(exported);check(text.contains("PDF"),"text layer lost");check(text.contains("Hello")&&text.contains("世界")&&text.contains("第二行"),"editable Chinese/English text lost: "+text);check(xObjects(exported)==originalXObjects,"PDF export rasterized a page");List<String> tokens=contentTokens(exported.getPage(0));check(Collections.frequency(tokens,"gs")>=3,"highlight/text/ink alpha states missing");check(tokens.indexOf("f")<tokens.lastIndexOf("Tj")&&tokens.lastIndexOf("Tj")<tokens.indexOf("S"),"PDF highlight/text/ink layer order");}
        ByteArrayOutputStream subset=new ByteArrayOutputStream();PdfAnnotationExporter.exportPdf(new Context(),source,ink,"export",metadata,new PdfAnnotationExporter.PageRange(1,3),subset,()->false,null);
        try(PDDocument exported=PDDocument.load(subset.toByteArray())){check(exported.getNumberOfPages()==2,"range count");check(new PDFTextStripper().getText(exported).length()>0,"range text lost");}
        File legacy=new File(root,"legacy-13.pdf");try(PDDocument pdf=new PDDocument()){pdf.setVersion(1.3f);pdf.addPage(new PDPage(PDRectangle.LETTER));pdf.save(legacy);}
        InkPage.Stroke translucent=new InkPage.Stroke(0x80d97718,.008f,new float[]{.1f,.1f,.8f,.3f}),opaque=new InkPage.Stroke(0xffd97718,.008f,new float[]{.2f,.3f,.7f,.5f});InkStore.save(ink,"legacy-0",Arrays.asList(translucent,opaque));ReaderDocument legacyMetadata=new ReaderDocument();legacyMetadata.addMarkup(0,0xfff6c343,"",Collections.singletonList(new ReaderDocument.Box(.1f,.2f,.3f,.25f)));
        ByteArrayOutputStream upgradedBytes=new ByteArrayOutputStream();PdfAnnotationExporter.exportPdf(new Context(),legacy,ink,"legacy",legacyMetadata,PdfAnnotationExporter.PageRange.all(1),upgradedBytes,()->false,null);
        try(PDDocument upgraded=PDDocument.load(upgradedBytes.toByteArray())){check(upgraded.getVersion()>=1.4f,"transparency did not upgrade PDF version");PDPage page=upgraded.getPage(0);int states=0;for(com.tom_roush.pdfbox.cos.COSName ignored:page.getResources().getExtGStateNames())states++;check(states==3,"expected highlight, translucent and opaque alpha resources: "+states);List<String> tokens=contentTokens(page);check(Collections.frequency(tokens,"gs")==3,"each vector mark must set alpha state");check(tokens.indexOf("f")>=0&&tokens.indexOf("f")<tokens.indexOf("S"),"highlight must be below ink");}
        ReaderDocument translucentText=new ReaderDocument();translucentText.addTextBox(0,"Tab\t中文",0x80123456,.1f,.2f,.03f);
        InkPage.Stroke pressureMarker=new InkPage.Stroke(0x55ffc300,.04f,new float[]{.1f,.2f,.15f,.2f,.2f,.2f,.25f,.2f},InkPage.KIND_FREEHAND,new float[]{.5f,.7f,1,.6f});
        InkStore.save(ink,"pressure-marker-0",Collections.singletonList(pressureMarker));
        ByteArrayOutputStream markerOutput=new ByteArrayOutputStream();PdfAnnotationExporter.exportPdf(new Context(),legacy,ink,"pressure-marker",new ReaderDocument(),PdfAnnotationExporter.PageRange.all(1),markerOutput,()->false,null);
        try(PDDocument exported=PDDocument.load(markerOutput.toByteArray())){PDPage page=exported.getPage(0);List<String> tokens=contentTokens(page);check(Collections.frequency(tokens,"f")==1&&Collections.frequency(tokens,"S")==0,"pressure marker must composite one filled shape, not overlapping segments");for(com.tom_roush.pdfbox.cos.COSName key:page.getResources().getExtGStateNames())near(page.getResources().getExtGState(key).getNonStrokingAlphaConstant(),85f/255,"single-fill marker alpha");}
        android.graphics.Canvas.pathDraws=android.graphics.Canvas.lineDraws=0;
        InkStrokeRenderer.draw(new android.graphics.Canvas(Bitmap.createBitmap(10,10,Bitmap.Config.ARGB_8888)),new android.graphics.Paint(),pressureMarker);
        check(android.graphics.Canvas.pathDraws==1&&android.graphics.Canvas.lineDraws==0,"preview/PNG pressure marker uses one compositing operation");
        ByteArrayOutputStream translucentOutput=new ByteArrayOutputStream();PdfAnnotationExporter.exportPdf(new Context(),legacy,ink,"text-only",translucentText,PdfAnnotationExporter.PageRange.all(1),translucentOutput,()->false,null);
        try(PDDocument exported=PDDocument.load(translucentOutput.toByteArray())){check(exported.getVersion()>=1.4f,"translucent text did not upgrade PDF 1.3");String value=new PDFTextStripper().getText(exported);check(value.contains("Tab")&&value.contains("中文"),"TXT tab or Chinese text could not export");}
        try{PdfAnnotationExporter.exportPdf(new Context(),source,ink,"export",metadata,PdfAnnotationExporter.PageRange.all(4),new ByteArrayOutputStream(),()->true,null);throw new AssertionError("cancel ignored");}catch(InterruptedIOException expected){}
        android.graphics.Canvas.drawnTexts.clear();PdfAnnotationExporter.exportPagePng(source,ink,"export",metadata,0,1200,new ByteArrayOutputStream(),()->false);check(android.graphics.Canvas.drawnTexts.contains("Hello 世界")&&android.graphics.Canvas.drawnTexts.contains("第二行"),"PNG text overlay missing");
        File rotated=new File(root,"rotated-text.pdf");try(PDDocument pdf=new PDDocument()){for(int angle:new int[]{0,90,180,270}){PDPage page=new PDPage(new PDRectangle(10,20,200,300));page.setRotation(angle);pdf.addPage(page);}pdf.save(rotated);}
        ReaderDocument rotationText=new ReaderDocument();for(int i=0;i<4;i++)rotationText.addTextBox(i,"Rotate "+i+" 中文",0xff172839,.1f,.2f,.025f);
        ByteArrayOutputStream rotatedOutput=new ByteArrayOutputStream();PdfAnnotationExporter.exportPdf(new Context(),rotated,ink,"rotation-text",rotationText,PdfAnnotationExporter.PageRange.all(4),rotatedOutput,()->false,null);
        try(FileOutputStream visual=new FileOutputStream(new File(root,"text-rotation-visual.pdf"))){rotatedOutput.writeTo(visual);}
        try(PDDocument pdf=PDDocument.load(rotatedOutput.toByteArray())){for(int i=0;i<4;i++){PDFTextStripper stripper=new PDFTextStripper();stripper.setStartPage(i+1);stripper.setEndPage(i+1);check(stripper.getText(pdf).contains("Rotate "+i),"rotated page text missing: "+i);}}
        pngMemoryBounds();PdfRenderer.Page.failWithOutOfMemory=true;try{PdfAnnotationExporter.exportPagePng(source,ink,"export",metadata,0,4096,new ByteArrayOutputStream(),()->false);throw new AssertionError("renderer OOM escaped conversion");}catch(PdfAnnotationExporter.ExportOutOfMemory expected){}finally{PdfRenderer.Page.failWithOutOfMemory=false;}check(Bitmap.lastCreated!=null&&Bitmap.lastCreated.isRecycled(),"bitmap was not recycled after OOM");
        coordinates();System.out.println("PdfExportSmoke passed: vector overlays/layer order, per-mark alpha reset, source immutability, text retention, range export, cancellation, heap/side/pixel PNG bounds, OOM cleanup, PDF 1.4 upgrade, rotation/crop transforms (desktop PDFBox; no Android renderer/SAF validation).");
    }
}
