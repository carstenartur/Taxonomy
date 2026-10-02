package com.taxonomy.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Streams entry bytes to durable storage and computes their length and SHA-256. */
@FunctionalInterface
public interface ComponentSink {
    /** The caller owns and closes input. The sink rejects duplicate or unsafe paths. */
    BackupEntry write(String path, InputStream input) throws IOException;

    /**
     * Produce an entry synchronously on the capture thread without buffering the complete entry.
     * The stream is valid only during the callback; closing it does not transfer ownership of
     * durable storage. Unsupported sinks fail before invoking the producer.
     */
    default BackupEntry writeGenerated(String path, EntryWriter producer) throws IOException {
        throw new IOException("Capture sink does not support generated entries");
    }

    @FunctionalInterface interface EntryWriter { void write(OutputStream output) throws IOException; }

    /** Contributors call this regularly while inspecting data before their first write. */
    default void checkpoint() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Capture interrupted");
    }
}
