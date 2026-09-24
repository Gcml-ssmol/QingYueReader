package cn.qingyue.pdf;

public final class BackupManager {
    private BackupManager() { }

    public interface Cancellation { boolean isCancelled(); }

    public interface Progress {
        void onProgress(String phase, String detail, long completedBytes, long totalBytes,
                        int completedEntries, int totalEntries);
    }

    public static class Result {
        public final int itemCount, entryCount, conflictCopies;
        public final long bytes;
        public final String warning;

        Result(int itemCount, int entryCount, int conflictCopies, long bytes, String warning) {
            this.itemCount = itemCount;
            this.entryCount = entryCount;
            this.conflictCopies = conflictCopies;
            this.bytes = bytes;
            this.warning = warning == null ? "" : warning;
        }
    }
}
