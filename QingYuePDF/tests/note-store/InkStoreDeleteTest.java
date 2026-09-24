package cn.qingyue.pdf;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.SortedSet;
import java.util.Arrays;
import java.util.TreeSet;

public final class InkStoreDeleteTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("qingyue-ink-delete-").toFile();
        try {
            String removed = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
            String kept = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
            String[] suffixes = {".ink", ".ink.bak", ".ink.new"};
            for (String suffix : suffixes) {
                check(new File(dir, removed + "-0" + suffix).createNewFile(), "create removed file");
                check(new File(dir, kept + "-0" + suffix).createNewFile(), "create kept file");
            }
            InkStore.pending.put(removed + "-0", Collections.emptyList());
            InkStore.pending.put(kept + "-0", Collections.emptyList());
            InkStore.pending.put(removed + "-2", Collections.emptyList());
            InkStore.pending.put(removed + "-99", Collections.emptyList());
            InkStore.pending.put(removed + "-bad", Collections.emptyList());
            InkStore.prepareClear(removed + "-2", Collections.singletonList(new InkPage.Stroke(0xff000000, .01f, new float[]{0f, 0f, 1f, 1f})));
            check(InkStore.hasClearRecovery(dir, removed + "-2"), "queued clear recovery");
            check(new File(dir, removed + "-1.ink").createNewFile(), "create disk ink");
            check(new File(dir, removed + "-3.ink.bak").createNewFile(), "create backup ink");
            check(new File(dir, kept + "-4.ink").createNewFile(), "create other document ink");
            SortedSet<Integer> annotated = InkStore.annotatedPages(dir, removed, 5);
            check(annotated.equals(new TreeSet<>(Arrays.asList(0, 1, 2, 3))), "pending and disk annotations: " + annotated);
            InkStore.deleteDocument(dir, removed);
            check(InkStore.annotatedPages(dir, removed, 5).isEmpty(), "deleted annotations remained");
            check(!InkStore.hasClearRecovery(dir, removed + "-2"), "queued clear recovery remained");
            check(InkStore.annotatedPages(dir, kept, 5).equals(new TreeSet<>(Arrays.asList(0, 4))), "other document annotations changed");
            for (String suffix : suffixes) {
                check(!new File(dir, removed + "-0" + suffix).exists(), "removed suffix " + suffix);
                check(new File(dir, kept + "-0" + suffix).exists(), "kept suffix " + suffix);
            }
            check(!InkStore.pending.containsKey(removed + "-0"), "removed pending ink");
            check(InkStore.pending.containsKey(kept + "-0"), "kept pending ink");
            InkStore.deleteDocument(dir, kept);
            for (String suffix : suffixes) check(!new File(dir, kept + "-0" + suffix).exists(), "last suffix " + suffix);
            check(InkStore.pending.isEmpty(), "last pending ink");
            InkStore.deleteDocument(dir, kept);
            System.out.println("InkStoreDeleteTest passed: pending and disk annotations, deletion, retention and repeated removal.");
        } finally {
            InkStore.pending.clear();
            File[] files = dir.listFiles();
            if (files != null) for (File file : files) file.delete();
            dir.delete();
        }
    }
}
