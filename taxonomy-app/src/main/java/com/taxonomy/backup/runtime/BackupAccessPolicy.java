package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

/**
 * Authoritative, current principal/capability/scope decisions, implemented by application security.
 * Implementations must resolve stable bindings, never display names or Git authors, and must not
 * reuse the capability set stored in a backup job. Resource quotas are a separate decision.
 */
public interface BackupAccessPolicy {
    boolean isEnabled(PrincipalId principal);
    boolean hasCapability(PrincipalId principal, BackupCapability capability, BackupScope scope);
    boolean canRead(PrincipalId principal, BackupRepositoryKey repository);
    boolean canReadVersion(PrincipalId principal, BackupRepositoryKey repository, String commit);
}
