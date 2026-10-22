package com.taxonomy.workspace.storage;

import com.taxonomy.workspace.signing.CommitSignature;
import com.taxonomy.workspace.signing.SignatureStatus;

import java.time.Instant;

/**
 * Immutable DTO representing a single DSL commit in the Git history.
 *
 * @param commitId  the full SHA-1 hex string of the commit object
 * @param author    the author identity (name &lt;email&gt;)
 * @param timestamp when the commit was created
 * @param message   the commit message
 * @param signature the verification result for the stored Git object
 */
public record DslCommit(
        String commitId,
        String author,
        Instant timestamp,
        String message,
        CommitSignature signature
) {
    public DslCommit {
        if (signature == null) {
            signature = new CommitSignature(SignatureStatus.UNSIGNED, null, null, false, null);
        }
    }

    /** Retains source compatibility for legacy callers and unsigned history. */
    public DslCommit(String commitId, String author, Instant timestamp, String message) {
        this(commitId, author, timestamp, message, null);
    }
}
