package android.os;

import java.io.File;

/** Compile-only stub for LibraryStore's PDF reconstruction path. */
public final class ParcelFileDescriptor implements AutoCloseable {
    public static final int MODE_READ_ONLY = 0;
    public static ParcelFileDescriptor open(File file, int mode) { return new ParcelFileDescriptor(); }
    @Override public void close() { }
}
