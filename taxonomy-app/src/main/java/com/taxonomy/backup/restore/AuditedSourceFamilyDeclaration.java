package com.taxonomy.backup.restore;

import com.taxonomy.backup.PrincipalId;
import com.taxonomy.backup.SourceDatabaseFamily;
import java.time.Instant;
import java.util.Objects;

/** Private operator evidence for legacy archives that do not contain a captured vendor witness. */
public record AuditedSourceFamilyDeclaration(SourceDatabaseFamily family,String archiveSha256,
        PrincipalId declaredBy,String declaredAt,String reason) {
    public AuditedSourceFamilyDeclaration {
        Objects.requireNonNull(family);Objects.requireNonNull(declaredBy);
        if(archiveSha256==null||!archiveSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid source family declaration archive");
        if(!Instant.parse(declaredAt).toString().equals(declaredAt))throw new IllegalArgumentException("Invalid source family declaration time");
        if(reason==null||reason.isBlank()||reason.length()>4096||reason.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid source family declaration reason");
    }
}
