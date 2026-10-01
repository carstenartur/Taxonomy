package com.taxonomy.security.model;

import com.taxonomy.backup.PrincipalId;

/** Implemented only by server-created authenticated identities. */
public interface StablePrincipal {
    PrincipalId principalId();
}
