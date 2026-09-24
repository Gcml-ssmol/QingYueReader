package android.content;

/** Minimal read-only preferences surface used by LibraryStore migration. */
public interface SharedPreferences {
    String getString(String key, String fallback);
}
