package com.taxonomy.backup;

import java.util.*;

/** Historical source identities confer no target login or authority. */
public record SourceIdentitySet(List<Identity> identities) {
    public record Identity(PrincipalId id, String displayName, String email, Set<IdentityBinding> bindings) {
        public Identity { Objects.requireNonNull(id); bindings = Set.copyOf(bindings); }
    }
    public SourceIdentitySet {
        identities = List.copyOf(identities);
        var ids = new HashSet<PrincipalId>();
        for (var identity : identities) if (!ids.add(identity.id())) throw new IllegalArgumentException("Duplicate source principal");
    }
}
