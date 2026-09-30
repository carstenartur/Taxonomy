package com.taxonomy.backup;

import java.util.Objects;

final class BackupChecks {
    private BackupChecks() { }
    static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || !value.equals(value.strip()) || value.codePointCount(0, value.length()) > BackupLimits.MAX_TEXT_LENGTH || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }
    static void count(int size) {
        if (size > BackupLimits.MAX_ITEMS) throw new IllegalArgumentException("Too many manifest items");
    }
    static String hash(String value, int length, String name) {
        text(value, name);
        if (!value.matches("[0-9a-f]{" + length + "}")) throw new IllegalArgumentException("Invalid " + name);
        return value;
    }
}
