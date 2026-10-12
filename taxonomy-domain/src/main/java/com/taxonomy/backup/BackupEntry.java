package com.taxonomy.backup;

import java.util.Objects;

/** Length and digest cover uncompressed bytes. A trailing slash declares an empty directory. */
public record BackupEntry(String path, long length, String sha256) {
    public static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    public BackupEntry {
        BackupChecks.text(path, "entry path");
        boolean directory = path.endsWith("/");
        String name = directory ? path.substring(0, path.length() - 1) : path;
        if (name.startsWith("/") || name.contains("\\") || name.contains(":")
                || java.util.Arrays.stream(name.split("/", -1)).anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals(".."))) {
            throw new IllegalArgumentException("Unsafe archive path");
        }
        if (length < 0) throw new IllegalArgumentException("Negative entry length");
        BackupChecks.hash(sha256, 64, "sha256");
        if (directory && (length != 0 || !EMPTY_SHA256.equals(sha256)))
            throw new IllegalArgumentException("Directory entries must have empty content");
    }
}
