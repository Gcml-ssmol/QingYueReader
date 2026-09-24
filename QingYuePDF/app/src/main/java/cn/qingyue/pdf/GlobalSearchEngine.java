package cn.qingyue.pdf;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

/**
 * Bounded, persistent full-library text index. The index is a disposable cache and never mutates
 * the library, PDFs, notes, OCR data, or study data that fed it.
 */
public final class GlobalSearchEngine {
    private static final int MAGIC = 0x51595349; // QYSI
    private static final int VERSION = 1;
    private static final long HEADER_BYTES = 24;
    private static final long MAX_INDEX_BYTES = 64L * 1024 * 1024;
    /** Keeps the in-memory UTF-16 snapshot well below typical Android heap limits. */
    private static final long MAX_SEARCHABLE_TEXT_BYTES = 24L * 1024 * 1024;
    private static final int MAX_DOCUMENTS = 10000;
    /** One logical document can contribute base text plus several independently invalidated channels. */
    private static final int MAX_INDEX_RECORDS = 50000;
    private static final int MAX_FRAGMENTS = 100000;
    private static final int MAX_FRAGMENTS_PER_DOCUMENT = 20000;
    private static final int MAX_ID_BYTES = 1024;
    private static final int MAX_NAME_BYTES = 16 * 1024;
    private static final int MAX_STAMP_BYTES = 16 * 1024;
    private static final int MAX_TEXT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PAGE = 1000000;

    public static final int DEFAULT_RESULT_LIMIT = 200;
    public static final int MAX_RESULT_LIMIT = 500;

    /** Serializes cache load/refresh across Activity instances in this app process. */
    private static final Object INDEX_PROCESS_LOCK = new Object();

    private final File indexFile;
    private volatile Snapshot snapshot = Snapshot.empty();
    private volatile String loadWarning;

    public GlobalSearchEngine(File indexFile) {
        if (indexFile == null) throw new IllegalArgumentException("indexFile");
        this.indexFile = indexFile;
        loadInitial();
    }

    /** A corrupt or incompatible cache is ignored; source data is never touched. */
    private void loadInitial() {
        synchronized (INDEX_PROCESS_LOCK) { loadInitialLocked(); }
    }

    private void loadInitialLocked() {
        File backup = backupFile();
        try {
            snapshot = readSnapshot(indexFile);
            return;
        } catch (IOException badMain) {
            if (indexFile.isFile()) loadWarning = "搜索索引已损坏，将在后台重建";
        }
        try {
            Snapshot recovered = readSnapshot(backup);
            snapshot = recovered;
            loadWarning = "搜索索引使用了上次完整备份，将在后台校验";
        } catch (IOException ignored) {
            snapshot = Snapshot.empty();
        }
    }

    public String loadWarning() { return loadWarning; }
    public int documentCount() { return snapshot.records.size(); }
    public int fragmentCount() { return snapshot.fragmentCount; }

