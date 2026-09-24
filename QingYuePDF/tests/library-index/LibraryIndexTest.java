package cn.qingyue.pdf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Desktop regression checks for migration, recovery policy, filtering and stable ordering. */
public final class LibraryIndexTest {
    private static final String A = "11111111-1111-4111-8111-111111111111";
    private static final String B = "22222222-2222-4222-8222-222222222222";
    private static final String C = "33333333-3333-4333-8333-333333333333";
    private static final String D = "44444444-4444-4444-8444-444444444444";

    public static void main(String[] arguments) {
        migratesPastDamagedRecord();
        refusesTopLevelOverwrite();
        roundTripsVersionTwoMetadata();
        filtersAndSortsDeterministically();
        nestedFoldersAndLiteralLegacyNames();
        System.out.println("PASS: legacy item isolation, top-level save guard, v2 metadata, filters and stable sorting");
    }

    private static void nestedFoldersAndLiteralLegacyNames() {
        LibraryItem old = item(A, "旧资料", 1, 1, 1);
        old.folder = "课程/高数";
        LibraryIndex.Result migrated = LibraryIndex.decodeV2(LibraryIndex.encodeV2(Arrays.asList(old), 1));
        equal(1, migrated.folders.size(), "legacy slash remains one folder");
        equal("课程/高数", migrated.folders.get(0).name, "slash is literal");
        equal("", migrated.folders.get(0).parentId, "legacy folder remains root");
        String stableId = migrated.folders.get(0).id;
        equal(stableId, LibraryIndex.decodeV2(LibraryIndex.encodeV2(Arrays.asList(old), 2)).folders.get(0).id,
                "legacy ID is deterministic");

        LibraryFolder child = new LibraryFolder(B, stableId, "期末");
        LibraryFolder empty = new LibraryFolder(C, "", "空文件夹");
        LibraryItem moved = old.copy();moved.folderId = child.id;moved.folder = child.name;
        LibraryIndex.Result restored = LibraryIndex.decodeV2(LibraryIndex.encodeV2(
                Arrays.asList(moved), Arrays.asList(migrated.folders.get(0), child, empty), 3));
        yes(restored.allowSave, "nested folder index remains writable");
        equal(3, restored.folders.size(), "empty folder survives round trip");
        equal(child.id, restored.items.get(0).folderId, "document retains nested folder ID");
        equal(0, LibraryQuery.apply(restored.items, new LibraryQuery.Filter().rootOnly(true),
                LibraryQuery.Sort.NAME).size(), "root excludes filed document");
        equal(1, LibraryQuery.apply(restored.items, new LibraryQuery.Filter().folderId(child.id),
                LibraryQuery.Sort.NAME).size(), "folder shows direct document");
    }

    private static void migratesPastDamagedRecord() {
        String broken = "{\"id\":\"bad\"}";
        String legacy = "["
                + legacy(A, "第一份", 12, 3, 50) + ","
                + broken + ","
                + legacy(B, "后续仍保留", 4, 0, 70)
                + "]";
        LibraryIndex.Result result = LibraryIndex.migrateLegacy(legacy, 1234);
        yes(result.allowSave, "valid legacy array remains saveable after quarantine");
        equal(2, result.items.size(), "valid legacy item count");
        equal(A, result.items.get(0).id, "first item");
        equal(B, result.items.get(1).id, "item after damaged record");
        equal(1, result.damaged.size(), "damaged record count");
        equal(1, result.damaged.get(0).index, "damaged record index");
        equal(broken, result.damaged.get(0).raw, "damaged record preserved exactly");
        equal(1234L, result.items.get(0).created, "migration creation fallback");
        equal("第一份", result.items.get(0).originalName, "original name migration");
        yes(LibraryIndex.encodeRecovery(result, 9).contains("\\\"id\\\":\\\"bad\\\""), "recovery manifest embeds raw item");
    }

    private static void refusesTopLevelOverwrite() {
        String corrupt = "[{\"id\":";
        LibraryIndex.Result result = LibraryIndex.migrateLegacy(corrupt, 1);
        no(result.allowSave, "corrupt top level must disable save");
        equal(0, result.items.size(), "no partial top-level recovery is presented as complete");
        equal(corrupt, result.topLevelRaw, "top-level original preserved");
        equal(corrupt, result.damaged.get(0).raw, "damage contains top-level original");

        LibraryIndex.Result wrongSchema = LibraryIndex.decodeV2("{\"schema\":3,\"items\":[]}");
        no(wrongSchema.allowSave, "unknown v2 schema must disable save");
    }

