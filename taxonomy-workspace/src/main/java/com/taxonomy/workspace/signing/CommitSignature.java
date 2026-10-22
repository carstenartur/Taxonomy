package com.taxonomy.workspace.signing;

/** Public verification result. Never contains private key material or credentials. */
public record CommitSignature(SignatureStatus status, String signer, String fingerprint,
                              boolean serviceSignature, String reason) {
    public static CommitSignature unsigned() {
        return new CommitSignature(SignatureStatus.UNSIGNED, null, null, false, "Commit is unsigned");
    }
}
