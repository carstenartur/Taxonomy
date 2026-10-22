package com.taxonomy.workspace.signing;

/** Cryptographic status, independent from the commit's author/committer identity. */
public enum SignatureStatus {
    UNSIGNED, VERIFIED, UNTRUSTED, INVALID, UNSUPPORTED
}
