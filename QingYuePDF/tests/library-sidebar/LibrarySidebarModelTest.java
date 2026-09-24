import cn.qingyue.pdf.LibraryItem;
import cn.qingyue.pdf.LibraryQuery;
import cn.qingyue.pdf.LibrarySidebarModel;
import java.util.Arrays;
import java.util.List;

public final class LibrarySidebarModelTest {
    private static LibraryItem item(String id,String folder,boolean trash){
        LibraryItem item=new LibraryItem();item.id=id;item.name=id;item.folder=folder;item.trashedAt=trash?1:0;return item;
    }
    private static void expect(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    public static void main(String[] args){
        LibraryItem a=item("a","",false),b=item("b","  ",false),c=item("c","研究",false);
        LibraryItem d=item("d","研究",false),e=item("e","工作",false),f=item("f","研究",true);
        List<LibraryItem> items=Arrays.asList(a,b,c,d,e,f);
        LibrarySidebarModel model=LibrarySidebarModel.from(items);
        expect(model.all==5,"active count");expect(model.unfiled==2,"unfiled count");
        expect(model.trashed==1,"trashed count");expect(model.folders.size()==2,"folder count");
        expect(model.folders.get("研究")==2&&model.folders.get("工作")==1,"folder item counts");
        List<LibraryItem> unfiled=LibraryQuery.apply(items,new LibraryQuery.Filter().unfiledOnly(true),LibraryQuery.Sort.NAME);
        expect(unfiled.size()==2&&unfiled.contains(a)&&unfiled.contains(b),"unfiled excludes folders and trash");
        List<LibraryItem> research=LibraryQuery.apply(items,new LibraryQuery.Filter().folder("研究"),LibraryQuery.Sort.NAME);
        expect(research.size()==2&&research.contains(c)&&research.contains(d),"folder excludes trash");
        List<LibraryItem> trash=LibraryQuery.apply(Arrays.asList(f),new LibraryQuery.Filter().includeTrashed(true),LibraryQuery.Sort.NAME);
        expect(trash.size()==1,"trash is selectable separately");
        System.out.println("LibrarySidebarModelTest passed");
    }
}
