package android.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Test-only filesystem shim with the AtomicFile operations used by LibraryStore. */
public final class AtomicFile {
    private final File base;
    private final File fresh;

    public AtomicFile(File base) {
        this.base = base;
        this.fresh = new File(base.getPath() + ".new");
    }

    public FileInputStream openRead() throws IOException {
        return new FileInputStream(base);
    }

    public FileOutputStream startWrite() throws IOException {
        File parent = fresh.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create atomic parent");
        }
        return new FileOutputStream(fresh);
    }

    public void finishWrite(FileOutputStream output) throws IOException {
        output.getFD().sync();
        output.close();
        try {
            Files.move(fresh.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(fresh.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void failWrite(FileOutputStream output) {
        try { output.close(); } catch (IOException ignored) { }
        fresh.delete();
    }
}
