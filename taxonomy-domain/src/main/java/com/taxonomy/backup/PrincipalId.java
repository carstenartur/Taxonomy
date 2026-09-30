package com.taxonomy.backup;

import java.util.UUID;
import java.util.Objects;

/** Stable application identity; never constructed from a username or e-mail. */
public record PrincipalId(UUID value) {
    public PrincipalId { Objects.requireNonNull(value, "value"); }
    public static PrincipalId create() { return new PrincipalId(UUID.randomUUID()); }
}
