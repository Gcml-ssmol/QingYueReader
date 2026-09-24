package cn.qingyue.pdf;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Strict, dependency-free codec for the legacy array and version 2 library index. */
public final class LibraryIndex {
    public static final int SCHEMA = 2;
    public static final int MAX_ITEMS = 10000;
    public static final int MAX_JSON_CHARS = 16 * 1024 * 1024;

    public static final class Damage {
        public final int index;
        public final String raw;
        public final String reason;

        Damage(int index, String raw, String reason) {
            this.index = index;
            this.raw = raw == null ? "" : raw;
            this.reason = reason == null ? "未知错误" : reason;
        }
    }

    public static final class Result {
        public final ArrayList<LibraryItem> items;
        public final ArrayList<LibraryFolder> folders;
        public final ArrayList<Damage> damaged;
        public final boolean allowSave;
        public final boolean legacy;
        /** Exact source text when the top level could not be trusted. */
        public final String topLevelRaw;

        Result(ArrayList<LibraryItem> items, ArrayList<Damage> damaged, boolean allowSave,
                boolean legacy, String topLevelRaw) {
            this(items, new ArrayList<>(), damaged, allowSave, legacy, topLevelRaw);
        }

        Result(ArrayList<LibraryItem> items, ArrayList<LibraryFolder> folders, ArrayList<Damage> damaged,
                boolean allowSave, boolean legacy, String topLevelRaw) {
            this.items = items;
            this.folders = folders;
            this.damaged = damaged;
            this.allowSave = allowSave;
            this.legacy = legacy;
            this.topLevelRaw = topLevelRaw == null ? "" : topLevelRaw;
        }
    }

    private LibraryIndex() { }

    /** Migrates the 1.7 SharedPreferences JSON array without letting one bad item hide later items. */
    public static Result migrateLegacy(String json, long fallbackTime) {
        return decode(json, true, Math.max(0, fallbackTime));
    }

    public static Result decodeV2(String json) {
        return decode(json, false, 0);
    }

