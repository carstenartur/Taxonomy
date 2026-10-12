package com.taxonomy.backup.restore;

import com.taxonomy.backup.PrincipalId;
import java.util.Objects;

/** Caller-approved namespace allocation. This does not authorize target login or grants. */
public record RestorePrincipalAllocation(PrincipalId targetPrincipal,String targetScope,Disposition disposition) {
    public enum Disposition {EXPLICIT_TARGET,QUARANTINE}
    public RestorePrincipalAllocation {
        Objects.requireNonNull(targetPrincipal);Objects.requireNonNull(disposition);
        if(targetScope==null||targetScope.isBlank()||targetScope.length()>255||targetScope.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid restore principal allocation");
    }
}
