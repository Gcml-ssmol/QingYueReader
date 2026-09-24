package cn.qingyue.pdf;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Streaming, platform-independent QingYue backup container.
 *
 * <p>The container is a ZIP file whose only reserved entry is {@code manifest.json}. Payload
 * entries are written first and the manifest is written last, so large source files are read only
 * once. Every payload entry is described by an exact byte length and SHA-256 digest. Restores are
 * extracted into a private sibling directory and that directory is renamed to the requested
 * staging path only after the complete archive has passed validation. This class never writes to
 * the live library.</p>
 *
 * <p>Paths are deliberately stricter than ordinary ZIP paths: relative ASCII segments only,
 * forward slashes, no empty/dot segments, no drive letters, and no case-folding collisions. User
 * visible Unicode names belong in {@link #metadataJson()}, not in entry paths.</p>
 */
public final class BackupArchive {
    public static final String FORMAT = "qingyue-backup";
    public static final int FORMAT_VERSION = 1;
    public static final String MANIFEST_PATH = "manifest.json";

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_PATH_CHARS = 512;
    private static final int MAX_SEGMENT_CHARS = 128;
    private static final int MAX_KIND_CHARS = 64;
    private static final int MAX_OWNER_CHARS = 128;
    private static final int MAX_JSON_DEPTH = 64;

    private BackupArchive() { }

    /** Hard resource limits. All byte counters are uncompressed byte counts. */
    public static final class Limits {
        public final int maxEntries;
        public final long maxEntryBytes;
        public final long maxTotalBytes;
        public final int maxManifestBytes;

        public Limits(int maxEntries, long maxEntryBytes, long maxTotalBytes,
                      int maxManifestBytes) {
            if (maxEntries < 1 || maxEntryBytes < 0 || maxTotalBytes < 0
                    || maxManifestBytes < 128) {
                throw new IllegalArgumentException("Invalid backup limits");
            }
            this.maxEntries = maxEntries;
            this.maxEntryBytes = maxEntryBytes;
            this.maxTotalBytes = maxTotalBytes;
            this.maxManifestBytes = maxManifestBytes;
        }

        public static Limits defaults() {
            return new Limits(50_000, 200L * 1024 * 1024, 8L * 1024 * 1024 * 1024,
                    8 * 1024 * 1024);
        }
    }

    /** Opens a fresh stream for one export entry. */
    public interface StreamOpener {
        InputStream open() throws IOException;
    }

    /** Cooperative cancellation; checked before each entry and after each 64 KiB chunk. */
    public interface Cancellation {
        boolean isCancelled();
    }

    /** Progress callback. {@code totalBytes} is -1 while export size is still being discovered. */
    public interface Progress {
        void onProgress(String phase, String path, long completedBytes, long totalBytes,
                        int completedEntries, int totalEntries);
    }

    public static final Cancellation NEVER_CANCEL = new Cancellation() {
        @Override public boolean isCancelled() { return false; }
    };
    public static final Progress NO_PROGRESS = new Progress() {
        @Override public void onProgress(String phase, String path, long completedBytes,
                                         long totalBytes, int completedEntries,
                                         int totalEntries) { }
    };

    public static final class CancelledException extends IOException {
        public CancelledException() { super("Backup operation cancelled"); }
    }

    /** One logical source file for export. */
    public static final class EntrySource {
        private final String path, kind, ownerId;
        private final long expectedSize;
        private final StreamOpener opener;

        public EntrySource(String path, String kind, String ownerId, long expectedSize,
                           StreamOpener opener) {
            if (opener == null) throw new NullPointerException("opener");
            this.path = path;
            this.kind = kind;
            this.ownerId = ownerId == null ? "" : ownerId;
            this.expectedSize = expectedSize;
            this.opener = opener;
        }

        public static EntrySource fromFile(String path, String kind, String ownerId,
                                           final File file) {
            if (file == null) throw new NullPointerException("file");
            final long size = file.length();
            return new EntrySource(path, kind, ownerId, size, new StreamOpener() {
                @Override public InputStream open() throws IOException {
                    if (!file.isFile()) throw new FileNotFoundException(file.toString());
                    return new BufferedInputStream(new FileInputStream(file));
                }
            });
        }

        public static EntrySource fromBytes(String path, String kind, String ownerId,
                                            final byte[] bytes) {
            if (bytes == null) throw new NullPointerException("bytes");
            return new EntrySource(path, kind, ownerId, bytes.length, new StreamOpener() {
                @Override public InputStream open() { return new ByteArrayInputStream(bytes); }
            });
        }

        public String path() { return path; }
        public String kind() { return kind; }
        public String ownerId() { return ownerId; }
        public long expectedSize() { return expectedSize; }
    }

    /** Immutable manifest record for one payload entry. */
    public static final class EntryRecord {
        public final String path, kind, ownerId, sha256;
        public final long size;

        private EntryRecord(String path, String kind, String ownerId, long size,
                            String sha256) {
            this.path = path;
            this.kind = kind;
            this.ownerId = ownerId;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    /** Immutable validated archive manifest. */
    public static final class Manifest {
        public final long createdAt, totalBytes;
        private final String metadataJson;
        private final List<EntryRecord> entries;
        private final Map<String, EntryRecord> byPath;

        private Manifest(long createdAt, long totalBytes, String metadataJson,
                         List<EntryRecord> entries) {
            this.createdAt = createdAt;
            this.totalBytes = totalBytes;
            this.metadataJson = metadataJson;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            LinkedHashMap<String, EntryRecord> map = new LinkedHashMap<>();
            for (EntryRecord entry : entries) map.put(entry.path, entry);
            this.byPath = Collections.unmodifiableMap(map);
        }

        public String metadataJson() { return metadataJson; }
        /** Returns one string-valued metadata field without exposing the internal JSON codec. */
        @SuppressWarnings("unchecked")
        public String metadataString(String key) throws IOException {
            Object decoded = Json.parse(metadataJson);
            Object value = ((Map<String, Object>) decoded).get(key);
            if (!(value instanceof String)) {
                throw new IOException("Missing or invalid backup metadata field: " + key);
            }
            return (String) value;
        }
        public List<EntryRecord> entries() { return entries; }
        public EntryRecord entry(String path) { return byPath.get(path); }
    }

    /** A fully validated staging tree. Callers may now merge it transactionally. */
    public static final class ValidatedArchive {
        public final File directory;
        public final Manifest manifest;

        private ValidatedArchive(File directory, Manifest manifest) {
            this.directory = directory;
            this.manifest = manifest;
        }

        public File file(String path) throws IOException {
            if (manifest.entry(path) == null) throw new FileNotFoundException(path);
            return containedFile(directory, path);
        }
    }

    /**
     * Writes one complete backup. The caller owns {@code destination}; it is flushed but not
     * closed. For user-visible SAF exports, write to a private temporary file first, verify it,
     * then copy that completed file to the provider URI.
     */
    public static Manifest write(OutputStream destination, List<EntrySource> sources,
                                 String metadataJson, Limits limits, Progress progress,
                                 Cancellation cancellation) throws IOException {
        if (destination == null) throw new NullPointerException("destination");
        if (sources == null) throw new NullPointerException("sources");
        limits = limits == null ? Limits.defaults() : limits;
        progress = progress == null ? NO_PROGRESS : progress;
        cancellation = cancellation == null ? NEVER_CANCEL : cancellation;
        if (sources.size() > limits.maxEntries) throw new IOException("Too many backup entries");

        Object metadata = parseMetadata(metadataJson == null ? "{}" : metadataJson);
        validateSources(sources, limits);
        List<EntrySource> ordered = new ArrayList<>(sources);
        Collections.sort(ordered, new Comparator<EntrySource>() {
            @Override public int compare(EntrySource a, EntrySource b) {
                return a.path.compareTo(b.path);
            }
        });

        List<EntryRecord> records = new ArrayList<>();
        long total = 0;
        int completed = 0;
        ZipOutputStream zip = new ZipOutputStream(new NonClosingOutputStream(destination));
        boolean finished = false;
        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            for (EntrySource source : ordered) {
                checkCancelled(cancellation);
                ZipEntry zipEntry = new ZipEntry(source.path);
                zipEntry.setTime(0L);
                zip.putNextEntry(zipEntry);
                MessageDigest digest = sha256();
                long entryBytes = 0;
                try (InputStream input = source.opener.open()) {
                    if (input == null) throw new IOException("Source returned null: " + source.path);
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkCancelled(cancellation);
                        entryBytes = checkedAdd(entryBytes, count, limits.maxEntryBytes,
                                "Backup entry too large: " + source.path);
                        total = checkedAdd(total, count, limits.maxTotalBytes,
                                "Backup total size limit exceeded");
                        zip.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                        progress.onProgress("export", source.path, total, -1, completed,
                                ordered.size());
                    }
                }
                zip.closeEntry();
                if (source.expectedSize >= 0 && source.expectedSize != entryBytes) {
                    throw new IOException("Source changed while exporting: " + source.path);
                }
                records.add(new EntryRecord(source.path, source.kind, source.ownerId,
                        entryBytes, hex(digest.digest())));
                completed++;
                progress.onProgress("export", source.path, total, -1, completed,
                        ordered.size());
            }

            long createdAt = System.currentTimeMillis();
            byte[] manifestBytes = encodeManifest(createdAt, total, metadata, records);
            if (manifestBytes.length > limits.maxManifestBytes) {
                throw new IOException("Backup manifest too large");
            }
            checkCancelled(cancellation);
            ZipEntry manifestEntry = new ZipEntry(MANIFEST_PATH);
            manifestEntry.setTime(0L);
            zip.putNextEntry(manifestEntry);
            zip.write(manifestBytes);
            zip.closeEntry();
            zip.finish();
            zip.flush();
            finished = true;
            String normalizedMetadata = Json.write(metadata);
            progress.onProgress("complete", MANIFEST_PATH, total, total, completed,
                    ordered.size());
            return new Manifest(createdAt, total, normalizedMetadata, records);
        } finally {
            try {
                zip.close();
            } catch (IOException closeFailure) {
                if (finished) throw closeFailure;
            }
        }
    }

    /** Convenience overload using default limits and no callbacks. */
    public static Manifest write(OutputStream destination, List<EntrySource> sources,
                                 String metadataJson) throws IOException {
        return write(destination, sources, metadataJson, Limits.defaults(), NO_PROGRESS,
                NEVER_CANCEL);
    }

    /**
     * Extracts and validates an archive without touching live data. {@code stagingDirectory} must
     * not already exist. A hidden sibling is used during validation and removed on every failure.
     * The input is consumed but not closed.
     */
    public static ValidatedArchive extractValidated(InputStream archive, File stagingDirectory,
                                                    Limits limits, Progress progress,
                                                    Cancellation cancellation) throws IOException {
        if (archive == null) throw new NullPointerException("archive");
        if (stagingDirectory == null) throw new NullPointerException("stagingDirectory");
        limits = limits == null ? Limits.defaults() : limits;
        progress = progress == null ? NO_PROGRESS : progress;
        cancellation = cancellation == null ? NEVER_CANCEL : cancellation;
        if (stagingDirectory.exists()) {
            throw new IOException("Staging directory already exists");
        }
        File parent = stagingDirectory.getAbsoluteFile().getParentFile();
        if (parent == null) throw new IOException("Staging directory has no parent");
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create staging parent");
        }
        File partial;
        do {
            partial = new File(parent, "." + stagingDirectory.getName() + ".partial-"
                    + UUID.randomUUID().toString());
        } while (partial.exists());
        if (!partial.mkdir()) throw new IOException("Cannot create private staging directory");

        boolean committed = false;
        try {
            Extraction extraction = extractInto(archive, partial, limits, progress, cancellation);
            checkCancelled(cancellation);
            if (stagingDirectory.exists() || !partial.renameTo(stagingDirectory)) {
                throw new IOException("Cannot commit validated staging directory");
            }
            committed = true;
            progress.onProgress("complete", MANIFEST_PATH, extraction.manifest.totalBytes,
                    extraction.manifest.totalBytes, extraction.manifest.entries().size(),
                    extraction.manifest.entries().size());
            return new ValidatedArchive(stagingDirectory, extraction.manifest);
        } finally {
            if (!committed) deleteTree(partial);
        }
    }

    /** Convenience overload using default limits and no callbacks. */
    public static ValidatedArchive extractValidated(InputStream archive, File stagingDirectory)
            throws IOException {
        return extractValidated(archive, stagingDirectory, Limits.defaults(), NO_PROGRESS,
                NEVER_CANCEL);
    }

    private static final class ActualEntry {
        final String path, sha256;
        final long size;
        ActualEntry(String path, long size, String sha256) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private static final class Extraction {
        final Manifest manifest;
        Extraction(Manifest manifest) { this.manifest = manifest; }
    }

    private static Extraction extractInto(InputStream archive, File root, Limits limits,
                                          Progress progress, Cancellation cancellation)
            throws IOException {
        LinkedHashMap<String, ActualEntry> actual = new LinkedHashMap<>();
        HashSet<String> foldedPaths = new HashSet<>();
        byte[] manifestBytes = null;
        long total = 0;
        int payloadCount = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        ZipInputStream zip = new ZipInputStream(new NonClosingInputStream(archive));
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                checkCancelled(cancellation);
                String path = validatePath(entry.getName());
                String folded = foldPath(path);
                if (!foldedPaths.add(folded)) {
                    throw new IOException("Duplicate or ambiguous ZIP entry: " + path);
                }
                if (entry.isDirectory()) throw new IOException("Directory ZIP entries are not allowed");
                if (MANIFEST_PATH.equals(path)) {
                    manifestBytes = readManifest(zip, limits.maxManifestBytes, cancellation);
                } else {
                    if (++payloadCount > limits.maxEntries) {
                        throw new IOException("Too many backup entries");
                    }
                    File target = containedFile(root, path);
                    File targetParent = target.getParentFile();
                    if (!targetParent.isDirectory() && !targetParent.mkdirs()) {
                        throw new IOException("Cannot create staging path: " + path);
                    }
                    if (!target.createNewFile()) {
                        throw new IOException("Refusing to overwrite staging path: " + path);
                    }
                    MessageDigest digest = sha256();
                    long entryBytes = 0;
                    boolean complete = false;
                    try (FileOutputStream output = new FileOutputStream(target)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            checkCancelled(cancellation);
                            entryBytes = checkedAdd(entryBytes, count, limits.maxEntryBytes,
                                    "Backup entry too large: " + path);
                            total = checkedAdd(total, count, limits.maxTotalBytes,
                                    "Backup total size limit exceeded");
                            output.write(buffer, 0, count);
                            digest.update(buffer, 0, count);
                            progress.onProgress("extract", path, total, -1,
                                    payloadCount - 1, -1);
                        }
                        output.getFD().sync();
                        complete = true;
                    } finally {
                        if (!complete) target.delete();
                    }
                    actual.put(path, new ActualEntry(path, entryBytes, hex(digest.digest())));
                    progress.onProgress("extract", path, total, -1, payloadCount, -1);
                }
                zip.closeEntry();
            }
        } finally {
            zip.close();
        }
        if (manifestBytes == null) throw new IOException("Backup manifest is missing");
        Manifest manifest = decodeManifest(manifestBytes, limits);
        verifyManifest(manifest, actual, total);
        return new Extraction(manifest);
    }

    private static void validateSources(List<EntrySource> sources, Limits limits)
            throws IOException {
        HashSet<String> folded = new HashSet<>();
        long expectedTotal = 0;
        for (EntrySource source : sources) {
            if (source == null) throw new IOException("Null backup source");
            String path = validatePathForSource(source.path);
            validateToken(source.kind, "kind", MAX_KIND_CHARS, false);
            validateToken(source.ownerId, "ownerId", MAX_OWNER_CHARS, true);
            if (!folded.add(foldPath(path))) {
                throw new IOException("Duplicate or ambiguous source path: " + path);
            }
            if (source.expectedSize < -1) throw new IOException("Invalid expected size");
            if (source.expectedSize >= 0) {
                if (source.expectedSize > limits.maxEntryBytes) {
                    throw new IOException("Backup entry too large: " + source.path);
                }
                expectedTotal = checkedAdd(expectedTotal, source.expectedSize,
                        limits.maxTotalBytes, "Backup total size limit exceeded");
            }
        }
    }

    private static String validatePathForSource(String path) throws IOException {
        path = validatePath(path);
        if (MANIFEST_PATH.equalsIgnoreCase(path)) {
            throw new IOException("Payload cannot use reserved manifest path");
        }
        return path;
    }

    private static String validatePath(String path) throws IOException {
        if (path == null || path.isEmpty() || path.length() > MAX_PATH_CHARS) {
            throw new IOException("Invalid ZIP entry path");
        }
        if (path.charAt(0) == '/' || path.indexOf('\\') >= 0
                || path.length() >= 2 && isAsciiLetter(path.charAt(0)) && path.charAt(1) == ':') {
            throw new IOException("Absolute or platform-specific ZIP path: " + path);
        }
        String normalized = Normalizer.normalize(path, Normalizer.Form.NFC);
        if (!normalized.equals(path)) throw new IOException("Non-normalized ZIP path: " + path);
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || segment.length() > MAX_SEGMENT_CHARS) {
                throw new IOException("Unsafe ZIP path segment: " + path);
            }
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (!(isAsciiLetter(c) || c >= '0' && c <= '9' || c == '.' || c == '_'
                        || c == '-')) {
                    throw new IOException("Unsupported ZIP path character: " + path);
                }
            }
        }
        return path;
    }

    private static boolean isAsciiLetter(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z';
    }

    private static String foldPath(String path) { return path.toLowerCase(Locale.ROOT); }

    private static void validateToken(String token, String label, int max, boolean allowEmpty)
            throws IOException {
        if (token == null || token.length() > max || !allowEmpty && token.isEmpty()) {
            throw new IOException("Invalid " + label);
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!(isAsciiLetter(c) || c >= '0' && c <= '9' || c == '.' || c == '_'
                    || c == '-')) throw new IOException("Invalid " + label);
        }
    }

    private static File containedFile(File root, String path) throws IOException {
        File rootCanonical = root.getCanonicalFile();
        File target = new File(rootCanonical, path.replace('/', File.separatorChar))
                .getCanonicalFile();
        String prefix = rootCanonical.getPath() + File.separator;
        if (!target.getPath().startsWith(prefix)) {
            throw new IOException("ZIP entry escapes staging directory: " + path);
        }
        return target;
    }

    private static byte[] readManifest(InputStream input, int limit, Cancellation cancellation)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 16 * 1024));
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            checkCancelled(cancellation);
            if (output.size() > limit - count) throw new IOException("Backup manifest too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static Object parseMetadata(String json) throws IOException {
        Object value = Json.parse(json);
        if (!(value instanceof Map)) throw new IOException("Backup metadata must be a JSON object");
        return value;
    }

    private static byte[] encodeManifest(long createdAt, long total, Object metadata,
                                         List<EntryRecord> records) throws IOException {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("version", (long) FORMAT_VERSION);
        root.put("createdAt", createdAt);
        root.put("entryCount", (long) records.size());
        root.put("totalBytes", total);
        root.put("metadata", metadata);
        ArrayList<Object> entries = new ArrayList<>();
        for (EntryRecord record : records) {
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("path", record.path);
            item.put("kind", record.kind);
            item.put("ownerId", record.ownerId);
            item.put("size", record.size);
            item.put("sha256", record.sha256);
            entries.add(item);
        }
        root.put("entries", entries);
        return Json.write(root).getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Manifest decodeManifest(byte[] bytes, Limits limits) throws IOException {
        String text = decodeUtf8(bytes);
        Object decoded = Json.parse(text);
        if (!(decoded instanceof Map)) throw new IOException("Backup manifest must be an object");
        Map<String, Object> root = (Map<String, Object>) decoded;
        if (!FORMAT.equals(string(root, "format"))) throw new IOException("Unknown backup format");
        if (number(root, "version") != FORMAT_VERSION) {
            throw new IOException("Unsupported backup version");
        }
        long createdAt = number(root, "createdAt");
        long entryCount = number(root, "entryCount");
        long totalBytes = number(root, "totalBytes");
        if (createdAt < 0 || entryCount < 0 || entryCount > limits.maxEntries
                || totalBytes < 0 || totalBytes > limits.maxTotalBytes) {
            throw new IOException("Invalid backup manifest counters");
        }
        Object metadata = root.get("metadata");
        if (!(metadata instanceof Map)) throw new IOException("Invalid backup metadata");
        Object entriesValue = root.get("entries");
        if (!(entriesValue instanceof List)) throw new IOException("Invalid backup entry list");
        List<Object> encodedEntries = (List<Object>) entriesValue;
        if (encodedEntries.size() != entryCount) throw new IOException("Entry count mismatch");
        ArrayList<EntryRecord> entries = new ArrayList<>();
        HashSet<String> folded = new HashSet<>();
        long computedTotal = 0;
        for (Object value : encodedEntries) {
            if (!(value instanceof Map)) throw new IOException("Invalid backup entry record");
            Map<String, Object> item = (Map<String, Object>) value;
            String path = validatePathForSource(string(item, "path"));
            String kind = string(item, "kind");
            String ownerId = string(item, "ownerId");
            validateToken(kind, "kind", MAX_KIND_CHARS, false);
            validateToken(ownerId, "ownerId", MAX_OWNER_CHARS, true);
            if (!folded.add(foldPath(path))) throw new IOException("Duplicate manifest path");
            long size = number(item, "size");
            if (size < 0 || size > limits.maxEntryBytes) {
                throw new IOException("Invalid entry size: " + path);
            }
            computedTotal = checkedAdd(computedTotal, size, limits.maxTotalBytes,
                    "Backup total size limit exceeded");
            String digest = string(item, "sha256");
            if (!digest.matches("[0-9a-f]{64}")) throw new IOException("Invalid SHA-256: " + path);
            entries.add(new EntryRecord(path, kind, ownerId, size, digest));
        }
        if (computedTotal != totalBytes) throw new IOException("Manifest total size mismatch");
        return new Manifest(createdAt, totalBytes, Json.write(metadata), entries);
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new IOException("Manifest is not valid UTF-8", failure);
        }
    }

    private static void verifyManifest(Manifest manifest,
                                       Map<String, ActualEntry> actual, long actualTotal)
            throws IOException {
        if (manifest.entries().size() != actual.size() || manifest.totalBytes != actualTotal) {
            throw new IOException("Backup payload count or size mismatch");
        }
        for (EntryRecord expected : manifest.entries()) {
            ActualEntry found = actual.get(expected.path);
            if (found == null) throw new IOException("Missing backup entry: " + expected.path);
            if (found.size != expected.size) throw new IOException("Size mismatch: " + expected.path);
            if (!constantTimeEquals(found.sha256, expected.sha256)) {
                throw new IOException("SHA-256 mismatch: " + expected.path);
            }
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int difference = 0;
        for (int i = 0; i < a.length(); i++) difference |= a.charAt(i) ^ b.charAt(i);
        return difference == 0;
    }

    private static String string(Map<String, Object> object, String key) throws IOException {
        Object value = object.get(key);
        if (!(value instanceof String)) throw new IOException("Missing or invalid " + key);
        return (String) value;
    }

    private static long number(Map<String, Object> object, String key) throws IOException {
        Object value = object.get(key);
        if (!(value instanceof Long)) throw new IOException("Missing or invalid " + key);
        return (Long) value;
    }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 255;
            result[i * 2] = digits[value >>> 4];
            result[i * 2 + 1] = digits[value & 15];
        }
        return new String(result);
    }

    private static long checkedAdd(long current, long delta, long limit, String message)
            throws IOException {
        if (delta < 0 || current < 0 || current > limit || delta > limit - current) {
            throw new IOException(message);
        }
        return current + delta;
    }

    private static void checkCancelled(Cancellation cancellation) throws CancelledException {
        if (cancellation.isCancelled()) throw new CancelledException();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }

    private static final class NonClosingOutputStream extends FilterOutputStream {
        NonClosingOutputStream(OutputStream output) { super(output); }
        @Override public void close() throws IOException { flush(); }
    }

    private static final class NonClosingInputStream extends FilterInputStream {
        NonClosingInputStream(InputStream input) { super(input); }
        @Override public void close() { }
    }

    /** Minimal strict JSON parser/writer to keep the archive core runnable in desktop tests. */
    private static final class Json {
        static Object parse(String text) throws IOException {
            if (text == null) throw new IOException("JSON is null");
            Parser parser = new Parser(text);
            Object value = parser.value(0);
            parser.whitespace();
            if (parser.index != text.length()) throw parser.error("Trailing JSON data");
            return value;
        }

        static String write(Object value) throws IOException {
            StringBuilder output = new StringBuilder();
            append(output, value, 0);
            return output.toString();
        }

        @SuppressWarnings("unchecked")
        private static void append(StringBuilder out, Object value, int depth)
                throws IOException {
            if (depth > MAX_JSON_DEPTH) throw new IOException("JSON nesting is too deep");
            if (value == null) { out.append("null"); return; }
            if (value instanceof String) { string(out, (String) value); return; }
            if (value instanceof Boolean) { out.append(value.toString()); return; }
            if (value instanceof Long || value instanceof Integer) {
                out.append(((Number) value).longValue()); return;
            }
            if (value instanceof Map) {
                out.append('{'); boolean first = true;
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (!(entry.getKey() instanceof String)) throw new IOException("Non-string JSON key");
                    if (!first) out.append(','); first = false;
                    string(out, (String) entry.getKey()); out.append(':');
                    append(out, entry.getValue(), depth + 1);
                }
                out.append('}'); return;
            }
            if (value instanceof List) {
                out.append('['); boolean first = true;
                for (Object item : (List<Object>) value) {
                    if (!first) out.append(','); first = false;
                    append(out, item, depth + 1);
                }
                out.append(']'); return;
            }
            throw new IOException("Unsupported JSON value");
        }

        private static void string(StringBuilder out, String value) throws IOException {
            out.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"': out.append("\\\""); break;
                    case '\\': out.append("\\\\"); break;
                    case '\b': out.append("\\b"); break;
                    case '\f': out.append("\\f"); break;
                    case '\n': out.append("\\n"); break;
                    case '\r': out.append("\\r"); break;
                    case '\t': out.append("\\t"); break;
                    default:
                        if (c < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                        else if (Character.isHighSurrogate(c)) {
                            if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                                throw new IOException("Unpaired JSON surrogate");
                            }
                            out.append(c).append(value.charAt(++i));
                        } else if (Character.isLowSurrogate(c)) {
                            throw new IOException("Unpaired JSON surrogate");
                        } else out.append(c);
                }
            }
            out.append('"');
        }

        private static final class Parser {
            final String text;
            int index;
            Parser(String text) { this.text = text; }

            Object value(int depth) throws IOException {
                if (depth > MAX_JSON_DEPTH) throw error("JSON nesting is too deep");
                whitespace();
                if (index >= text.length()) throw error("Unexpected end of JSON");
                char c = text.charAt(index);
                if (c == '{') return object(depth + 1);
                if (c == '[') return array(depth + 1);
                if (c == '"') return string();
                if (c == '-' || c >= '0' && c <= '9') return number();
                if (take("true")) return Boolean.TRUE;
                if (take("false")) return Boolean.FALSE;
                if (take("null")) return null;
                throw error("Unexpected JSON token");
            }

            LinkedHashMap<String, Object> object(int depth) throws IOException {
                index++; whitespace();
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                if (peek('}')) { index++; return result; }
                while (true) {
                    whitespace();
                    if (!peek('"')) throw error("Object key must be a string");
                    String key = string();
                    if (result.containsKey(key)) throw error("Duplicate JSON key: " + key);
                    whitespace(); expect(':');
                    result.put(key, value(depth));
                    whitespace();
                    if (peek('}')) { index++; return result; }
                    expect(',');
                }
            }

            ArrayList<Object> array(int depth) throws IOException {
                index++; whitespace();
                ArrayList<Object> result = new ArrayList<>();
                if (peek(']')) { index++; return result; }
                while (true) {
                    result.add(value(depth)); whitespace();
                    if (peek(']')) { index++; return result; }
                    expect(',');
                }
            }

            String string() throws IOException {
                expect('"'); StringBuilder result = new StringBuilder();
                while (index < text.length()) {
                    char c = text.charAt(index++);
                    if (c == '"') return result.toString();
                    if (c < 32) throw error("Control character in JSON string");
                    if (c != '\\') {
                        if (Character.isHighSurrogate(c)) {
                            if (index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) {
                                throw error("Unpaired JSON surrogate");
                            }
                            result.append(c).append(text.charAt(index++));
                        } else if (Character.isLowSurrogate(c)) throw error("Unpaired JSON surrogate");
                        else result.append(c);
                        continue;
                    }
                    if (index >= text.length()) throw error("Broken JSON escape");
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '"': result.append('"'); break;
                        case '\\': result.append('\\'); break;
                        case '/': result.append('/'); break;
                        case 'b': result.append('\b'); break;
                        case 'f': result.append('\f'); break;
                        case 'n': result.append('\n'); break;
                        case 'r': result.append('\r'); break;
                        case 't': result.append('\t'); break;
                        case 'u':
                            char unicode = unicode();
                            if (Character.isHighSurrogate(unicode)) {
                                if (index + 1 >= text.length() || text.charAt(index) != '\\'
                                        || text.charAt(index + 1) != 'u') {
                                    throw error("Unpaired JSON surrogate");
                                }
                                index += 2; char low = unicode();
                                if (!Character.isLowSurrogate(low)) throw error("Unpaired JSON surrogate");
                                result.append(unicode).append(low);
                            } else if (Character.isLowSurrogate(unicode)) {
                                throw error("Unpaired JSON surrogate");
                            } else result.append(unicode);
                            break;
                        default: throw error("Unknown JSON escape");
                    }
                }
                throw error("Unterminated JSON string");
            }

            char unicode() throws IOException {
                if (index + 4 > text.length()) throw error("Broken Unicode escape");
                int value = 0;
                for (int i = 0; i < 4; i++) {
                    int digit = Character.digit(text.charAt(index++), 16);
                    if (digit < 0) throw error("Broken Unicode escape");
                    value = value * 16 + digit;
                }
                return (char) value;
            }

            Long number() throws IOException {
                int start = index;
                if (peek('-')) index++;
                if (index >= text.length()) throw error("Broken JSON number");
                if (peek('0')) index++;
                else {
                    if (!digit()) throw error("Broken JSON number");
                    while (digit()) index++;
                }
                if (peek('.') || peek('e') || peek('E')) {
                    throw error("Only integer JSON numbers are supported");
                }
                try { return Long.valueOf(text.substring(start, index)); }
                catch (NumberFormatException failure) { throw error("JSON integer out of range"); }
            }

            boolean digit() {
                return index < text.length() && text.charAt(index) >= '0'
                        && text.charAt(index) <= '9';
            }

            boolean take(String expected) {
                if (!text.regionMatches(index, expected, 0, expected.length())) return false;
                index += expected.length(); return true;
            }

            void whitespace() {
                while (index < text.length()) {
                    char c = text.charAt(index);
                    if (c == ' ' || c == '\n' || c == '\r' || c == '\t') index++;
                    else break;
                }
            }

            void expect(char expected) throws IOException {
                if (!peek(expected)) throw error("Expected " + expected);
                index++;
            }

            boolean peek(char expected) {
                return index < text.length() && text.charAt(index) == expected;
            }

            IOException error(String message) {
                return new IOException(message + " at character " + index);
            }
        }
    }
}
