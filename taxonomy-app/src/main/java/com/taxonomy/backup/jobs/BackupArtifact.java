package com.taxonomy.backup.jobs;

import java.util.UUID;

/** Immutable result location is generated from job, claim and attempt, never from user input. */
public record BackupArtifact(String name, long length, String sha256, boolean encrypted) {
    public BackupArtifact {
        if (name == null || !name.matches("[0-9a-f-]{36}-[0-9a-f-]{36}-[1-9][0-9]{0,9}\\.taxbackup")
                || length < 1 || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid backup artifact metadata");
        if (!UUID.fromString(name.substring(0, 36)).toString().equals(name.substring(0, 36))
                || !UUID.fromString(name.substring(37, 73)).toString().equals(name.substring(37, 73)))
            throw new IllegalArgumentException("Invalid backup artifact identity");
    }
}
