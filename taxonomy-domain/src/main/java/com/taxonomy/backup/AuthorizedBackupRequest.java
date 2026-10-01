package com.taxonomy.backup;

import java.time.Instant;
import java.util.*;

/** Evidence of a past check, never a bearer token or a substitute for reauthorization. */
public record AuthorizedBackupRequest(BackupRequest request, PrincipalId principalId,
                                      String decisionId, Instant authorizedAt, Set<BackupCapability> capabilities) {
    public AuthorizedBackupRequest {
        Objects.requireNonNull(request); Objects.requireNonNull(principalId); Objects.requireNonNull(authorizedAt);
        BackupChecks.text(decisionId, "decisionId"); capabilities = Set.copyOf(capabilities);
        var required = EnumSet.of(BackupCapability.EXPORT_CURRENT);
        if (request.profile().includesHistory()) required.add(BackupCapability.EXPORT_HISTORY);
        if (request.profile().isInstallation()) required.add(BackupCapability.EXPORT_INSTALLATION);
        if (request.secrets() == SecretsSelection.INCLUDE_ENCRYPTED) required.add(BackupCapability.INCLUDE_SECRETS);
        if (!capabilities.containsAll(required)) throw new IllegalArgumentException("Missing required backup capabilities");
    }
}
