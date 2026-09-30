package com.taxonomy.backup;

import java.util.Objects;

/** Authenticated provider key. Display names and e-mail addresses are never keys. */
public record IdentityBinding(Kind kind, String issuer, String subject) {
    public enum Kind { LOCAL, OIDC, DIRECTORY }
    public IdentityBinding {
        Objects.requireNonNull(kind);
        BackupChecks.text(issuer, "identity issuer");
        BackupChecks.text(subject, "identity subject");
    }
}
