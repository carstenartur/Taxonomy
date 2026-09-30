package com.taxonomy.backup;

public record BackupComponentId(String value) {
    public BackupComponentId {
        BackupChecks.text(value, "componentId");
        if (!value.matches("[a-z][a-z0-9-]{0,63}")) throw new IllegalArgumentException("Invalid componentId");
    }
}