    private static void roundTripsVersionTwoMetadata() {
        LibraryItem item = item(C, "讲义", 80, 40, 20);
        item.folder = "课程";
        item.tags.add("高数");
        item.tags.add("期末");
        item.favorite = true;
        item.trashedAt = 7;
        item.originalName = "原始讲义.docx";
        item.attachment = C + "/original.docx";
        String encoded = LibraryIndex.encodeV2(Arrays.asList(item), 100);
        LibraryIndex.Result decoded = LibraryIndex.decodeV2(encoded);
        yes(decoded.allowSave, "self-written v2 index loads");
        equal(0, decoded.damaged.size(), "self-written v2 has no damage");
        LibraryItem restored = decoded.items.get(0);
        equal("课程", restored.folder, "folder round trip");
        equal(Arrays.asList("高数", "期末"), restored.tags, "tags round trip");
        yes(restored.favorite, "favorite round trip");
        equal(7L, restored.trashedAt, "trash timestamp round trip");
        equal("原始讲义.docx", restored.originalName, "original name round trip");
        equal(C + "/original.docx", restored.attachment, "attachment round trip");
    }

    private static void filtersAndSortsDeterministically() {
        LibraryItem a = item(A, "同名", 10, 50, 20);
        a.folder = "数学";
        a.tags.add("重点");
        a.favorite = true;
        LibraryItem b = item(B, "同名", 10, 60, 30);
        b.folder = "数学";
        b.tags.add("复习");
        LibraryItem c = item(C, "英语阅读", 20, 10, 40);
        c.folder = "";
        c.tags.add("重点");
        c.originalName = "reading.pdf";
        LibraryItem trashed = item(D, "已删除重点", 100, 100, 100);
        trashed.favorite = true;
        trashed.trashedAt = 99;

        List<LibraryItem> first = Arrays.asList(c, b, trashed, a);
        List<LibraryItem> second = Arrays.asList(a, trashed, c, b);
        equal(ids(LibraryQuery.apply(first, new LibraryQuery.Filter(), LibraryQuery.Sort.MODIFIED)),
                ids(LibraryQuery.apply(second, new LibraryQuery.Filter(), LibraryQuery.Sort.MODIFIED)),
                "modified order independent of input order");
        equal(Arrays.asList(C, A, B), ids(LibraryQuery.apply(first, new LibraryQuery.Filter(), LibraryQuery.Sort.MODIFIED)),
                "modified order and id tie break");
        equal(Arrays.asList(A, B, C), ids(LibraryQuery.apply(first, new LibraryQuery.Filter(), LibraryQuery.Sort.NAME)),
                "name order has deterministic id tie break");
        equal(Arrays.asList(B, A, C), ids(LibraryQuery.apply(first, new LibraryQuery.Filter(), LibraryQuery.Sort.OPENED)),
                "opened descending");
        equal(Arrays.asList(C, B, A), ids(LibraryQuery.apply(first, new LibraryQuery.Filter(), LibraryQuery.Sort.CREATED)),
                "created descending");

        equal(Arrays.asList(A), ids(LibraryQuery.apply(first,
                new LibraryQuery.Filter().folder("数学").tag("重点").favoriteOnly(true), LibraryQuery.Sort.MODIFIED)),
                "combined folder, tag and favorite filter");
        equal(Arrays.asList(C), ids(LibraryQuery.apply(first,
                new LibraryQuery.Filter().unfiledOnly(true), LibraryQuery.Sort.MODIFIED)), "unfiled items can be selected explicitly");
        equal(Arrays.asList(C, A), ids(LibraryQuery.apply(first,
                new LibraryQuery.Filter().keyword("重点"), LibraryQuery.Sort.MODIFIED)), "keyword searches tags");
        equal(Arrays.asList(C), ids(LibraryQuery.apply(first,
                new LibraryQuery.Filter().keyword("READING"), LibraryQuery.Sort.MODIFIED)), "keyword searches original name case-insensitively");
        equal(Arrays.asList(D, C, A, B), ids(LibraryQuery.apply(first,
                new LibraryQuery.Filter().includeTrashed(true), LibraryQuery.Sort.MODIFIED)), "trash inclusion is explicit");
    }

    private static String legacy(String id, String name, int pages, int page, long opened) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"pages\":" + pages
                + ",\"page\":" + page + ",\"opened\":" + opened + "}";
    }

    private static LibraryItem item(String id, String name, long modified, long opened, long created) {
        LibraryItem item = new LibraryItem();
        item.id = id;
        item.name = name;
        item.originalName = name;
        item.pages = 1;
        item.modified = modified;
        item.opened = opened;
        item.created = created;
        return item;
    }

    private static List<String> ids(List<LibraryItem> items) {
        ArrayList<String> result = new ArrayList<>();
        for (LibraryItem item : items) result.add(item.id);
        return result;
    }

    private static void yes(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void no(boolean value, String message) {
        if (value) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": " + actual + " != " + expected);
        }
    }
}
