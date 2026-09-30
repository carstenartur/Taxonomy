package com.taxonomy.backup;

import java.util.Objects;

final class BackupChecks {
    private BackupChecks() { }
    static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || !value.equals(value.strip()) || value.length() > 512 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }
    static String hash(String value, int length, String name) {
        text(value, name);
        if (!value.matches("[0-9a-f]{" + length + "}")) throw new IllegalArgumentException("Invalid " + name);
        return value;
    }
}
