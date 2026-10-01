package com.taxonomy.backup;

import java.util.Objects;

/** Length and digest cover the uncompressed UTF-8 or binary entry bytes. */
public record BackupEntry(String path, long length, String sha256) {
    public BackupEntry {
        BackupChecks.text(path, "entry path");
        if (path.startsWith("/") || path.contains("\\") || path.contains(":") || path.endsWith("/")
                || java.util.Arrays.stream(path.split("/", -1)).anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals(".."))) {
            throw new IllegalArgumentException("Unsafe archive path");
        }
        if (length < 0) throw new IllegalArgumentException("Negative entry length");
        BackupChecks.hash(sha256, 64, "sha256");
    }
}
