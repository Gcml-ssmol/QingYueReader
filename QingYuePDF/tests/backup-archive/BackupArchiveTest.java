package cn.qingyue.pdf;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BackupArchiveTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("qingyue-backup-test-").toFile();
        try {
            roundTrip(root);
            exportValidation();
            extractionPathValidation(root);
            limits(root);
            integrityAndManifestValidation(root);
            stagingAndCancellation(root);
            System.out.println("BackupArchiveTest: " + checks + " checks passed; roundtrip, "
                    + "checksums, strict paths, duplicates, limits, cancellation and staging "
                    + "rollback. Desktop Java test only; SAF/device behavior is not claimed.");
        } finally {
            deleteTree(root);
        }
    }

    private static void roundTrip(File root) throws Exception {
        final byte[] pdf = "%PDF-1.4\nQingYue\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        final byte[] ink = new byte[]{0x51, 0x49, 0x4e, 0x4b, 0, 0, 0, 1};
        final byte[] note = new byte[]{0x51, 0x4e, 0x4f, 0x54, 0, 0, 0, 1, 9, 8, 7};
        final byte[] image = new byte[150_000];
        for (int i = 0; i < image.length; i++) image[i] = (byte) (i * 31);
        ArrayList<BackupArchive.EntrySource> entries = new ArrayList<>();
        entries.add(BackupArchive.EntrySource.fromBytes(
                "documents/11111111-1111-1111-1111-111111111111/content.pdf",
                "document", "11111111-1111-1111-1111-111111111111", pdf));
        entries.add(BackupArchive.EntrySource.fromBytes(
                "documents/11111111-1111-1111-1111-111111111111/annotations/0.ink",
                "annotation", "11111111-1111-1111-1111-111111111111", ink));
        entries.add(BackupArchive.EntrySource.fromBytes(
                "notes/22222222-2222-2222-2222-222222222222/document.qnote",
                "note", "22222222-2222-2222-2222-222222222222", note));
        entries.add(BackupArchive.EntrySource.fromBytes(
                "notes/22222222-2222-2222-2222-222222222222/images/photo.png",
                "note-image", "22222222-2222-2222-2222-222222222222", image));
        entries.add(BackupArchive.EntrySource.fromBytes(
                "learning/33333333-3333-3333-3333-333333333333/card.dat",
                "learning", "33333333-3333-3333-3333-333333333333",
                "复习卡".getBytes(StandardCharsets.UTF_8)));

        final int[] progressCalls = {0};
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        BackupArchive.Manifest written = BackupArchive.write(archive, entries,
                "{\"title\":\"晴阅整库备份\",\"flags\":[true,false],\"schema\":1}",
                BackupArchive.Limits.defaults(), new BackupArchive.Progress() {
                    @Override public void onProgress(String phase, String path, long done,
                                                     long total, int completed, int count) {
                        progressCalls[0]++;
                    }
                }, BackupArchive.NEVER_CANCEL);
        check(written.entries().size() == 5, "export manifest entry count");
        check(written.totalBytes == pdf.length + ink.length + note.length + image.length
                + "复习卡".getBytes(StandardCharsets.UTF_8).length, "export total bytes");
        check(written.metadataJson().contains("晴阅整库备份"), "Unicode metadata preserved");
        check("晴阅整库备份".equals(written.metadataString("title")),
                "typed metadata string access");
        check(progressCalls[0] > 5, "streaming export progress reported");

        File staging = new File(root, "roundtrip-stage");
        BackupArchive.ValidatedArchive restored = BackupArchive.extractValidated(
                new ByteArrayInputStream(archive.toByteArray()), staging);
        check(staging.isDirectory(), "validated staging committed");
        check(Arrays.equals(pdf, read(restored.file(entries.get(0).path()))), "PDF exact bytes");
        check(Arrays.equals(ink, read(restored.file(entries.get(1).path()))), "ink exact bytes");
        check(Arrays.equals(note, read(restored.file(entries.get(2).path()))), "note opaque bytes");
        check(Arrays.equals(image, read(restored.file(entries.get(3).path()))), "large image exact bytes");
        check(restored.manifest.entry(entries.get(4).path()).sha256.equals(sha256(
                "复习卡".getBytes(StandardCharsets.UTF_8))), "manifest SHA exposed");
        check(restored.manifest.metadataJson().contains("晴阅整库备份"),
                "restored metadata available");
    }

    private static void exportValidation() throws Exception {
        String[] invalid = {"../evil", "/absolute", "C:/windows", "\\\\server\\share",
                "a\\b", "a//b", "a/./b", "a/../b", "a/b:bad", "资料/file"};
        for (String path : invalid) {
            expectIOException("export rejects path " + path, new IOAction() {
                @Override public void run() throws Exception {
                    BackupArchive.write(new ByteArrayOutputStream(), Collections.singletonList(
                            BackupArchive.EntrySource.fromBytes(path, "blob", "owner",
                                    new byte[]{1})), "{}");
                }
            });
        }
        expectIOException("reserved manifest path", new IOAction() {
            @Override public void run() throws Exception {
                BackupArchive.write(new ByteArrayOutputStream(), Collections.singletonList(
                        BackupArchive.EntrySource.fromBytes("MANIFEST.JSON", "blob", "owner",
                                new byte[0])), "{}");
            }
        });
        expectIOException("case-fold source collision", new IOAction() {
            @Override public void run() throws Exception {
                BackupArchive.write(new ByteArrayOutputStream(), Arrays.asList(
                        BackupArchive.EntrySource.fromBytes("notes/ID/a.dat", "blob", "owner",
                                new byte[0]),
                        BackupArchive.EntrySource.fromBytes("notes/id/A.dat", "blob", "owner",
                                new byte[0])), "{}");
            }
        });
        expectIOException("non-object metadata", new IOAction() {
            @Override public void run() throws Exception {
                BackupArchive.write(new ByteArrayOutputStream(), Collections.<BackupArchive.EntrySource>emptyList(),
                        "[1,2]");
            }
        });
        expectIOException("duplicate JSON metadata key", new IOAction() {
            @Override public void run() throws Exception {
                BackupArchive.write(new ByteArrayOutputStream(), Collections.<BackupArchive.EntrySource>emptyList(),
                        "{\"a\":1,\"a\":2}");
            }
        });
        expectIOException("changed source size", new IOAction() {
            @Override public void run() throws Exception {
                BackupArchive.EntrySource changing = new BackupArchive.EntrySource(
                        "documents/id/content.pdf", "document", "id", 2,
                        new BackupArchive.StreamOpener() {
                            @Override public InputStream open() {
                                return new ByteArrayInputStream(new byte[]{1, 2, 3});
                            }
                        });
                BackupArchive.write(new ByteArrayOutputStream(), Collections.singletonList(changing), "{}");
            }
        });
    }

    private static void extractionPathValidation(File root) throws Exception {
        String[] invalid = {"../outside.dat", "/absolute.dat", "C:/outside.dat", "a\\b.dat",
                "a//b.dat", "a/./b.dat", "a/../b.dat"};
        for (int i = 0; i < invalid.length; i++) {
            final String path = invalid[i];
            final File stage = new File(root, "bad-path-stage-" + i);
            final File outside = new File(root, "outside.dat");
            expectIOException("extract rejects path " + path, new IOAction() {
                @Override public void run() throws Exception {
                    byte[] zip = rawZip(new String[]{path}, new byte[][]{new byte[]{1}});
                    BackupArchive.extractValidated(new ByteArrayInputStream(zip), stage);
                }
            });
            check(!stage.exists(), "bad path leaves no committed staging");
            check(!outside.exists(), "bad path cannot escape staging");
        }

        final File collisionStage = new File(root, "collision-stage");
        expectIOException("extract rejects case-fold collision", new IOAction() {
            @Override public void run() throws Exception {
                byte[] zip = rawZip(new String[]{"notes/ID/a.dat", "notes/id/A.dat"},
                        new byte[][]{new byte[]{1}, new byte[]{2}});
                BackupArchive.extractValidated(new ByteArrayInputStream(zip), collisionStage);
            }
        });
        check(!collisionStage.exists(), "collision leaves no staging");
    }

    private static void limits(File root) throws Exception {
        final BackupArchive.Limits small = new BackupArchive.Limits(1, 4, 5, 4096);
        expectIOException("export entry count limit", new IOAction() {
            @Override public void run() throws Exception {
                BackupArchive.write(new ByteArrayOutputStream(), Arrays.asList(
                        source("a.dat", new byte[]{1}), source("b.dat", new byte[]{2})), "{}",
                        small, null, null);
            }
        });
        expectIOException("export single entry limit", new IOAction() {
            @Override public void run() throws Exception {
                BackupArchive.write(new ByteArrayOutputStream(), Collections.singletonList(
                        source("large.dat", new byte[5])), "{}", small, null, null);
            }
        });
        final BackupArchive.Limits totalLimit = new BackupArchive.Limits(2, 4, 5, 4096);
        expectIOException("export total limit", new IOAction() {
            @Override public void run() throws Exception {
                BackupArchive.write(new ByteArrayOutputStream(), Arrays.asList(
                        source("a.dat", new byte[3]), source("b.dat", new byte[3])), "{}",
                        totalLimit, null, null);
            }
        });
        ByteArrayOutputStream exactArchive = new ByteArrayOutputStream();
        BackupArchive.write(exactArchive, Arrays.asList(source("a.dat", new byte[2]),
                source("b.dat", new byte[3])), "{}", totalLimit, null, null);
        File exactStage = new File(root, "exact-limit-stage");
        BackupArchive.ValidatedArchive exact = BackupArchive.extractValidated(
                new ByteArrayInputStream(exactArchive.toByteArray()), exactStage, totalLimit,
                null, null);
        check(exact.manifest.totalBytes == 5, "exact size/count limits accepted");

        ByteArrayOutputStream generousArchive = new ByteArrayOutputStream();
        BackupArchive.write(generousArchive, Collections.singletonList(
                source("five.dat", new byte[5])), "{}");
        final File strictStage = new File(root, "strict-extract-stage");
        expectIOException("extract single entry limit", new IOAction() {
            @Override public void run() throws Exception {
                BackupArchive.extractValidated(new ByteArrayInputStream(generousArchive.toByteArray()),
                        strictStage, small, null, null);
            }
        });
        check(!strictStage.exists(), "size failure rolls back partial staging");

        final BackupArchive.Limits tinyManifest = new BackupArchive.Limits(2, 100, 100, 128);
        expectIOException("manifest size limit", new IOAction() {
            @Override public void run() throws Exception {
                char[] chars = new char[500]; Arrays.fill(chars, 'x');
                BackupArchive.write(new ByteArrayOutputStream(), Collections.<BackupArchive.EntrySource>emptyList(),
                        "{\"long\":\"" + new String(chars) + "\"}", tinyManifest, null, null);
            }
        });
    }

    private static void integrityAndManifestValidation(File root) throws Exception {
        byte[] good = "good".getBytes(StandardCharsets.UTF_8);
        String goodManifest = manifest(1, 4,
                "{\"path\":\"documents/id/content.pdf\",\"kind\":\"document\","
                        + "\"ownerId\":\"id\",\"size\":4,\"sha256\":\"" + sha256(good) + "\"}");
        final File checksumStage = new File(root, "checksum-stage");
        expectIOException("SHA mismatch rejected", new IOAction() {
            @Override public void run() throws Exception {
                byte[] zip = rawZip(new String[]{"documents/id/content.pdf", "manifest.json"},
                        new byte[][]{"evil".getBytes(StandardCharsets.UTF_8),
                                goodManifest.getBytes(StandardCharsets.UTF_8)});
                BackupArchive.extractValidated(new ByteArrayInputStream(zip), checksumStage);
            }
        });
        check(!checksumStage.exists(), "checksum failure leaves no staging");

        final File unlistedStage = new File(root, "unlisted-stage");
        expectIOException("unlisted payload rejected", new IOAction() {
            @Override public void run() throws Exception {
                String empty = manifest(0, 0, "");
                byte[] zip = rawZip(new String[]{"extra.dat", "manifest.json"},
                        new byte[][]{new byte[]{1}, empty.getBytes(StandardCharsets.UTF_8)});
                BackupArchive.extractValidated(new ByteArrayInputStream(zip), unlistedStage);
            }
        });

        final File missingStage = new File(root, "missing-stage");
        expectIOException("missing payload rejected", new IOAction() {
            @Override public void run() throws Exception {
                byte[] zip = rawZip(new String[]{"manifest.json"},
                        new byte[][]{goodManifest.getBytes(StandardCharsets.UTF_8)});
                BackupArchive.extractValidated(new ByteArrayInputStream(zip), missingStage);
            }
        });

        final File futureStage = new File(root, "future-stage");
        expectIOException("future schema rejected", new IOAction() {
            @Override public void run() throws Exception {
                String future = manifest(0, 0, "").replace("\"version\":1", "\"version\":2");
                BackupArchive.extractValidated(new ByteArrayInputStream(rawZip(
                        new String[]{"manifest.json"},
                        new byte[][]{future.getBytes(StandardCharsets.UTF_8)})), futureStage);
            }
        });

        final File badCounterStage = new File(root, "bad-counter-stage");
        expectIOException("manifest long overflow rejected", new IOAction() {
            @Override public void run() throws Exception {
                String overflow = manifest(0, 0, "").replace("\"totalBytes\":0",
                        "\"totalBytes\":9223372036854775808");
                BackupArchive.extractValidated(new ByteArrayInputStream(rawZip(
                        new String[]{"manifest.json"},
                        new byte[][]{overflow.getBytes(StandardCharsets.UTF_8)})), badCounterStage);
            }
        });
    }

    private static void stagingAndCancellation(File root) throws Exception {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        BackupArchive.write(archive, Collections.singletonList(
                source("notes/id/images/image.png", new byte[200_000])), "{}");

        File existing = new File(root, "existing-stage");
        check(existing.mkdir(), "create existing stage fixture");
        File marker = new File(existing, "keep.txt");
        try (OutputStream output = new FileOutputStream(marker)) { output.write(7); }
        expectIOException("existing staging is never overwritten", new IOAction() {
            @Override public void run() throws Exception {
                BackupArchive.extractValidated(new ByteArrayInputStream(archive.toByteArray()),
                        existing);
            }
        });
        check(marker.isFile() && read(marker)[0] == 7, "existing staging preserved");

        final int[] polls = {0};
        File cancelledStage = new File(root, "cancelled-stage");
        expectCancelled("cooperative extraction cancellation", new IOAction() {
            @Override public void run() throws Exception {
                BackupArchive.extractValidated(new ByteArrayInputStream(archive.toByteArray()),
                        cancelledStage, BackupArchive.Limits.defaults(), null,
                        new BackupArchive.Cancellation() {
                            @Override public boolean isCancelled() { return ++polls[0] >= 3; }
                        });
            }
        });
        check(!cancelledStage.exists(), "cancelled extraction removes partial staging");

        final int[] exportPolls = {0};
        expectCancelled("cooperative export cancellation", new IOAction() {
            @Override public void run() throws Exception {
                BackupArchive.write(new ByteArrayOutputStream(), Collections.singletonList(
                        source("large.dat", new byte[200_000])), "{}",
                        BackupArchive.Limits.defaults(), null, new BackupArchive.Cancellation() {
                            @Override public boolean isCancelled() { return ++exportPolls[0] >= 3; }
                        });
            }
        });
    }

    private static BackupArchive.EntrySource source(String path, byte[] bytes) {
        return BackupArchive.EntrySource.fromBytes(path, "blob", "owner", bytes);
    }

    private static String manifest(int count, long total, String records) {
        return "{\"format\":\"qingyue-backup\",\"version\":1,\"createdAt\":1,"
                + "\"entryCount\":" + count + ",\"totalBytes\":" + total
                + ",\"metadata\":{},\"entries\":[" + records + "]}";
    }

    private static byte[] rawZip(String[] paths, byte[][] contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < paths.length; i++) {
                zip.putNextEntry(new ZipEntry(paths[i]));
                zip.write(contents[i]);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] read(File file) throws IOException {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static void expectIOException(String label, IOAction action) throws Exception {
        checks++;
        try { action.run(); throw new AssertionError(label + " was accepted"); }
        catch (BackupArchive.CancelledException wrong) {
            throw new AssertionError(label + " returned cancellation", wrong);
        } catch (IOException expected) { }
    }

    private static void expectCancelled(String label, IOAction action) throws Exception {
        checks++;
        try { action.run(); throw new AssertionError(label + " did not cancel"); }
        catch (BackupArchive.CancelledException expected) { }
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private interface IOAction { void run() throws Exception; }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}
