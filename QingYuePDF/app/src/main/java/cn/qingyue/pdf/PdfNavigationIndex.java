package cn.qingyue.pdf;

import android.content.Context;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDDestinationOrAction;
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabels;
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDAction;
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.*;
import java.io.*;
import java.util.*;

/** Bounded local outline/page-label extraction. External actions are never executed. */
public final class PdfNavigationIndex {
    public static final int MAX_OUTLINE_ITEMS=2000,MAX_DEPTH=32;
    public static final class Item {
        public final String title,label;public final int page,depth;
        Item(String title,String label,int page,int depth){this.title=title;this.label=label;this.page=page;this.depth=depth;}
    }
    public final List<Item> outline;public final String[] pageLabels;
    private PdfNavigationIndex(List<Item> outline,String[] labels){this.outline=Collections.unmodifiableList(outline);this.pageLabels=labels.clone();}
    public String label(int page){return page>=0&&page<pageLabels.length?pageLabels[page]:Integer.toString(page+1);}

    public static PdfNavigationIndex load(Context context,File file)throws IOException {
        if(context==null||file==null||!file.isFile())throw new FileNotFoundException("PDF");PDFBoxResourceLoader.init(context.getApplicationContext());
        File scratch=new File(context.getCacheDir(),"pdf-navigation");if(!scratch.isDirectory()&&!scratch.mkdirs())throw new IOException("Cannot create scratch directory");
        try(PDDocument document=PDDocument.load(file,MemoryUsageSetting.setupMixed(12L*1024*1024).setTempDir(scratch))){
            String[] labels=null;
            try{PDPageLabels pageLabels=document.getDocumentCatalog().getPageLabels();if(pageLabels!=null)labels=pageLabels.getLabelsByPageIndices();}
            catch(IOException|RuntimeException malformedLabels){/* Broken labels do not invalidate a usable outline. */}
            if(labels==null||labels.length!=document.getNumberOfPages()){labels=new String[document.getNumberOfPages()];for(int i=0;i<labels.length;i++)labels[i]=Integer.toString(i+1);}
            else for(int i=0;i<labels.length;i++)if(labels[i]==null||labels[i].trim().length()==0)labels[i]=Integer.toString(i+1);
            ArrayList<Item> items=new ArrayList<>();PDDocumentOutline root=document.getDocumentCatalog().getDocumentOutline();
            if(root!=null){Set<Object> visited=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());visited.add(root.getCOSObject());walk(document,root,0,labels,items,visited);}
            return new PdfNavigationIndex(items,labels);
        }catch(RuntimeException problem){
            throw new IOException("Cannot read PDF outline or page labels",problem);
        }
    }
    private static void walk(PDDocument document,PDOutlineNode node,int depth,String[] labels,List<Item> out,Set<Object> visited)throws IOException {
        if(depth>MAX_DEPTH||out.size()>=MAX_OUTLINE_ITEMS||node==null)return;
        for(PDOutlineItem child=node.getFirstChild();child!=null;child=child.getNextSibling()){
            if(visited.size()>MAX_OUTLINE_ITEMS||!visited.add(child.getCOSObject()))break;
            int page=destinationPage(document,child);String title=clean(child.getTitle());
            if(page>=0&&page<labels.length&&title.length()>0)out.add(new Item(title,labels[page],page,depth));
            if(depth<MAX_DEPTH&&child.hasChildren())walk(document,child,depth+1,labels,out,visited);
        }
    }
    private static int destinationPage(PDDocument document,PDOutlineItem item){
        try{
            PDDestinationOrAction destination=item.getDestination();PDAction action=item.getAction();
            if(destination==null&&action!=null&&!(action instanceof PDActionGoTo))return -1;
            PDPage page=item.findDestinationPage(document);return page==null?-1:document.getPages().indexOf(page);
        }catch(IOException|RuntimeException ignored){return -1;}
    }
    private static String clean(String value){if(value==null)return "";value=value.replace('\u0000',' ').trim();return value.length()>300?value.substring(0,300):value;}
}
