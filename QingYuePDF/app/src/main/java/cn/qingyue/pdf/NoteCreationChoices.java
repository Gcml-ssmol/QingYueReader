package cn.qingyue.pdf;

import android.content.SharedPreferences;

/** Creation choices shared by the dialog and its preference regression test. */
final class NoteCreationChoices {
    interface Created { void created(String name, String template, String size); }
    String template, size, finiteSize;
    private final SharedPreferences preferences;

    NoteCreationChoices(SharedPreferences preferences) {
        this.preferences = preferences;
        template = validTemplate(preferences.getString("template", "blank"));
        size = validSize(preferences.getString("size", "infinite"));
        finiteSize = validFinite(preferences.getString("finiteSize", "A4"));
        if (!"infinite".equals(size)) finiteSize = size;
    }

    void draft() { size = "infinite"; }
    void paged() { size = finiteSize; }
    void size(String value) { finiteSize = validFinite(value); size = finiteSize; }
    void template(String value) { template = validTemplate(value); }

    boolean submit(String name, Created callback) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) return false;
        preferences.edit().putString("template", template).putString("size", size)
                .putString("finiteSize", finiteSize).apply();
        callback.created(clean, template, size);
        return true;
    }

    private static String validTemplate(String value) {
        return "ruled".equals(value) || "grid".equals(value) ? value : "blank";
    }
    private static String validFinite(String value) {
        return "A3".equals(value) || "A5".equals(value) || "B4".equals(value)
                || "B5".equals(value) || "Letter".equals(value) ? value : "A4";
    }
    private static String validSize(String value) {
        return "infinite".equals(value) ? value : validFinite(value);
    }
}
