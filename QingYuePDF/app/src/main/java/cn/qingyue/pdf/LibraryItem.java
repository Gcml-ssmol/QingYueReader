package cn.qingyue.pdf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Versioned metadata for one document in the local library. */
public final class LibraryItem {
    public String id = "";
    public String name = "";
    public String hash = "";
    public String type = "pdf";
    public String format = "PDF";
    public String template = "blank";
    public String paper = "A4";
    public int pages = 1;
    public int page;
    public long size;
    public String folder = "";
    /** Stable logical folder ID. Empty means unfiled; folder retains the legacy display text. */
    public String folderId = "";
    public final ArrayList<String> tags = new ArrayList<>();
    public boolean favorite;
    public long created;
    public long modified;
    public long opened;
    public long trashedAt;
    /** Original display name before conversion or renaming. */
    public String originalName = "";
    /** Safe path relative to files/library/attachments, or empty when no source was retained. */
    public String attachment = "";

    public boolean isNote() {
        return "note".equals(type);
    }

    public boolean isTrashed() {
        return trashedAt > 0;
    }

    public LibraryItem copy() {
        LibraryItem copy = new LibraryItem();
        copy.id = id;
        copy.name = name;
        copy.hash = hash;
        copy.type = type;
        copy.format = format;
        copy.template = template;
        copy.paper = paper;
        copy.pages = pages;
        copy.page = page;
        copy.size = size;
        copy.folder = folder;
        copy.folderId = folderId;
        copy.tags.addAll(tags);
        copy.favorite = favorite;
        copy.created = created;
        copy.modified = modified;
        copy.opened = opened;
        copy.trashedAt = trashedAt;
        copy.originalName = originalName;
        copy.attachment = attachment;
        return copy;
    }

    /** Rejects unsafe paths and unbounded metadata before it reaches the on-disk index. */
    public void validate() {
        if (!isUuid(id)) throw new IllegalArgumentException("无效的文档编号");
        if (!hasVisibleText(name) || name.length() > 512) throw new IllegalArgumentException("无效的文档名称");
        if (!("pdf".equals(type) || "note".equals(type))) throw new IllegalArgumentException("无效的文档类型");
        if (format == null || format.length() > 24) throw new IllegalArgumentException("无效的文档格式");
        if (template == null || template.length() > 24 || paper == null || paper.length() > 24) {
            throw new IllegalArgumentException("无效的纸张信息");
        }
        if (pages < 1 || pages > 1000000) throw new IllegalArgumentException("无效的页数");
        if (page < 0 || page >= pages) throw new IllegalArgumentException("无效的阅读页码");
        if (size < 0 || created < 0 || modified < 0 || opened < 0 || trashedAt < 0) {
            throw new IllegalArgumentException("无效的时间或大小");
        }
        if (hash == null || (!hash.isEmpty() && !hash.matches("[0-9a-fA-F]{64}"))) {
            throw new IllegalArgumentException("无效的内容摘要");
        }
        if (folder == null || folder.length() > 100 || containsControl(folder)) {
            throw new IllegalArgumentException("无效的文件夹名称");
        }
        if (folderId == null || (!folderId.isEmpty() && !isUuid(folderId))) {
            throw new IllegalArgumentException("无效的文件夹编号");
        }
        if (tags.size() > 64) throw new IllegalArgumentException("标签数量过多");
        Set<String> normalized = new HashSet<>();
        for (String tag : tags) {
            if (!hasVisibleText(tag) || tag.length() > 50 || containsControl(tag)) {
                throw new IllegalArgumentException("无效的标签");
            }
            if (!normalized.add(tag.trim().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("重复的标签");
            }
        }
        if (originalName == null || originalName.length() > 512 || containsControl(originalName)) {
            throw new IllegalArgumentException("无效的原文件名称");
        }
        if (!safeRelativePath(attachment)) throw new IllegalArgumentException("无效的原文件附件路径");
        if (!attachment.isEmpty()) {
            int separator = attachment.indexOf('/');
            if (separator <= 0 || !attachment.substring(0, separator).equalsIgnoreCase(id)) {
                throw new IllegalArgumentException("原文件附件不属于当前资料");
            }
        }
    }

    static boolean isUuid(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    static boolean hasVisibleText(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        return !containsControl(value);
    }

    private static boolean containsControl(String value) {
        if (value == null) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 && character != '\t') return true;
        }
        return false;
    }

    private static boolean safeRelativePath(String value) {
        if (value == null) return false;
        if (value.isEmpty()) return true;
        if (value.length() > 300 || value.startsWith("/") || value.startsWith("\\") || value.indexOf(':') >= 0 || value.indexOf('\\') >= 0) {
            return false;
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment) || containsControl(segment)) return false;
        }
        return true;
    }

    static ArrayList<String> cleanedTags(List<String> source) {
        ArrayList<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (source == null) return result;
        for (String value : source) {
            if (value == null) continue;
            String tag = value.trim();
            String normalized = tag.toLowerCase(Locale.ROOT);
            if (!tag.isEmpty() && tag.length() <= 50 && seen.add(normalized)) result.add(tag);
            if (result.size() == 64) break;
        }
        return result;
    }
}
