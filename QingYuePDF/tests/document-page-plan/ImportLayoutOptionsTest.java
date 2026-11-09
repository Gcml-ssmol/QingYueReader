package cn.qingyue.pdf;

import android.content.Context;
import java.io.File;

/** Runs on desktop; checks layout API and legacy overloads without Android PDF execution. */
public final class ImportLayoutOptionsTest {
    public static void main(String[] args) throws Exception {
        DocumentImport.LayoutOptions phone=new DocumentImport.LayoutOptions(
                DocumentImport.PagePreset.PHONE,DocumentImport.FontSize.MEDIUM);
        check(phone.pageWidth()==360&&phone.pageHeight()==640&&phone.textSize()==16.5f,
                "phone medium plan");
        DocumentImport.LayoutOptions a4=new DocumentImport.LayoutOptions(
                DocumentImport.PagePreset.A4,DocumentImport.FontSize.SMALL);
        check(a4.pageWidth()==595&&a4.pageHeight()==842&&a4.textSize()==12.5f,
                "legacy A4 plan");
        Class<?>[] old={Context.class,File.class,String.class,String.class,File.class};
        check(DocumentImport.class.getMethod("convert",old)!=null,"legacy API without callback");
        check(DocumentImport.class.getMethod("convert",Context.class,File.class,String.class,
                String.class,File.class,DocumentImport.Callback.class)!=null,"legacy callback API");
        check(DocumentImport.class.getMethod("convert",Context.class,File.class,String.class,
                String.class,File.class,DocumentImport.Callback.class,
                DocumentImport.LayoutOptions.class)!=null,"new layout API");
        System.out.println("ImportLayoutOptionsTest OK");
    }
    private static void check(boolean okay,String message){if(!okay)throw new AssertionError(message);}
}
