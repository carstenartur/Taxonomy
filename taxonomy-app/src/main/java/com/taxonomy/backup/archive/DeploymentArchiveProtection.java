package com.taxonomy.backup.archive;

import com.google.crypto.tink.*;
import com.google.crypto.tink.streamingaead.StreamingAeadConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Reads a bounded, operator-controlled secret mount. Key selection never comes from archive/request data. */
public final class DeploymentArchiveProtection {
    private DeploymentArchiveProtection() { }
    public static ArchiveProtectionProvider load(Path keysetFile, Path backupRoot) {
        try {
            Path key = keysetFile.toRealPath();
            Path root = backupRoot.toAbsolutePath().normalize();
            if (Files.exists(root)) root = root.toRealPath();
            if (key.startsWith(root) || !Files.isRegularFile(key)) throw new IllegalStateException();
            byte[] bytes;
            try (var input = Files.newInputStream(key)) { bytes = input.readNBytes(65537); }
            if (bytes.length == 0 || bytes.length > 65536) throw new IllegalStateException();
            StreamingAeadConfig.register();
            return new TinkArchiveProtection(TinkJsonProtoKeysetFormat.parseKeyset(new String(bytes, StandardCharsets.UTF_8), InsecureSecretKeyAccess.get()));
        } catch (Exception invalid) {
            // Neither key contents, mount paths nor provider errors are appropriate for logs or HTTP.
            throw new IllegalStateException("Backup key configuration is invalid");
        }
    }
}
