package cn.qingyue.pdf;

import android.content.Context;
import java.io.IOException;

/**
 * Adapter contract for future note text boxes, OCR sidecars, and study summaries.
 * Providers read their own data only; the global index remains a disposable cache.
 */
public interface GlobalSearchContentProvider {
    /** Stable identifier such as "note-text-v2", "local-ocr-v1", or "study-summary-v1". */
    String providerId();

    /** One of the channel constants in {@link GlobalSearchSource}, or a stable extension. */
    String channel();

    boolean supports(GlobalSearchLibrarySource.LibraryDocument document);

    /**
     * Must change whenever emitted text changes. Return an empty string when the document has no
     * data for this provider. The adapter also includes the underlying document fingerprint.
     */
    String fingerprint(Context context, GlobalSearchLibrarySource.LibraryDocument document,
            GlobalSearchSource.Cancellation cancellation) throws IOException;

    void read(Context context, GlobalSearchLibrarySource.LibraryDocument document, Sink sink,
            GlobalSearchSource.Cancellation cancellation) throws IOException;

    /**
     * Opens one immutable scan view. Providers with a shared sidecar can override this to load it
     * once per refresh rather than once per library item. The returned object is used for both
     * fingerprints and reads from the same descriptor scan.
     */
    default ScanSession openScan(final Context context,
            GlobalSearchSource.Cancellation cancellation) throws IOException {
        final GlobalSearchContentProvider owner = this;
        return new ScanSession() {
            @Override public String fingerprint(GlobalSearchLibrarySource.LibraryDocument document,
                    GlobalSearchSource.Cancellation token) throws IOException {
                return owner.fingerprint(context, document, token);
            }
            @Override public void read(GlobalSearchLibrarySource.LibraryDocument document,
                    Sink sink, GlobalSearchSource.Cancellation token) throws IOException {
                owner.read(context, document, sink, token);
            }
        };
    }

    interface ScanSession {
        String fingerprint(GlobalSearchLibrarySource.LibraryDocument document,
                GlobalSearchSource.Cancellation cancellation) throws IOException;
        void read(GlobalSearchLibrarySource.LibraryDocument document, Sink sink,
                GlobalSearchSource.Cancellation cancellation) throws IOException;
    }

    interface Sink {
        void accept(Text text) throws IOException;
    }

    final class Text {
        /** Zero-based page, or -1 for document-level summaries. */
        public final int page;
        public final String section, text, jumpType, anchor;
        public Text(int page, String section, String text, String jumpType, String anchor) {
            this.page = page;
            this.section = section == null ? "" : section;
            this.text = text == null ? "" : text;
            this.jumpType = jumpType == null ? "" : jumpType;
            this.anchor = anchor == null ? "" : anchor;
        }
    }
}
