package com.taxonomy.security.model;

import com.taxonomy.backup.PrincipalId;
import java.util.Objects;

/** Durable identity and its explicitly assigned compatibility scope, independent of a login name. */
public record AppPrincipal(PrincipalId id, String scopeKey, boolean enabled) {
    public AppPrincipal {
        Objects.requireNonNull(id);
        if (scopeKey == null || scopeKey.isBlank()) throw new IllegalArgumentException("Principal scope is required");
    }
}
