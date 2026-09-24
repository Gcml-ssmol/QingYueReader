package cn.qingyue.pdf;

import android.content.Context;
import java.io.IOException;

/** Compile-only note store for LibraryStore's reconstruction path. */
public final class NoteStore {
    private NoteStore() { }
    public static NoteDocument load(Context context, String id) throws IOException {
        throw new IOException("note scanning is outside this desktop regression suite");
    }
    static void flushPending(Context context,String id) throws IOException { }
    static void invalidate(Context context,String id) throws IOException { }
    static void revive(Context context,String id) throws IOException { }
}