    private static Result decode(String source, boolean legacy, long fallbackTime) {
        String json = source == null ? "" : source;
        ArrayList<LibraryItem> items = new ArrayList<>();
        ArrayList<LibraryFolder> folders = new ArrayList<>();
        ArrayList<Damage> damaged = new ArrayList<>();
        if (json.length() > MAX_JSON_CHARS) {
            damaged.add(new Damage(-1, json, "索引超过大小限制"));
            return new Result(items, damaged, false, legacy, json);
        }
        try {
            Node root = new Parser(json).parse();
            Node array;
            if (legacy) {
                array = require(root, Node.ARRAY, "旧版索引不是数组");
            } else {
                Node object = require(root, Node.OBJECT, "索引顶层不是对象");
                long schema = requiredLong(object, "schema");
                if (schema != SCHEMA) throw new IllegalArgumentException("不支持的索引版本 " + schema);
                array = required(object, "items");
                require(array, Node.ARRAY, "items 不是数组");
            }
            if (array.array.size() > MAX_ITEMS) throw new IllegalArgumentException("文档条目数量超过限制");
            Set<String> ids = new HashSet<>();
            for (int index = 0; index < array.array.size(); index++) {
                Node value = array.array.get(index);
                try {
                    LibraryItem item = parseItem(value, legacy, fallbackTime);
                    if (!ids.add(item.id)) throw new IllegalArgumentException("重复的文档编号");
                    items.add(item);
                } catch (RuntimeException problem) {
                    damaged.add(new Damage(index, value.raw(json), message(problem)));
                }
            }
            if (!legacy) {
                Node folderArray = optional(root, "folders");
                if (folderArray != null) {
                    require(folderArray, Node.ARRAY, "folders 不是数组");
                    if (folderArray.array.size() > MAX_ITEMS) throw new IllegalArgumentException("文件夹数量超过限制");
                    for (Node entry : folderArray.array) {
                        Node folderNode = require(entry, Node.OBJECT, "文件夹不是对象");
                        folders.add(new LibraryFolder(requiredString(folderNode, "id"),
                                optionalString(folderNode, "parentId", ""), requiredString(folderNode, "name")));
                    }
                    LibraryFolder.validateTree(folders);
                }
            }
            // Pre-folder indexes treated each original folder string as one flat name.
            // A slash in that string was literal, never an inferred hierarchy.
            Map<String, String> oldNames = new LinkedHashMap<>();
            for (LibraryFolder folder : folders) if (folder.parentId.isEmpty()) oldNames.put(folder.name, folder.id);
            for (LibraryItem item : items) {
                if (!item.folderId.isEmpty()) {
                    boolean known = false;
                    for (LibraryFolder folder : folders) if (folder.id.equals(item.folderId)) { known = true; break; }
                    if (!known) throw new IllegalArgumentException("资料引用不存在的文件夹");
                } else if (!item.folder.isEmpty()) {
                    String id = oldNames.get(item.folder);
                    if (id == null) {
                        id = UUID.nameUUIDFromBytes(("qingyue:legacy-folder:" + item.folder).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                        oldNames.put(item.folder, id);
                        folders.add(new LibraryFolder(id, "", item.folder));
                    }
                    item.folderId = id;
                }
            }
            LibraryFolder.validateTree(folders);
            return new Result(items, folders, damaged, true, legacy, "");
        } catch (RuntimeException problem) {
            damaged.add(new Damage(-1, json, message(problem)));
            return new Result(items, damaged, false, legacy, json);
        }
    }

    public static String encodeV2(Collection<LibraryItem> source, long generatedAt) {
        if (source != null) for (LibraryItem item : source)
            if (item != null && item.folderId != null && !item.folderId.isEmpty())
                throw new IllegalArgumentException("包含文件夹编号的资料须同时保存文件夹目录");
        return encodeV2(source, null, generatedAt);
    }

    public static String encodeV2(Collection<LibraryItem> source, Collection<LibraryFolder> folders, long generatedAt) {
        if (source == null) throw new IllegalArgumentException("文档列表为空");
        if (source.size() > MAX_ITEMS) throw new IllegalArgumentException("文档条目数量超过限制");
        if (folders != null && folders.size() > MAX_ITEMS) throw new IllegalArgumentException("文件夹数量超过限制");
        LibraryFolder.validateTree(folders);
        Set<String> folderIds = new HashSet<>();
        if (folders != null) for (LibraryFolder folder : folders) folderIds.add(folder.id);
        StringBuilder json = new StringBuilder(Math.max(128, source.size() * 256));
        json.append('{');
        member(json, "schema", SCHEMA).append(',');
        member(json, "generatedAt", Math.max(0, generatedAt)).append(',');
        quote(json, "items").append(':').append('[');
        Set<String> ids = new HashSet<>();
        boolean first = true;
        for (LibraryItem item : source) {
            if (item == null) throw new IllegalArgumentException("文档条目为空");
            item.validate();
            if (folders != null && !item.folderId.isEmpty() && !folderIds.contains(item.folderId))
                throw new IllegalArgumentException("资料引用不存在的文件夹");
            if (!ids.add(item.id)) throw new IllegalArgumentException("重复的文档编号");
            if (!first) json.append(',');
            first = false;
            appendItem(json, item);
            if (json.length() > MAX_JSON_CHARS) throw new IllegalArgumentException("索引超过大小限制");
        }
        json.append(']');
        if (folders != null && !folders.isEmpty()) {
            json.append(',');quote(json, "folders").append(':').append('[');
            boolean firstFolder = true;
            for (LibraryFolder folder : folders) {
                if (!firstFolder) json.append(',');
                firstFolder = false;
                json.append('{');member(json, "id", folder.id).append(',');
                member(json, "parentId", folder.parentId).append(',');member(json, "name", folder.name);
                json.append('}');
            }
            json.append(']');
        }
        json.append('}');
        if (json.length() > MAX_JSON_CHARS) throw new IllegalArgumentException("索引超过大小限制");
        return json.toString();
    }

    /** A standalone recovery manifest whose raw fields preserve damaged source records exactly. */
    public static String encodeRecovery(Result result, long recordedAt) {
        if (result == null) throw new IllegalArgumentException("恢复信息为空");
        StringBuilder json = new StringBuilder();
        json.append('{');
        member(json, "source", result.legacy ? "legacy-1.7" : "index-v2").append(',');
        member(json, "recordedAt", Math.max(0, recordedAt)).append(',');
        member(json, "allowSave", result.allowSave).append(',');
        quote(json, "damaged").append(':').append('[');
        for (int index = 0; index < result.damaged.size(); index++) {
            if (index > 0) json.append(',');
            Damage damage = result.damaged.get(index);
            json.append('{');
            member(json, "index", damage.index).append(',');
            member(json, "reason", damage.reason).append(',');
            member(json, "raw", damage.raw);
            json.append('}');
        }
        json.append(']');
        if (!result.topLevelRaw.isEmpty()) {
            json.append(',');
            member(json, "topLevelRaw", result.topLevelRaw);
        }
        return json.append('}').toString();
    }

    private static LibraryItem parseItem(Node value, boolean legacy, long fallbackTime) {
        Node object = require(value, Node.OBJECT, "文档条目不是对象");
        LibraryItem item = new LibraryItem();
        item.id = requiredString(object, "id");
        item.name = requiredString(object, "name");
        item.hash = optionalString(object, "hash", "");
        item.pages = requiredInt(object, "pages");
        item.page = optionalInt(object, "page", 0);
        item.size = optionalLong(object, "size", 0);
        item.opened = optionalLong(object, "opened", 0);
        item.type = optionalString(object, "type", "pdf");
        item.format = optionalString(object, "format", item.isNote() ? "NOTE" : "PDF");
        item.template = optionalString(object, "template", "blank");
        item.paper = optionalString(object, "paper", "A4");
        item.folder = optionalString(object, "folder", "");
        item.folderId = optionalString(object, "folderId", "");
        item.favorite = optionalBoolean(object, "favorite", false);
        item.created = optionalLong(object, "created", legacy ? fallbackTime : 0);
        item.modified = optionalLong(object, "modified", legacy ? fallbackTime : 0);
        item.trashedAt = optionalLong(object, "trashedAt", 0);
        item.originalName = optionalString(object, "originalName", item.name);
        item.attachment = optionalString(object, "attachment", "");
        Node tags = optional(object, "tags");
        if (tags != null) {
            require(tags, Node.ARRAY, "tags 不是数组");
            for (Node tag : tags.array) item.tags.add(asString(tag, "标签不是文字"));
        }
        if (legacy) {
            item.page = Math.max(0, Math.min(item.page, Math.max(0, item.pages - 1)));
            item.size = Math.max(0, item.size);
            item.opened = Math.max(0, item.opened);
        }
        item.validate();
        return item;
    }

    private static void appendItem(StringBuilder json, LibraryItem item) {
        json.append('{');
        member(json, "id", item.id).append(',');
        member(json, "name", item.name).append(',');
        member(json, "hash", item.hash).append(',');
        member(json, "type", item.type).append(',');
        member(json, "format", item.format).append(',');
        member(json, "template", item.template).append(',');
        member(json, "paper", item.paper).append(',');
        member(json, "pages", item.pages).append(',');
        member(json, "page", item.page).append(',');
        member(json, "size", item.size).append(',');
        member(json, "folder", item.folder).append(',');
        member(json, "folderId", item.folderId).append(',');
        quote(json, "tags").append(':').append('[');
        for (int index = 0; index < item.tags.size(); index++) {
            if (index > 0) json.append(',');
            quote(json, item.tags.get(index));
        }
        json.append(']').append(',');
        member(json, "favorite", item.favorite).append(',');
        member(json, "created", item.created).append(',');
        member(json, "modified", item.modified).append(',');
        member(json, "opened", item.opened).append(',');
        member(json, "trashedAt", item.trashedAt).append(',');
        member(json, "originalName", item.originalName).append(',');
        member(json, "attachment", item.attachment);
        json.append('}');
    }

    private static StringBuilder member(StringBuilder target, String name, String value) {
        quote(target, name).append(':');
        return quote(target, value == null ? "" : value);
    }

    private static StringBuilder member(StringBuilder target, String name, long value) {
        quote(target, name).append(':').append(value);
        return target;
    }

    private static StringBuilder member(StringBuilder target, String name, boolean value) {
        quote(target, name).append(':').append(value);
        return target;
    }

    private static StringBuilder quote(StringBuilder target, String value) {
        target.append('"');
        String source = value == null ? "" : value;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            switch (character) {
                case '"': target.append("\\\""); break;
                case '\\': target.append("\\\\"); break;
                case '\b': target.append("\\b"); break;
                case '\f': target.append("\\f"); break;
                case '\n': target.append("\\n"); break;
                case '\r': target.append("\\r"); break;
                case '\t': target.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        target.append("\\u");
                        String hex = Integer.toHexString(character);
                        for (int pad = hex.length(); pad < 4; pad++) target.append('0');
                        target.append(hex);
                    } else target.append(character);
            }
        }
        return target.append('"');
    }

    private static Node required(Node object, String key) {
        Node value = optional(object, key);
        if (value == null) throw new IllegalArgumentException("缺少 " + key);
        return value;
    }

    private static Node optional(Node object, String key) {
        require(object, Node.OBJECT, "不是对象");
        return object.object.get(key);
    }

    private static String requiredString(Node object, String key) {
        return asString(required(object, key), key + " 不是文字");
    }

    private static String optionalString(Node object, String key, String fallback) {
        Node value = optional(object, key);
        return value == null ? fallback : asString(value, key + " 不是文字");
    }

    private static long requiredLong(Node object, String key) {
        return asLong(required(object, key), key + " 不是整数");
    }

    private static long optionalLong(Node object, String key, long fallback) {
        Node value = optional(object, key);
        return value == null ? fallback : asLong(value, key + " 不是整数");
    }

    private static int requiredInt(Node object, String key) {
        long value = requiredLong(object, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IllegalArgumentException(key + " 超出范围");
        return (int) value;
    }

    private static int optionalInt(Node object, String key, int fallback) {
        Node value = optional(object, key);
        if (value == null) return fallback;
        long number = asLong(value, key + " 不是整数");
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw new IllegalArgumentException(key + " 超出范围");
        return (int) number;
    }

    private static boolean optionalBoolean(Node object, String key, boolean fallback) {
        Node value = optional(object, key);
        if (value == null) return fallback;
        if (value.kind != Node.BOOLEAN) throw new IllegalArgumentException(key + " 不是布尔值");
        return value.bool;
    }

    private static String asString(Node value, String problem) {
        if (value.kind != Node.STRING) throw new IllegalArgumentException(problem);
        return value.text;
    }

    private static long asLong(Node value, String problem) {
        if (value.kind != Node.NUMBER) throw new IllegalArgumentException(problem);
        try {
            return new BigDecimal(value.text).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(problem);
        }
    }

    private static Node require(Node value, int kind, String problem) {
        if (value == null || value.kind != kind) throw new IllegalArgumentException(problem);
        return value;
    }

    private static String message(RuntimeException problem) {
        String value = problem.getMessage();
        return value == null || value.trim().isEmpty() ? problem.getClass().getSimpleName() : value;
    }

    private static final class Node {
        static final int OBJECT = 1, ARRAY = 2, STRING = 3, NUMBER = 4, BOOLEAN = 5, NULL = 6;
        final int kind, start, end;
        LinkedHashMap<String, Node> object;
        ArrayList<Node> array;
        String text;
        boolean bool;

        Node(int kind, int start, int end) {
            this.kind = kind;
            this.start = start;
            this.end = end;
        }

        String raw(String source) {
            return source.substring(Math.max(0, start), Math.min(source.length(), end));
        }
    }

    /** Small strict parser so desktop migration tests do not depend on Android's org.json stubs. */
    private static final class Parser {
        private final String source;
        private int offset;

        Parser(String source) { this.source = source; }

        Node parse() {
            skip();
            Node result = value(0);
            skip();
            if (offset != source.length()) fail("索引末尾包含多余内容");
            return result;
        }

        private Node value(int depth) {
            if (depth > 64) fail("索引嵌套过深");
            skip();
            if (offset >= source.length()) fail("索引内容不完整");
            char next = source.charAt(offset);
            if (next == '{') return object(depth + 1);
            if (next == '[') return array(depth + 1);
            if (next == '"') return string();
            if (next == 't') return literal("true", Node.BOOLEAN, true);
            if (next == 'f') return literal("false", Node.BOOLEAN, false);
            if (next == 'n') return literal("null", Node.NULL, false);
            if (next == '-' || (next >= '0' && next <= '9')) return number();
            fail("索引包含无效字符");
            return null;
        }

        private Node object(int depth) {
            int start = offset++;
            LinkedHashMap<String, Node> values = new LinkedHashMap<>();
            skip();
            if (take('}')) return objectNode(start, values);
            while (true) {
                skip();
                if (offset >= source.length() || source.charAt(offset) != '"') fail("对象字段名无效");
                Node key = string();
                skip();
                expect(':');
                Node value = value(depth);
                if (values.put(key.text, value) != null) fail("对象包含重复字段 " + key.text);
                skip();
                if (take('}')) return objectNode(start, values);
                expect(',');
            }
        }

        private Node objectNode(int start, LinkedHashMap<String, Node> values) {
            Node result = new Node(Node.OBJECT, start, offset);
            result.object = values;
            return result;
        }

        private Node array(int depth) {
            int start = offset++;
            ArrayList<Node> values = new ArrayList<>();
            skip();
            if (take(']')) return arrayNode(start, values);
            while (true) {
                values.add(value(depth));
                skip();
                if (take(']')) return arrayNode(start, values);
                expect(',');
            }
        }

        private Node arrayNode(int start, ArrayList<Node> values) {
            Node result = new Node(Node.ARRAY, start, offset);
            result.array = values;
            return result;
        }

        private Node string() {
            int start = offset;
            expect('"');
            StringBuilder value = new StringBuilder();
            while (offset < source.length()) {
                char character = source.charAt(offset++);
                if (character == '"') {
                    Node result = new Node(Node.STRING, start, offset);
                    result.text = value.toString();
                    return result;
                }
                if (character == '\\') {
                    if (offset >= source.length()) fail("文字转义不完整");
                    char escaped = source.charAt(offset++);
                    switch (escaped) {
                        case '"': value.append('"'); break;
                        case '\\': value.append('\\'); break;
                        case '/': value.append('/'); break;
                        case 'b': value.append('\b'); break;
                        case 'f': value.append('\f'); break;
                        case 'n': value.append('\n'); break;
                        case 'r': value.append('\r'); break;
                        case 't': value.append('\t'); break;
                        case 'u': value.append(unicode()); break;
                        default: fail("文字包含无效转义");
                    }
                } else {
                    if (character < 0x20) fail("文字包含控制字符");
                    value.append(character);
                }
            }
            fail("文字没有结束引号");
            return null;
        }

        private char unicode() {
            if (offset + 4 > source.length()) fail("Unicode 转义不完整");
            int value = 0;
            for (int count = 0; count < 4; count++) {
                int digit = Character.digit(source.charAt(offset++), 16);
                if (digit < 0) fail("Unicode 转义无效");
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private Node number() {
            int start = offset;
            if (take('-') && offset >= source.length()) fail("数字不完整");
            if (take('0')) {
                if (offset < source.length() && Character.isDigit(source.charAt(offset))) fail("数字前导零无效");
            } else {
                digits("数字无效");
            }
            if (take('.')) digits("小数不完整");
            if (offset < source.length() && (source.charAt(offset) == 'e' || source.charAt(offset) == 'E')) {
                offset++;
                if (offset < source.length() && (source.charAt(offset) == '+' || source.charAt(offset) == '-')) offset++;
                digits("指数不完整");
            }
            Node result = new Node(Node.NUMBER, start, offset);
            result.text = source.substring(start, offset);
            return result;
        }

        private void digits(String problem) {
            int start = offset;
            while (offset < source.length() && Character.isDigit(source.charAt(offset))) offset++;
            if (offset == start) fail(problem);
        }

        private Node literal(String expected, int kind, boolean bool) {
            int start = offset;
            if (!source.regionMatches(offset, expected, 0, expected.length())) fail("无效的 JSON 字面量");
            offset += expected.length();
            Node result = new Node(kind, start, offset);
            result.bool = bool;
            return result;
        }

        private boolean take(char expected) {
            if (offset < source.length() && source.charAt(offset) == expected) {
                offset++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) fail("应为 " + expected);
        }

        private void skip() {
            while (offset < source.length()) {
                char character = source.charAt(offset);
                if (character == ' ' || character == '\n' || character == '\r' || character == '\t') offset++;
                else break;
            }
        }

        private void fail(String problem) {
            throw new IllegalArgumentException(problem + "（位置 " + offset + "）");
        }
    }
}
