package cn.qingyue.pdf;

import com.tom_roush.pdfbox.cos.*;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.*;
import java.io.*;
import java.util.*;

/** PDF-only transformation; the caller owns publication and reference-sidecar transactions. */
final class PdfBlankPageEditor {
    private PdfBlankPageEditor(){}
    static void insert(File original,File output,File cache,int index,int count)throws IOException {
        try(PDDocument document=PDDocument.load(original,MemoryUsageSetting.setupMixed(16L*1024*1024).setTempDir(cache))){
            if(document.getNumberOfPages()!=count||index<0||index>count||count<1||count>=1_000_000)throw new IOException("PDF 页数已变化，请重新打开后插页");
            if(document.isEncrypted()||!document.getCurrentAccessPermission().canModify())throw new IOException("加密或限制修改的 PDF 暂不支持插页");
            if(!document.getSignatureDictionaries().isEmpty())throw new IOException("已签名 PDF 插页会使签名失效，已取消操作");
            // Object destinations keep pointing to the same page; numeric local destinations need shifting.
            shiftDestinations(document.getDocumentCatalog().getCOSObject(),index,count,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
            PDPageLabels labels=document.getDocumentCatalog().getPageLabels();
            PDPageLabels shifted=labels==null?null:shiftLabels(document,labels,index,count);
            PDPage reference=document.getPage(index==0?0:index-1);
            PDPage blank=new PDPage(rectangle(reference.getMediaBox()));
            blank.setCropBox(rectangle(reference.getCropBox()));blank.setRotation(reference.getRotation());
            blank.setUserUnit(reference.getUserUnit());
            if(index==count)document.addPage(blank);else document.getPages().insertBefore(blank,document.getPage(index));
            if(shifted!=null)document.getDocumentCatalog().setPageLabels(shifted);
            document.save(output);
        }
        try(FileOutputStream out=new FileOutputStream(output,true)){out.getFD().sync();}
        try(PDDocument check=PDDocument.load(output,MemoryUsageSetting.setupMixed(16L*1024*1024).setTempDir(cache))){
            if(check.getNumberOfPages()!=count+1)throw new IOException("新 PDF 页数验证失败");
            for(PDPage page:check.getPages())if(page.getMediaBox().getWidth()<=0||page.getMediaBox().getHeight()<=0)throw new IOException("新 PDF 页面尺寸无效");
        }
    }
    private static PDRectangle rectangle(PDRectangle r){return new PDRectangle(r.getLowerLeftX(),r.getLowerLeftY(),r.getWidth(),r.getHeight());}
    private static PDPageLabelRange range(PDPageLabelRange r){return new PDPageLabelRange(new COSDictionary(r.getCOSObject()));}
    private static PDPageLabels shiftLabels(PDDocument doc,PDPageLabels old,int index,int count){
        PDPageLabels next=new PDPageLabels(doc);
        for(int start:old.getPageIndices())next.setLabelItem(start>=index?start+1:start,range(old.getPageLabelRange(start)));
        if(index<count&&!old.getPageIndices().contains(index)){
            Integer start=old.getPageIndices().floor(index);
            if(start!=null){PDPageLabelRange resumed=range(old.getPageLabelRange(start));if(resumed.getStyle()!=null)resumed.setStart(resumed.getStart()+index-start);next.setLabelItem(index+1,resumed);}
        }
        PDPageLabelRange blank=new PDPageLabelRange();blank.setPrefix("空白");next.setLabelItem(index,blank);return next;
    }
    private static COSBase direct(COSBase value){return value instanceof COSObject?((COSObject)value).getObject():value;}
    private static final Set<String> DEST_TYPES=new HashSet<>(Arrays.asList("XYZ","Fit","FitH","FitV","FitR","FitB","FitBH","FitBV"));
    private static void shiftDestinations(COSBase raw,int index,int count,Set<COSBase> seen)throws IOException {
        COSBase value=direct(raw);if(value==null||!seen.add(value))return;
        if(value instanceof COSArray){
            COSArray array=(COSArray)value;
            if(array.size()>=2&&array.getObject(0) instanceof COSInteger&&array.getObject(1) instanceof COSName&&DEST_TYPES.contains(((COSName)array.getObject(1)).getName())){
                int page=array.getInt(0);if(page<0||page>=count)throw new IOException("PDF 内部跳转页码无效，已取消插页");if(page>=index)array.setInt(0,page+1);
            }
            for(COSBase child:array)shiftDestinations(child,index,count,seen);
        }else if(value instanceof COSDictionary){
            COSDictionary dictionary=(COSDictionary)value;
            boolean remote="GoToR".equals(dictionary.getNameAsString(COSName.S))||"GoToE".equals(dictionary.getNameAsString(COSName.S));
            for(Map.Entry<COSName,COSBase> child:dictionary.entrySet()){
                if(remote&&COSName.D.equals(child.getKey()))continue;
                shiftDestinations(child.getValue(),index,count,seen);
            }
        }
    }
}
