package com.taxonomy.backup.archive;

import java.io.*;
import java.nio.channels.SeekableByteChannel;

/** Standard authenticated streaming protection; keys are supplied independently of the archive. */
public interface ArchiveProtectionProvider {
    boolean encrypted();
    OutputStream protect(OutputStream output, byte[] context) throws IOException;
    InputStream unprotect(InputStream input, byte[] context) throws IOException;
    SeekableByteChannel open(SeekableByteChannel input, byte[] context) throws IOException;

    static ArchiveProtectionProvider unprotected() {
        return new ArchiveProtectionProvider() {
            public boolean encrypted() { return false; }
            public OutputStream protect(OutputStream output, byte[] context) { return output; }
            public InputStream unprotect(InputStream input, byte[] context) { return input; }
            public SeekableByteChannel open(SeekableByteChannel input, byte[] context) { return input; }
        };
    }
}
