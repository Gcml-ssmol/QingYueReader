package cn.qingyue.pdf;

import android.content.Context;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** Appends invisible positioned text to selected pages while retaining every original PDF page. */
public final class OcrPdfExporter {
    private OcrPdfExporter() { }
    /** Production path: a bundled, licensable TTF is copied to a temporary file for PDFBox. */
    public static void export(Context context, File input, File output, Map<Integer,OcrHocr.Page> pages,
            OcrCancellation cancellation) throws IOException {
        PDFBoxResourceLoader.init(context.getApplicationContext());
        File font=File.createTempFile("ocr-font-",".ttf",context.getCacheDir());
        try {
            try(InputStream in=context.getAssets().open("ocr/ocr-font.ttf"); FileOutputStream out=new FileOutputStream(font)) {
                byte[] bytes=new byte[65536]; int n;
                while((n=in.read(bytes))!=-1) { cancellation.throwIfCancelled(); out.write(bytes,0,n); }
            }
            export(input,output,pages,font,new File(context.getCacheDir(),"ocr-pdfbox"),cancellation);
        } finally { font.delete(); }
    }
    /** Injectable file path for tests; font remains open internally until document.save completes. */
    public static void export(File input,File output,Map<Integer,OcrHocr.Page> pages,File fontFile,
            File scratch,OcrCancellation cancellation) throws IOException {
        if(input==null||!input.isFile()||output==null||pages==null||pages.isEmpty()||fontFile==null||!fontFile.isFile())
            throw new IOException("PDF OCR 导出输入无效");
        if(input.getCanonicalPath().equals(output.getCanonicalPath()))throw new IOException("不能覆盖原始 PDF");
        if(!scratch.isDirectory()&&!scratch.mkdirs())throw new IOException("无法创建 PDF 临时目录");
        cancellation.throwIfCancelled();
        try(PDDocument pdf=PDDocument.load(input,MemoryUsageSetting.setupMixed(12L*1024*1024).setTempDir(scratch))) {
            if(!pdf.getCurrentAccessPermission().canModify()||!pdf.getCurrentAccessPermission().canExtractContent())
                throw new IOException("PDF 权限不允许添加或提取文字层");
            if(!pdf.getSignatureDictionaries().isEmpty())
                throw new IOException("签名 PDF 的签名会因 OCR 导出失效，请先另存为无签名副本");
            PDType0Font font=PDType0Font.load(pdf,fontFile);
            for(Map.Entry<Integer,OcrHocr.Page> entry:pages.entrySet()) {
                cancellation.throwIfCancelled(); int index=entry.getKey(); OcrHocr.Page recognized=entry.getValue();
                if(index<0||index>=pdf.getNumberOfPages()||recognized==null||recognized.words.isEmpty())
                    throw new IOException("OCR 页码或词框无效");
                PDPage page=pdf.getPage(index); PDRectangle crop=page.getCropBox(); if(crop==null)crop=page.getMediaBox();
                int rotation=((page.getRotation()%360)+360)%360;
                if(rotation%90!=0)throw new IOException("不支持该 PDF 页面旋转角度");
                float visualWidth=rotation%180==0?crop.getWidth():crop.getHeight();
                float visualHeight=rotation%180==0?crop.getHeight():crop.getWidth();
                try(PDPageContentStream stream=new PDPageContentStream(pdf,page,PDPageContentStream.AppendMode.APPEND,true,true)) {
                    stream.saveGraphicsState();
                    for(OcrHocr.Word word:recognized.words) {
                        cancellation.throwIfCancelled();
                        try { font.encode(word.text); } catch(IOException | IllegalArgumentException glyph) { throw new IOException("OCR 字体缺少字符："+word.text,glyph); }
                        float boxWidth=(word.right-word.left)*visualWidth/recognized.width;
                        float boxHeight=(word.bottom-word.top)*visualHeight/recognized.height;
                        float measured=font.getStringWidth(word.text)/1000f;
                        if(boxWidth<=0||boxHeight<=0||measured<=0)throw new IOException("OCR 文字框或字形宽度无效");
                        float u=word.left/(float)recognized.width, v=1f-word.bottom/(float)recognized.height;
                        float sx=boxWidth/measured,sy=boxHeight;
                        float[] origin=toPagePoint(crop,rotation,u,v);float x=origin[0],y=origin[1];
                        stream.beginText();stream.setRenderingMode(RenderingMode.NEITHER);stream.setFont(font,1f);
                        if(rotation==0)stream.setTextMatrix(sx,0,0,sy,x,y);
                        else if(rotation==90)stream.setTextMatrix(0,sx,-sy,0,x,y);
                        else if(rotation==180)stream.setTextMatrix(-sx,0,0,-sy,x,y);
                        else stream.setTextMatrix(0,-sx,sy,0,x,y);
                        stream.showText(word.text);stream.endText();
                    }
                    stream.restoreGraphicsState();
                }
            }
            cancellation.throwIfCancelled();pdf.save(output);cancellation.throwIfCancelled();
        } catch(RuntimeException | LinkageError failure) { throw new IOException("PDF 文字层导出失败",failure); }
    }
    static float[] toPagePoint(PDRectangle crop,int rotation,float u,float v){
        float left=crop.getLowerLeftX(),bottom=crop.getLowerLeftY(),width=crop.getWidth(),height=crop.getHeight();
        if(rotation==0)return new float[]{left+u*width,bottom+v*height};
        if(rotation==90)return new float[]{left+(1f-v)*width,bottom+u*height};
        if(rotation==180)return new float[]{left+(1f-u)*width,bottom+(1f-v)*height};
        if(rotation==270)return new float[]{left+v*width,bottom+(1f-u)*height};
        throw new IllegalArgumentException("Unsupported rotation");
    }
}
