package cn.qingyue.pdf;

/** Compile-only note model for LibraryStore's reconstruction path. */
public final class NoteDocument {
    public String template = "blank";
    public String size = "A4";
    public int pageCount() { return 1; }
}
