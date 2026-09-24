package cn.qingyue.pdf;

import java.util.Collection;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** A logical library folder; IDs keep names, including legacy slashes, literal. */
public final class LibraryFolder {
    public String id = "";
    public String parentId = "";
    public String name = "";

    public LibraryFolder() { }

    public LibraryFolder(String id, String parentId, String name) {
        this.id = id;
        this.parentId = parentId;
        this.name = name;
        validate();
    }

    public LibraryFolder copy() { return new LibraryFolder(id, parentId, name); }

    public void validate() {
        if (!LibraryItem.isUuid(id) || parentId == null || (!parentId.isEmpty() && !LibraryItem.isUuid(parentId))
                || id.equals(parentId)) throw new IllegalArgumentException("无效的文件夹编号");
        if (!LibraryItem.hasVisibleText(name) || name.length() > 100 || name.indexOf('\n') >= 0)
            throw new IllegalArgumentException("无效的文件夹名称");
    }

    public static void validateTree(Collection<LibraryFolder> folders) {
        Set<String> ids = new HashSet<>();
        Map<String, LibraryFolder> byId = new HashMap<>();
        if (folders == null) return;
        for (LibraryFolder folder : folders) {
            if (folder == null) throw new IllegalArgumentException("文件夹为空");
            folder.validate();
            if (!ids.add(folder.id)) throw new IllegalArgumentException("重复的文件夹编号");
            byId.put(folder.id, folder);
        }
        for (LibraryFolder folder : folders) {
            String parent = folder.parentId;
            Set<String> seen = new HashSet<>();
            while (!parent.isEmpty()) {
                if (!ids.contains(parent) || !seen.add(parent)) throw new IllegalArgumentException("文件夹层级无效");
                if (seen.size() > 64) throw new IllegalArgumentException("文件夹层级过深");
                parent = byId.get(parent).parentId;
            }
        }
    }
}
