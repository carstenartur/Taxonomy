package com.taxonomy.backup;

import java.io.IOException;
import java.io.InputStream;

/** Streams entry bytes to durable storage and computes their length and SHA-256. */
@FunctionalInterface
public interface ComponentSink {
    /** The caller owns and closes input. The sink rejects duplicate or unsafe paths. */
    BackupEntry write(String path, InputStream input) throws IOException;

    /** Contributors call this regularly while inspecting data before their first write. */
    default void checkpoint() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Capture interrupted");
    }
}