    /** Mutable token shared with every source. Interrupting the worker is also treated as cancel. */
    public static final class CancellationToken implements GlobalSearchSource.Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        public void cancel() { cancelled.set(true); }
        @Override public boolean isCancelled() {
            return cancelled.get() || Thread.currentThread().isInterrupted();
        }
    }

    public interface ProgressListener {
        /** Called on the refresh caller's thread. */
        void onProgress(int doneDocuments, int totalDocuments, String documentName,
                boolean reused);
    }

    public static final class Failure {
        public final String sourceId, documentId, documentName, message;
        Failure(String sourceId, String documentId, String documentName, String message) {
            this.sourceId = sourceId; this.documentId = documentId;
            this.documentName = documentName; this.message = message;
        }
    }

    public static final class RefreshReport {
        public final int documents, fragments, rebuiltDocuments, reusedDocuments;
        public final List<Failure> failures;
        RefreshReport(int documents, int fragments, int rebuiltDocuments, int reusedDocuments,
                List<Failure> failures) {
            this.documents = documents; this.fragments = fragments;
            this.rebuiltDocuments = rebuiltDocuments; this.reusedDocuments = reusedDocuments;
            this.failures = Collections.unmodifiableList(new ArrayList<Failure>(failures));
        }
    }

    /**
     * Builds a complete replacement snapshot and atomically publishes it only after every source
     * has been scanned. A cancelled refresh leaves both the in-memory and on-disk snapshot intact.
     */
    public synchronized RefreshReport refresh(List<? extends GlobalSearchSource> sources,
            GlobalSearchSource.Cancellation cancellation, ProgressListener listener)
            throws IOException {
        synchronized (INDEX_PROCESS_LOCK) {
            return refreshExclusive(sources, cancellation, listener);
        }
    }

    private RefreshReport refreshExclusive(List<? extends GlobalSearchSource> sources,
            GlobalSearchSource.Cancellation cancellation, ProgressListener listener)
            throws IOException {
        if (sources == null) throw new IllegalArgumentException("sources");
        if (cancellation == null) cancellation = new CancellationToken();
        checkCancelled(cancellation);

        ArrayList<Work> work = new ArrayList<Work>();
        LinkedHashMap<GlobalSearchSource, List<GlobalSearchSource.Document>> scanned =
                new LinkedHashMap<GlobalSearchSource, List<GlobalSearchSource.Document>>();
        HashSet<String> sourceIds = new HashSet<String>();
        HashSet<String> logicalDocuments = new HashSet<String>();
        for (GlobalSearchSource source : sources) {
            if (source == null) throw new IOException("搜索来源为空");
            String sourceId;
            try { sourceId = bounded(source.sourceId(), MAX_ID_BYTES, "搜索来源编号"); }
            catch (RuntimeException failure) { throw sourceFailure("读取搜索来源编号", failure); }
            if (!sourceIds.add(sourceId)) throw new IOException("重复的搜索来源：" + sourceId);
            List<GlobalSearchSource.Document> documents = scanSource(source, cancellation,
                    sourceId);
            checkCancelled(cancellation);
            if (documents == null) throw new IOException("搜索来源未返回文档：" + sourceId);
            scanned.put(source, new ArrayList<GlobalSearchSource.Document>(documents));
            HashSet<String> documentIds = new HashSet<String>();
            for (GlobalSearchSource.Document document : documents) {
                validateDocument(document);
                if (!documentIds.add(document.id))
                    throw new IOException("搜索来源包含重复文档：" + document.id);
                work.add(new Work(source, sourceId, document));
                logicalDocuments.add(document.kind + '\u0000' + document.id);
                if (logicalDocuments.size() > MAX_DOCUMENTS)
                    throw new IOException("可索引资料超过上限");
                if (work.size() > MAX_INDEX_RECORDS)
                    throw new IOException("可索引来源记录超过上限");
            }
        }

        Snapshot previous = snapshot;
        HashMap<String, Record> reusable = new HashMap<String, Record>();
        for (Record record : previous.records) reusable.put(key(record.sourceId, record.document.id), record);

        ArrayList<Record> next = new ArrayList<Record>();
        ArrayList<Failure> failures = new ArrayList<Failure>();
        int fragments = 0, rebuilt = 0, reused = 0, done = 0;
        long searchableTextBytes = 0;
        for (Work item : work) {
            checkCancelled(cancellation);
            Record old = reusable.get(key(item.sourceId, item.document.id));
            boolean didReuse = old != null && old.document.fingerprint.equals(item.document.fingerprint);
            if (didReuse) {
                Record refreshedMetadata = new Record(item.sourceId, item.document, old.fragments);
                if (searchableTextBytes + refreshedMetadata.textBytes > MAX_SEARCHABLE_TEXT_BYTES)
                    throw new IOException("搜索索引文字总量超过上限");
                next.add(refreshedMetadata);
                fragments += old.fragments.size();
                searchableTextBytes += refreshedMetadata.textBytes;
                reused++;
            } else {
                final ArrayList<GlobalSearchSource.Fragment> collected =
                        new ArrayList<GlobalSearchSource.Fragment>();
                final GlobalSearchSource.Cancellation token = cancellation;
                final long alreadyIndexedBytes = searchableTextBytes;
                final long[] documentTextBytes = {0};
                try {
                    item.source.read(item.document, new GlobalSearchSource.Sink() {
                        @Override public void accept(GlobalSearchSource.Fragment fragment)
                                throws IOException {
                            checkCancelled(token);
                            validateFragment(fragment, item.document);
                            if (collected.size() >= MAX_FRAGMENTS_PER_DOCUMENT)
                                throw new IOException("单份文档的可搜索片段超过上限");
                            String compact = normalize(fragment.text);
                            if (compact.length() == 0) return;
                            int bytes = utf8Length(compact, MAX_TEXT_BYTES, "搜索片段");
                            if (alreadyIndexedBytes + documentTextBytes[0] + bytes
                                    > MAX_SEARCHABLE_TEXT_BYTES)
                                throw new IOException("搜索索引文字总量超过上限");
                            collected.add(new GlobalSearchSource.Fragment(fragment.page,
                                    fragment.channel, fragment.section, compact,
                                    fragment.jumpType, fragment.anchor));
                            documentTextBytes[0] += bytes;
                        }
                    }, cancellation);
                    checkCancelled(cancellation);
                    if (fragments + collected.size() > MAX_FRAGMENTS)
                        throw new IOException("搜索索引片段超过上限");
                    next.add(new Record(item.sourceId, item.document, collected));
                    fragments += collected.size();
                    searchableTextBytes += documentTextBytes[0];
                    rebuilt++;
                } catch (IOException failure) {
                    checkCancelled(cancellation);
                    next.add(new Record(item.sourceId, item.document,
                            Collections.<GlobalSearchSource.Fragment>emptyList()));
                    failures.add(new Failure(item.sourceId, item.document.id, item.document.name,
                            useful(failure)));
                } catch (RuntimeException failure) {
                    checkCancelled(cancellation);
                    next.add(new Record(item.sourceId, item.document,
                            Collections.<GlobalSearchSource.Fragment>emptyList()));
                    failures.add(new Failure(item.sourceId, item.document.id, item.document.name,
                            useful(failure)));
                }
            }
            done++;
            if (listener != null)
                listener.onProgress(done, work.size(), item.document.name, didReuse);
        }

        checkCancelled(cancellation);
        // A PDF, note, or sidecar can be replaced while extraction is running. Re-read cheap
        // source stamps before publication so that content is never committed under an old stamp.
        for (Map.Entry<GlobalSearchSource, List<GlobalSearchSource.Document>> source
                : scanned.entrySet()) {
            List<GlobalSearchSource.Document> latest = scanSource(source.getKey(), cancellation,
                    "提交前复核");
            checkCancelled(cancellation);
            if (!sameDescriptors(source.getValue(), latest))
                throw new IOException("建立索引期间本地内容发生变化，请重试");
        }
        Snapshot completed = new Snapshot(next, fragments);
        writeSnapshot(completed, cancellation);
        // Once the atomic rename succeeds, publish the identical in-memory snapshot even if a
        // cancellation races with these final instructions. This keeps disk and memory coherent.
        snapshot = completed;
        loadWarning = null;
        return new RefreshReport(next.size(), fragments, rebuilt, reused, failures);
    }

    public SearchResponse search(String query) { return search(query, "", DEFAULT_RESULT_LIMIT); }

    /** A null or empty channel searches all sources, including document titles. */
    public SearchResponse search(String query, String channel) {
        return search(query, channel, DEFAULT_RESULT_LIMIT);
    }

    /** Literal phrase matches rank first; space-separated terms fall back to AND matching. */
    public SearchResponse search(String query, int requestedLimit) {
        return search(query, "", requestedLimit);
    }

    /** Filter candidates before ranking and truncation so other channels cannot hide matches. */
    public SearchResponse search(String query, String channel, int requestedLimit) {
        String displayQuery = normalize(query);
        if (displayQuery.length() == 0)
            return new SearchResponse(displayQuery, Collections.<Result>emptyList(), false);
        String selectedChannel = channel == null ? "" : channel;
        boolean allChannels = selectedChannel.isEmpty();
        int limit = Math.max(1, Math.min(MAX_RESULT_LIMIT, requestedLimit));
        Query matcher = new Query(query, displayQuery);
        Snapshot current = snapshot;
        ArrayList<Scored> found = new ArrayList<Scored>();
        HashSet<String> titleDocuments = new HashSet<String>();

        for (Record record : current.records) {
            Match title = allChannels || "document_title".equals(selectedChannel)
                    ? matcher.find(record.document.name) : null;
            String titleKey = record.document.kind + "\u0000" + record.document.id;
            if (title != null && titleDocuments.add(titleKey)) {
                found.add(new Scored(new Result(record.document.id, record.document.name,
                        record.document.kind, -1, "document_title", "",
                        record.document.name, title.count, record.document.defaultJumpType, ""),
                        2000 + title.score));
            }
            for (GlobalSearchSource.Fragment fragment : record.fragments) {
                if (!allChannels && !selectedChannel.equals(fragment.channel)) continue;
                Match match = matcher.find(fragment.text);
                if (match == null) continue;
                int channelScore = GlobalSearchSource.CHANNEL_NOTE_TEXT.equals(fragment.channel) ? 80
                        : GlobalSearchSource.CHANNEL_PDF_TEXT.equals(fragment.channel) ? 70
                        : GlobalSearchSource.CHANNEL_OCR_TEXT.equals(fragment.channel) ? 60
                        : GlobalSearchSource.CHANNEL_STUDY_SUMMARY.equals(fragment.channel) ? 50 : 0;
                found.add(new Scored(new Result(record.document.id, record.document.name,
                        record.document.kind, fragment.page, fragment.channel, fragment.section,
                        snippet(fragment.text, match.first, match.length), match.count,
                        fragment.jumpType, fragment.anchor), match.score + channelScore));
            }
        }

        Collections.sort(found, new Comparator<Scored>() {
            @Override public int compare(Scored left, Scored right) {
                int score = Integer.compare(right.score, left.score);
                if (score != 0) return score;
                int name = left.result.documentName.compareToIgnoreCase(right.result.documentName);
                if (name != 0) return name;
                int page = Integer.compare(left.result.page, right.result.page);
                if (page != 0) return page;
                return left.result.channel.compareTo(right.result.channel);
            }
        });
        boolean limited = found.size() > limit;
        ArrayList<Result> results = new ArrayList<Result>(Math.min(limit, found.size()));
        for (int i = 0; i < found.size() && i < limit; i++) results.add(found.get(i).result);
        return new SearchResponse(displayQuery, results, limited);
    }

    public static final class Result {
        public final String documentId, documentName, documentKind;
        /** Zero-based page, or -1 for a document-level result. */
        public final int page;
        public final String channel, section, snippet;
        public final int matches;
        public final String jumpType, anchor;
        Result(String documentId, String documentName, String documentKind, int page,
                String channel, String section, String snippet, int matches, String jumpType,
                String anchor) {
            this.documentId = documentId; this.documentName = documentName;
            this.documentKind = documentKind; this.page = page; this.channel = channel;
            this.section = section; this.snippet = snippet; this.matches = matches;
            this.jumpType = jumpType; this.anchor = anchor;
        }
    }

    public static final class SearchResponse {
        public final String normalizedQuery;
        public final List<Result> results;
        public final boolean limited;
        SearchResponse(String normalizedQuery, List<Result> results, boolean limited) {
            this.normalizedQuery = normalizedQuery;
            this.results = Collections.unmodifiableList(results);
            this.limited = limited;
        }
    }

    private static final class Work {
        final GlobalSearchSource source; final String sourceId;
        final GlobalSearchSource.Document document;
        Work(GlobalSearchSource source, String sourceId, GlobalSearchSource.Document document) {
            this.source = source; this.sourceId = sourceId; this.document = document;
        }
    }

    private static final class Record {
        final String sourceId; final GlobalSearchSource.Document document;
        final List<GlobalSearchSource.Fragment> fragments;
        final long textBytes;
        Record(String sourceId, GlobalSearchSource.Document document,
                List<GlobalSearchSource.Fragment> fragments) {
            this.sourceId = sourceId; this.document = document;
            this.fragments = Collections.unmodifiableList(
                    new ArrayList<GlobalSearchSource.Fragment>(fragments));
            long bytes = 0;
            for (GlobalSearchSource.Fragment fragment : fragments)
                bytes += fragment.text.getBytes(StandardCharsets.UTF_8).length;
            this.textBytes = bytes;
        }
    }

    private static final class Snapshot {
        final List<Record> records; final int fragmentCount;
        Snapshot(List<Record> records, int fragmentCount) {
            this.records = Collections.unmodifiableList(new ArrayList<Record>(records));
            this.fragmentCount = fragmentCount;
        }
        static Snapshot empty() { return new Snapshot(Collections.<Record>emptyList(), 0); }
    }

    private static final class Scored {
        final Result result; final int score;
        Scored(Result result, int score) { this.result = result; this.score = score; }
    }

    private static final class Match {
        final int first, length, count, score;
        Match(int first, int length, int count, int score) {
            this.first = first; this.length = length; this.count = count; this.score = score;
        }
    }

    private static final class Query {
        final String phrase, folded;
        final String[] terms;
        Query(String original, String phrase) {
            this.phrase = phrase;
            this.folded = phrase.toLowerCase(Locale.ROOT);
            String raw = original == null ? "" : Normalizer.normalize(original,
                    Normalizer.Form.NFKC).trim();
            String[] separated = raw.split("\\s+");
            if (separated.length > 1) {
                ArrayList<String> normalizedTerms = new ArrayList<String>();
                for (String value : separated) {
                    String term = normalize(value).toLowerCase(Locale.ROOT);
                    if (term.length() > 0) normalizedTerms.add(term);
                }
                this.terms = normalizedTerms.toArray(new String[normalizedTerms.size()]);
            } else this.terms = folded.split(" +");
        }
        Match find(String value) {
            String display = normalize(value);
            if (display.length() == 0) return null;
            String haystack = display.toLowerCase(Locale.ROOT);
            int phraseAt = haystack.indexOf(folded);
            if (phraseAt >= 0) {
                int count = occurrences(haystack, folded);
                int first = displayOffset(display, phraseAt);
                int end = displayOffset(display, phraseAt + folded.length());
                return new Match(first, Math.max(1, end - first), count,
                        500 + Math.min(100, count * 5));
            }
            if (terms.length <= 1) return null;
            int firstFolded = Integer.MAX_VALUE, count = 0, firstLength = 1;
            for (String term : terms) {
                if (term.length() == 0) continue;
                int at = haystack.indexOf(term);
                if (at < 0) return null;
                count += occurrences(haystack, term);
                if (at < firstFolded) { firstFolded = at; firstLength = term.length(); }
            }
            int first = displayOffset(display, firstFolded);
            int end = displayOffset(display, firstFolded + firstLength);
            return new Match(first, Math.max(1, end - first), count,
                    200 + Math.min(100, count * 5));
        }
    }

    private void writeSnapshot(Snapshot value, GlobalSearchSource.Cancellation cancellation)
            throws IOException {
        File directory = indexFile.getParentFile();
        if (directory == null) throw new IOException("搜索索引目录无效");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("无法创建搜索索引目录");
        String transaction = UUID.randomUUID().toString();
        File payload = new File(directory, indexFile.getName() + ".payload." + transaction + ".tmp");
        File staged = new File(directory, indexFile.getName() + ".write." + transaction + ".tmp");
        CRC32 checksum = new CRC32();
        try {
            try (FileOutputStream raw = new FileOutputStream(payload);
                    CheckedOutputStream checked = new CheckedOutputStream(raw, checksum);
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(checked))) {
                out.writeInt(value.records.size());
                int writtenFragments = 0;
                for (Record record : value.records) {
                    checkCancelled(cancellation);
                    writeString(out, record.sourceId, MAX_ID_BYTES);
                    writeString(out, record.document.id, MAX_ID_BYTES);
                    writeString(out, record.document.name, MAX_NAME_BYTES);
                    writeString(out, record.document.kind, MAX_ID_BYTES);
                    writeString(out, record.document.fingerprint, MAX_STAMP_BYTES);
                    out.writeInt(record.document.pageCount);
                    writeString(out, record.document.defaultJumpType, MAX_ID_BYTES);
                    out.writeInt(record.fragments.size());
                    for (GlobalSearchSource.Fragment fragment : record.fragments) {
                        out.writeInt(fragment.page);
                        writeString(out, fragment.channel, MAX_ID_BYTES);
                        writeString(out, fragment.section, MAX_NAME_BYTES);
                        writeString(out, fragment.text, MAX_TEXT_BYTES);
                        writeString(out, fragment.jumpType, MAX_ID_BYTES);
                        writeString(out, fragment.anchor, MAX_NAME_BYTES);
                        if (++writtenFragments > MAX_FRAGMENTS)
                            throw new IOException("搜索索引片段超过上限");
                    }
                }
                out.flush();
                raw.getFD().sync();
            }
            long payloadLength = payload.length();
            if (payloadLength <= 0 || payloadLength > MAX_INDEX_BYTES)
                throw new IOException("搜索索引大小超过上限");
            checkCancelled(cancellation);
            try (FileOutputStream raw = new FileOutputStream(staged);
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw));
                    FileInputStream in = new FileInputStream(payload)) {
                out.writeInt(MAGIC); out.writeInt(VERSION); out.writeLong(payloadLength);
                out.writeLong(checksum.getValue());
                byte[] buffer = new byte[65536]; int count;
                while ((count = in.read(buffer)) != -1) {
                    checkCancelled(cancellation); out.write(buffer, 0, count);
                }
                out.flush(); raw.getFD().sync();
            }
            checkCancelled(cancellation);
            replaceAtomically(staged);
        } finally {
            if (payload.exists()) payload.delete();
            if (staged.exists()) staged.delete();
        }
    }

    private void replaceAtomically(File staged) throws IOException {
        File backup = backupFile();
        if (backup.exists() && !backup.delete()) throw new IOException("无法轮换搜索索引备份");
        boolean hadIndex = indexFile.exists();
        if (hadIndex && !indexFile.renameTo(backup)) throw new IOException("无法备份旧搜索索引");
        if (!staged.renameTo(indexFile)) {
            if (hadIndex) backup.renameTo(indexFile);
            throw new IOException("无法提交新搜索索引");
        }
        if (backup.exists()) backup.delete();
    }

    private Snapshot readSnapshot(File file) throws IOException {
        if (!file.isFile()) throw new FileNotFoundException(file.getPath());
        long length = file.length();
        if (length < HEADER_BYTES || length > HEADER_BYTES + MAX_INDEX_BYTES)
            throw new IOException("搜索索引大小无效");
        try (FileInputStream raw = new FileInputStream(file);
                DataInputStream header = new DataInputStream(new BufferedInputStream(raw))) {
            if (header.readInt() != MAGIC || header.readInt() != VERSION)
                throw new IOException("搜索索引版本无效");
            long payloadLength = header.readLong(), expectedChecksum = header.readLong();
            if (payloadLength <= 0 || payloadLength > MAX_INDEX_BYTES
                    || payloadLength + HEADER_BYTES != length)
                throw new IOException("搜索索引长度无效");
            CRC32 checksum = new CRC32();
            CheckedInputStream checked = new CheckedInputStream(header, checksum);
            DataInputStream in = new DataInputStream(new BufferedInputStream(checked));
            int documentCount = in.readInt();
            if (documentCount < 0 || documentCount > MAX_INDEX_RECORDS)
                throw new IOException("搜索索引文档数量无效");
            ArrayList<Record> records = new ArrayList<Record>(documentCount);
            HashSet<String> logicalDocuments = new HashSet<String>();
            int fragmentCount = 0;
            long searchableTextBytes = 0;
            for (int i = 0; i < documentCount; i++) {
                String sourceId = readString(in, MAX_ID_BYTES);
                GlobalSearchSource.Document document = new GlobalSearchSource.Document(
                        readString(in, MAX_ID_BYTES), readString(in, MAX_NAME_BYTES),
                        readString(in, MAX_ID_BYTES), readString(in, MAX_STAMP_BYTES),
                        in.readInt(), readString(in, MAX_ID_BYTES));
                validateDocument(document);
                logicalDocuments.add(document.kind + '\u0000' + document.id);
                if (logicalDocuments.size() > MAX_DOCUMENTS)
                    throw new IOException("搜索索引资料数量无效");
                int count = in.readInt();
                if (count < 0 || count > MAX_FRAGMENTS_PER_DOCUMENT
                        || fragmentCount + count > MAX_FRAGMENTS)
                    throw new IOException("搜索索引片段数量无效");
                ArrayList<GlobalSearchSource.Fragment> fragments =
                        new ArrayList<GlobalSearchSource.Fragment>(count);
                for (int n = 0; n < count; n++) {
                    GlobalSearchSource.Fragment fragment = new GlobalSearchSource.Fragment(
                            in.readInt(), readString(in, MAX_ID_BYTES),
                            readString(in, MAX_NAME_BYTES), readString(in, MAX_TEXT_BYTES),
                            readString(in, MAX_ID_BYTES), readString(in, MAX_NAME_BYTES));
                    validateFragment(fragment, document); fragments.add(fragment);
                    searchableTextBytes += fragment.text.getBytes(StandardCharsets.UTF_8).length;
                    if (searchableTextBytes > MAX_SEARCHABLE_TEXT_BYTES)
                        throw new IOException("搜索索引文字总量超过上限");
                }
                fragmentCount += count;
                records.add(new Record(sourceId, document, fragments));
            }
            if (in.read() != -1) throw new IOException("搜索索引末尾数据无效");
            if (checksum.getValue() != expectedChecksum) throw new IOException("搜索索引校验失败");
            return new Snapshot(records, fragmentCount);
        } catch (RuntimeException malformed) {
            throw new IOException("搜索索引内容无效", malformed);
        }
    }

    private File backupFile() {
        return new File(indexFile.getParentFile(), indexFile.getName() + ".bak");
    }

    private static void validateDocument(GlobalSearchSource.Document document) throws IOException {
        if (document == null) throw new IOException("搜索文档为空");
        bounded(document.id, MAX_ID_BYTES, "文档编号");
        bounded(document.name, MAX_NAME_BYTES, "文档名称");
        bounded(document.kind, MAX_ID_BYTES, "文档类型");
        bounded(document.fingerprint, MAX_STAMP_BYTES, "文档指纹");
        bounded(document.defaultJumpType, MAX_ID_BYTES, "跳转类型");
        if (document.id.length() == 0 || document.name.length() == 0
                || document.kind.length() == 0 || document.fingerprint.length() == 0
                || document.defaultJumpType.length() == 0
                || document.pageCount < 0 || document.pageCount > MAX_PAGE)
            throw new IOException("搜索文档元数据无效");
    }

    private static void validateFragment(GlobalSearchSource.Fragment fragment,
            GlobalSearchSource.Document document) throws IOException {
        if (fragment == null) throw new IOException("搜索片段为空");
        if (fragment.page < -1 || fragment.page > MAX_PAGE
                || (fragment.page >= 0 && fragment.page >= document.pageCount))
            throw new IOException("搜索片段页码无效");
        if (fragment.channel.length() == 0 || fragment.jumpType.length() == 0)
            throw new IOException("搜索片段类型无效");
        bounded(fragment.channel, MAX_ID_BYTES, "搜索片段来源");
        bounded(fragment.section, MAX_NAME_BYTES, "搜索片段标题");
        bounded(fragment.jumpType, MAX_ID_BYTES, "搜索片段跳转类型");
        bounded(fragment.anchor, MAX_NAME_BYTES, "搜索片段定位信息");
    }

    private static void checkCancelled(GlobalSearchSource.Cancellation cancellation)
            throws InterruptedIOException {
        if (cancellation != null && cancellation.isCancelled())
            throw new InterruptedIOException("搜索索引已取消");
    }

    private static String useful(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().length() == 0 ? "无法读取可搜索文字" : message;
    }

    private static List<GlobalSearchSource.Document> scanSource(GlobalSearchSource source,
            GlobalSearchSource.Cancellation cancellation, String label) throws IOException {
        try { return source.scan(cancellation); }
        catch (IOException failure) { throw failure; }
        catch (RuntimeException failure) { throw sourceFailure("扫描搜索来源 " + label, failure); }
    }

    private static IOException sourceFailure(String operation, RuntimeException failure) {
        return new IOException(operation + "失败：" + useful(failure), failure);
    }

    private static String key(String sourceId, String documentId) {
        return sourceId + '\u0000' + documentId;
    }

    private static boolean sameDescriptors(List<GlobalSearchSource.Document> left,
            List<GlobalSearchSource.Document> right) {
        if (right == null || left.size() != right.size()) return false;
        HashMap<String, GlobalSearchSource.Document> expected =
                new HashMap<String, GlobalSearchSource.Document>();
        for (GlobalSearchSource.Document document : left) expected.put(document.id, document);
        for (GlobalSearchSource.Document document : right) {
            GlobalSearchSource.Document old = expected.remove(document.id);
            if (old == null || !old.name.equals(document.name) || !old.kind.equals(document.kind)
                    || !old.fingerprint.equals(document.fingerprint)
                    || old.pageCount != document.pageCount
                    || !old.defaultJumpType.equals(document.defaultJumpType)) return false;
        }
        return expected.isEmpty();
    }

    private static String bounded(String value, int maxBytes, String label) throws IOException {
        if (value == null || value.indexOf('\u0000') >= 0) throw new IOException(label + "无效");
        ensureUtf8(value, maxBytes, label); return value;
    }

    private static void ensureUtf8(String value, int maxBytes, String label) throws IOException {
        utf8Length(value, maxBytes, label);
    }

    private static int utf8Length(String value, int maxBytes, String label) throws IOException {
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        if (length > maxBytes)
            throw new IOException(label + "超过上限");
        return length;
    }

    private static void writeString(DataOutputStream out, String value, int maximum)
            throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximum) throw new IOException("搜索索引字段超过上限");
        out.writeInt(bytes.length); out.write(bytes);
    }

    private static String readString(DataInputStream in, int maximum) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > maximum) throw new IOException("搜索索引字段长度无效");
        byte[] bytes = new byte[length]; in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static String normalize(String text) {
        if (text == null || text.length() == 0) return "";
        String input = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(input.length());
        boolean space = false; int previous = -1;
        for (int i = 0; i < input.length();) {
            int cp = input.codePointAt(i); i += Character.charCount(cp);
            if (cp == 0x200b || cp == 0xfeff || cp == 0x00ad) continue;
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = out.length() > 0; continue;
            }
            if (space && !(isCjk(previous) && isCjk(cp))) out.append(' ');
            out.appendCodePoint(cp); previous = cp; space = false;
        }
        return out.toString();
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x9fff) || (cp >= 0xf900 && cp <= 0xfaff)
                || (cp >= 0x20000 && cp <= 0x323af) || (cp >= 0x3040 && cp <= 0x30ff)
                || (cp >= 0xac00 && cp <= 0xd7af);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int at = value.indexOf(needle); at >= 0;
                at = value.indexOf(needle, at + Math.max(1, needle.length()))) count++;
        return count;
    }

    private static int displayOffset(String display, int foldedOffset) {
        int folded = 0;
        for (int i = 0; i < display.length();) {
            if (folded >= foldedOffset) return i;
            int next = i + Character.charCount(display.codePointAt(i));
            folded += display.substring(i, next).toLowerCase(Locale.ROOT).length();
            i = next;
        }
        return display.length();
    }

    private static String snippet(String text, int matchStart, int matchLength) {
        int begin = Math.max(0, matchStart - 48);
        int end = Math.min(text.length(), Math.max(matchStart + matchLength + 72, begin + 140));
        if (end - begin > 220) end = begin + 220;
        if (begin > 0 && Character.isLowSurrogate(text.charAt(begin))) begin--;
        if (end < text.length() && Character.isLowSurrogate(text.charAt(end))) end++;
        return (begin > 0 ? "…" : "") + text.substring(begin, end)
                + (end < text.length() ? "…" : "");
    }
}
