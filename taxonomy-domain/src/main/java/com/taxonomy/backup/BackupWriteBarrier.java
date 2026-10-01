package com.taxonomy.backup;

/** A bounded logical write, possibly spanning several database/Git transactions. */
public interface BackupWriteBarrier {
    Section enter(BackupScope scope);

    interface Section extends AutoCloseable {
        long generation();
        void checkValid();
        @Override void close();
    }

    /** For installations with backup disabled; a capture coordinator must never use this. */
    static BackupWriteBarrier disabled() {
        return scope -> new Section() {
            @Override public long generation() { return 0; }
            @Override public void checkValid() { }
            @Override public void close() { }
        };
    }
}
