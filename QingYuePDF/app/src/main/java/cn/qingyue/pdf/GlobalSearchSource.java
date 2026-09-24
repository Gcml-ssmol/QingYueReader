package cn.qingyue.pdf;

import java.io.IOException;
import java.util.List;

/**
 * A pluggable, local-only source for {@link GlobalSearchEngine}.
 *
 * <p>Page numbers are always zero based. A document-level fragment uses page {@code -1}.
 * Implementations must make {@link Document#fingerprint} change whenever searchable content
 * changes. That contract is what allows the engine to reuse unchanged documents without ever
 * serving text from a changed file.</p>
 */
public interface GlobalSearchSource {
    String CHANNEL_PDF_TEXT = "pdf_text";
    String CHANNEL_NOTE_TEXT = "note_text";
    String CHANNEL_OCR_TEXT = "ocr_text";
    String CHANNEL_STUDY_SUMMARY = "study_summary";

    String JUMP_PDF_PAGE = "pdf_page";
    String JUMP_NOTE_PAGE = "note_page";
    String JUMP_DOCUMENT = "document";

    /** Stable across app launches and unique among the sources passed to one refresh. */
    String sourceId();

    /** Returns a bounded snapshot of the documents currently owned by this source. */
    List<Document> scan(Cancellation cancellation) throws IOException;

    /** Emits searchable fragments for one descriptor returned by the most recent scan. */
    void read(Document document, Sink sink, Cancellation cancellation) throws IOException;

    interface Cancellation {
        boolean isCancelled();
    }

    interface Sink {
        void accept(Fragment fragment) throws IOException;
    }

    final class Document {
        public final String id;
        public final String name;
        /** "pdf", "note", or a future local document kind. */
        public final String kind;
        /** Opaque source-owned invalidation stamp. */
        public final String fingerprint;
        public final int pageCount;
        public final String defaultJumpType;

        public Document(String id, String name, String kind, String fingerprint, int pageCount,
                String defaultJumpType) {
            this.id = safe(id);
            this.name = safe(name);
            this.kind = safe(kind);
            this.fingerprint = safe(fingerprint);
            this.pageCount = pageCount;
            this.defaultJumpType = safe(defaultJumpType);
        }
    }

    final class Fragment {
        /** Zero-based page, or -1 for document-level content. */
        public final int page;
        public final String channel;
        /** Optional text-box name, OCR region label, or summary heading. */
        public final String section;
        public final String text;
        public final String jumpType;
        /** Optional provider-owned stable anchor, never interpreted by the index. */
        public final String anchor;

        public Fragment(int page, String channel, String section, String text, String jumpType,
                String anchor) {
            this.page = page;
            this.channel = safe(channel);
            this.section = safe(section);
            this.text = safe(text);
            this.jumpType = safe(jumpType);
            this.anchor = safe(anchor);
        }
    }

    static String safe(String value) { return value == null ? "" : value; }
}
