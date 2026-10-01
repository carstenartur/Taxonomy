package com.taxonomy.backup;

/** Version-one wire limits. Domain values must remain readable after serialization. */
public final class BackupLimits {
    private BackupLimits() { }
    public static final int MAX_TEXT_LENGTH = 512;
    public static final int MAX_ITEMS = 10_000;
    public static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
}
