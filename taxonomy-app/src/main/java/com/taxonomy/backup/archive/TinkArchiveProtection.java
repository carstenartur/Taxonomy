package com.taxonomy.backup.archive;

import com.google.crypto.tink.*;
import com.google.crypto.tink.streamingaead.StreamingAeadConfig;
import java.io.*;
import java.nio.channels.*;
import java.security.GeneralSecurityException;

/** Tink Streaming AEAD provides segment authentication, nonce generation and bounded seekable decryption. */
public final class TinkArchiveProtection implements ArchiveProtectionProvider {
    private final StreamingAead primitive;

    public TinkArchiveProtection(KeysetHandle keyset) throws GeneralSecurityException {
        StreamingAeadConfig.register();
        primitive = keyset.getPrimitive(RegistryConfiguration.get(), StreamingAead.class);
    }
    @Override public boolean encrypted() { return true; }
    @Override public OutputStream protect(OutputStream output, byte[] context) throws IOException {
        try { return primitive.newEncryptingStream(output, context); }
        catch (GeneralSecurityException failure) { throw new IOException("Cannot protect backup stream"); }
    }
    @Override public InputStream unprotect(InputStream input, byte[] context) throws IOException {
        try { return primitive.newDecryptingStream(input, context); }
        catch (GeneralSecurityException failure) { throw new IOException("Cannot authenticate backup stream"); }
    }
    @Override public SeekableByteChannel open(SeekableByteChannel channel, byte[] context) throws IOException {
        try {
            var decrypted = primitive.newSeekableDecryptingChannel(channel, context);
            // Tink selects/authenticates the matching key lazily on the first read.
            if (decrypted.read(java.nio.ByteBuffer.allocate(1)) < 0) throw new IOException("Empty protected archive");
            decrypted.position(0);
            return decrypted;
        }
        catch (GeneralSecurityException | IOException | RuntimeException failure) {
            channel.close(); throw new IOException("Cannot authenticate backup archive");
        }
    }
}
