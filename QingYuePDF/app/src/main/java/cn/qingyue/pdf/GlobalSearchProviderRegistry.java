package cn.qingyue.pdf;

import java.util.*;

/** Process-local registry populated by optional note/OCR/study modules during app startup. */
public final class GlobalSearchProviderRegistry {
    private static final LinkedHashMap<String, GlobalSearchContentProvider> PROVIDERS =
            new LinkedHashMap<String, GlobalSearchContentProvider>();
    private GlobalSearchProviderRegistry() { }

    public static synchronized void register(GlobalSearchContentProvider provider) {
        if (provider == null || provider.providerId() == null
                || !provider.providerId().matches("[a-zA-Z0-9._-]{1,80}"))
            throw new IllegalArgumentException("providerId");
        String channel = provider.channel();
        if (channel == null || !channel.matches("[a-zA-Z0-9._-]{1,80}"))
            throw new IllegalArgumentException("channel");
        PROVIDERS.put(provider.providerId(), provider);
    }

    public static synchronized void unregister(String providerId) { PROVIDERS.remove(providerId); }

    public static synchronized List<GlobalSearchContentProvider> snapshot() {
        return Collections.unmodifiableList(
                new ArrayList<GlobalSearchContentProvider>(PROVIDERS.values()));
    }
}
