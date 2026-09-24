package cn.qingyue.pdf;

import android.content.Context;
import java.io.*;
import java.util.*;

/** Turns one note/OCR/summary provider into an independently invalidated search source. */
public final class GlobalSearchProviderSource implements GlobalSearchSource {
    private final Context context;
    private final GlobalSearchContentProvider provider;
    private final Map<String, GlobalSearchLibrarySource.LibraryDocument> current =
            new HashMap<String, GlobalSearchLibrarySource.LibraryDocument>();
    private final Map<String, IOException> scanFailures = new HashMap<String, IOException>();
    private GlobalSearchContentProvider.ScanSession scanSession;

    public GlobalSearchProviderSource(Context context, GlobalSearchContentProvider provider) {
        if (context == null || provider == null) throw new IllegalArgumentException();
        this.context = context.getApplicationContext(); this.provider = provider;
    }

    @Override public String sourceId() { return "content:" + provider.providerId(); }

    @Override public List<Document> scan(Cancellation cancellation) throws IOException {
        GlobalSearchLibrarySource.Catalog catalog =
                GlobalSearchLibrarySource.readCatalog(context, cancellation);
        GlobalSearchContentProvider.ScanSession nextSession =
                provider.openScan(context, cancellation);
        current.clear(); scanFailures.clear();
        ArrayList<Document> result = new ArrayList<Document>();
        for (GlobalSearchLibrarySource.LibraryDocument library : catalog.documents) {
            if (cancellation != null && cancellation.isCancelled())
                throw new InterruptedIOException("搜索索引已取消");
            boolean supports;
            try { supports = provider.supports(library); }
            catch (RuntimeException badProvider) { supports = false; }
            if (!supports) continue;
            String providerStamp;
            try {
                providerStamp = nextSession.fingerprint(library, cancellation);
            } catch (IOException failure) {
                providerStamp = "provider-read-error";
                scanFailures.put(library.id, failure);
            }
            if (providerStamp == null || providerStamp.length() == 0) continue;
            current.put(library.id, library);
            result.add(new Document(library.id, library.name, library.kind,
                    library.contentFingerprint() + "|" + providerStamp, library.pages,
                    library.isNote() ? JUMP_NOTE_PAGE : JUMP_PDF_PAGE));
        }
        scanSession = nextSession;
        return result;
    }

    @Override public void read(Document document, final Sink sink, Cancellation cancellation)
            throws IOException {
        final GlobalSearchLibrarySource.LibraryDocument library = current.get(document.id);
        if (library == null) throw new IOException("内容提供方找不到文档");
        IOException scanFailure = scanFailures.get(document.id);
        if (scanFailure != null) throw scanFailure;
        final String channel = provider.channel();
        GlobalSearchContentProvider.ScanSession session = scanSession;
        if (session == null) throw new IOException("内容提供方尚未建立扫描快照");
        session.read(library, new GlobalSearchContentProvider.Sink() {
            @Override public void accept(GlobalSearchContentProvider.Text text) throws IOException {
                if (text == null) throw new IOException("内容提供方返回空片段");
                String jump = text.jumpType.length() == 0
                        ? (library.isNote() ? JUMP_NOTE_PAGE : JUMP_PDF_PAGE) : text.jumpType;
                sink.accept(new Fragment(text.page, channel, text.section, text.text, jump,
                        text.anchor));
            }
        }, cancellation);
    }
}
