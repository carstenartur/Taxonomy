package com.taxonomy.backup;

import org.springframework.security.access.AccessDeniedException;
import java.time.Clock;
import java.util.*;

/**
 * Shared start/status/download policy, registered with the persistent adapter when backup is enabled.
 */
public final class BackupAuthorizationService {
    private final BackupAccessPolicy policy;
    private final Clock clock;

    public BackupAuthorizationService(BackupAccessPolicy policy, Clock clock) {
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
    }

    public void require(PrincipalId principal, BackupCapability capability, BackupScope scope) {
        Objects.requireNonNull(principal); Objects.requireNonNull(capability); Objects.requireNonNull(scope);
        if (!policy.isEnabled(principal) || !policy.hasCapability(principal, capability, scope)) deny();
        for (var selected : scope.selectedRepositories()) {
            if (!policy.canRead(principal, selected)) deny();
        }
    }

    public AuthorizedBackupRequest authorize(PrincipalId principal, BackupRequest request) {
        Objects.requireNonNull(request);
        var required = EnumSet.of(BackupCapability.EXPORT_CURRENT);
        if (request.profile().includesHistory()) required.add(BackupCapability.EXPORT_HISTORY);
        if (request.profile().isInstallation()) required.add(BackupCapability.EXPORT_INSTALLATION);
        if (request.secrets() == SecretsSelection.INCLUDE_ENCRYPTED) required.add(BackupCapability.INCLUDE_SECRETS);
        for (var capability : required) require(principal, capability, request.scope());
        if (request.time() instanceof BackupTime.SelectedVersion version) {
            version.commitsByRepository().forEach((repository, commit) -> {
                if (!policy.canReadVersion(principal, repository, commit)) deny();
            });
        }
        return new AuthorizedBackupRequest(request, principal, UUID.randomUUID().toString(), clock.instant(), required);
    }

    /** Job metadata, including scope and filenames, is also private after revocation. */
    public void requireJobAccess(PrincipalId requester, AuthorizedBackupRequest creation) {
        Objects.requireNonNull(creation);
        if (!creation.principalId().equals(requester)) deny();
        authorize(requester, creation.request());
    }

    public void requireDownload(PrincipalId requester, AuthorizedBackupRequest creation) {
        requireJobAccess(requester, creation);
        require(requester, BackupCapability.DOWNLOAD_BACKUP, creation.request().scope());
    }

    private static void deny() { throw new AccessDeniedException("Backup access denied"); }
}
