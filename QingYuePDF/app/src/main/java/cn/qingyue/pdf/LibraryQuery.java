package cn.qingyue.pdf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Deterministic filtering and sorting shared by the library list and tests. */
public final class LibraryQuery {
    public enum Sort { MODIFIED, OPENED, NAME, CREATED }

    public static final class Filter {
        public String keyword = "";
        public String folder = "";
        public String folderId = "";
        public boolean rootOnly;
        public String tag = "";
        public boolean favoriteOnly;
        public boolean includeTrashed;
        public boolean unfiledOnly;

        public Filter keyword(String value) { keyword = safe(value); return this; }
        public Filter folder(String value) { folder = safe(value); return this; }
        public Filter folderId(String value) { folderId = safe(value); return this; }
        public Filter rootOnly(boolean value) { rootOnly = value; return this; }
        public Filter tag(String value) { tag = safe(value); return this; }
        public Filter favoriteOnly(boolean value) { favoriteOnly = value; return this; }
        public Filter includeTrashed(boolean value) { includeTrashed = value; return this; }
        public Filter unfiledOnly(boolean value) { unfiledOnly = value; return this; }
    }

    private LibraryQuery() { }

    public static List<LibraryItem> apply(Collection<LibraryItem> source, Filter filter, Sort sort) {
        Filter wanted = filter == null ? new Filter() : filter;
        String keyword = normalize(wanted.keyword);
        String folder = normalize(wanted.folder);
        String tag = normalize(wanted.tag);
        ArrayList<LibraryItem> result = new ArrayList<>();
        if (source != null) {
            for (LibraryItem item : source) {
                if (item == null) continue;
                if (!wanted.includeTrashed && item.isTrashed()) continue;
                if (wanted.favoriteOnly && !item.favorite) continue;
                if (wanted.unfiledOnly && !normalize(item.folder).isEmpty()) continue;
                if (wanted.rootOnly && !safe(item.folderId).isEmpty()) continue;
                if (!wanted.folderId.isEmpty() && !wanted.folderId.equals(item.folderId)) continue;
                if (!wanted.unfiledOnly && !folder.isEmpty() && !folder.equals(normalize(item.folder))) continue;
                if (!tag.isEmpty() && !hasTag(item, tag)) continue;
                if (!keyword.isEmpty() && !matches(item, keyword)) continue;
                result.add(item);
            }
        }
        Collections.sort(result, comparator(sort == null ? Sort.MODIFIED : sort));
        return result;
    }

    public static Comparator<LibraryItem> comparator(Sort sort) {
        final Sort selected = sort == null ? Sort.MODIFIED : sort;
        return (left, right) -> {
            int compared;
            switch (selected) {
                case OPENED:
                    compared = Long.compare(right.opened, left.opened);
                    break;
                case CREATED:
                    compared = Long.compare(right.created, left.created);
                    break;
                case NAME:
                    compared = compareText(left.name, right.name);
                    if (compared != 0) return compared;
                    return safe(left.id).compareTo(safe(right.id));
                case MODIFIED:
                default:
                    compared = Long.compare(right.modified, left.modified);
                    break;
            }
            if (compared != 0) return compared;
            compared = compareText(left.name, right.name);
            if (compared != 0) return compared;
            return safe(left.id).compareTo(safe(right.id));
        };
    }

    private static boolean matches(LibraryItem item, String keyword) {
        if (normalize(item.name).contains(keyword) || normalize(item.originalName).contains(keyword)
                || normalize(item.folder).contains(keyword) || normalize(item.format).contains(keyword)) {
            return true;
        }
        for (String value : item.tags) if (normalize(value).contains(keyword)) return true;
        return false;
    }

    private static boolean hasTag(LibraryItem item, String wanted) {
        for (String value : item.tags) if (wanted.equals(normalize(value))) return true;
        return false;
    }

    private static int compareText(String left, String right) {
        String a = normalize(left), b = normalize(right);
        int compared = a.compareTo(b);
        if (compared != 0) return compared;
        compared = safe(left).compareTo(safe(right));
        return compared;
    }

    private static String normalize(String value) {
        return safe(value).trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
