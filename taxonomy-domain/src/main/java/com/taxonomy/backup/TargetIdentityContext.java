package com.taxonomy.backup;

import java.util.*;

/** Server-observed target identities; must not be supplied by an archive or browser. */
public record TargetIdentityContext(List<Identity> identities) {
    public record Identity(PrincipalId id, String displayName, String email, boolean loginEnabled, Set<IdentityBinding> bindings) {
        public Identity { Objects.requireNonNull(id); bindings = Set.copyOf(bindings); }
    }
    public TargetIdentityContext {
        identities = List.copyOf(identities);
        var ids = new HashSet<PrincipalId>();
        for (var identity : identities) if (!ids.add(identity.id())) throw new IllegalArgumentException("Duplicate target principal");
    }
}
