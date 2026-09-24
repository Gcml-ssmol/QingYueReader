package cn.qingyue.pdf;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.List;

/** Counts the library categories shown in navigation, excluding deleted items. */
public final class LibrarySidebarModel {
    public final int all;
    public final int unfiled;
    public final int trashed;
    public final Map<String,Integer> folders;
    public final List<LibraryFolder> folderTree;

    private LibrarySidebarModel(int all,int unfiled,int trashed,Map<String,Integer> folders){
        this.all=all;this.unfiled=unfiled;this.trashed=trashed;
        this.folders=Collections.unmodifiableMap(folders);this.folderTree=Collections.emptyList();
    }
    private LibrarySidebarModel(int all,int unfiled,int trashed,Map<String,Integer> counts,List<LibraryFolder> tree){
        this.all=all;this.unfiled=unfiled;this.trashed=trashed;
        this.folders=Collections.unmodifiableMap(counts);this.folderTree=Collections.unmodifiableList(tree);
    }
    public static LibrarySidebarModel from(Collection<LibraryItem> items){
        TreeMap<String,Integer> folders=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int all=0,unfiled=0,trashed=0;
        if(items!=null)for(LibraryItem item:items){
            if(item==null)continue;
            if(item.isTrashed()){trashed++;continue;}
            all++;String folder=item.folder==null?"":item.folder.trim();
            if(folder.isEmpty())unfiled++;
            else folders.put(folder,folders.containsKey(folder)?folders.get(folder)+1:1);
        }
        return new LibrarySidebarModel(all,unfiled,trashed,folders);
    }
    public static LibrarySidebarModel from(Collection<LibraryItem> items,Collection<LibraryFolder> source){
        TreeMap<String,Integer> counts=new TreeMap<>();
        ArrayList<LibraryFolder> tree=new ArrayList<>();
        if(source!=null)for(LibraryFolder folder:source){counts.put(folder.id,0);tree.add(folder);}
        tree.sort((a,b)->{int compared=a.name.compareToIgnoreCase(b.name);return compared!=0?compared:a.id.compareTo(b.id);});
        int all=0,unfiled=0,trashed=0;
        if(items!=null)for(LibraryItem item:items){
            if(item==null)continue;
            if(item.isTrashed()){trashed++;continue;}
            all++;
            if(item.folderId==null||item.folderId.isEmpty())unfiled++;
            else if(counts.containsKey(item.folderId))counts.put(item.folderId,counts.get(item.folderId)+1);
        }
        return new LibrarySidebarModel(all,unfiled,trashed,counts,tree);
    }
}
