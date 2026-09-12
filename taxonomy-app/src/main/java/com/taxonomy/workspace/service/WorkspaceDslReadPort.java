package com.taxonomy.workspace.service;

import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Workspace-owned, read-only access to an explicitly selected repository branch.
 * It exposes neither storage handles nor JGit types and never resolves implicit
 * current-user/current-workspace state.
 */
public interface WorkspaceDslReadPort {

    /** Binds one factory-owned repository handle; the caller must not close it. */
    RepositoryRead openRead(RepositoryContext context);

    /** Canonicalizes a full object ID without accepting refs, abbreviations or whitespace. */
    static String normalizeCommitId(String value) {
        if (value == null || value.length() != 40) {
            throw new IllegalArgumentException("commitId must be a full Git object ID");
        }
        HexFormat.of().parseHex(value);
        return value.toLowerCase(Locale.ROOT);
    }

    interface RepositoryRead {
        /** The selected branch's current full commit ID, or null if absent. */
        String currentHead() throws IOException;

        /**
         * Reads only the exact full commit ID, never the latest branch content.
         * An empty Optional means that the existing commit has no DSL file;
         * Optional.of("") is a valid empty file. Missing commits fail with IOException.
         * Reading a commit does not prove it is still the selected branch head.
         */
        Optional<String> dslAtCommit(String commitId) throws IOException;

        /**
         * Checks the expected head with the existing atomic no-change ref update,
         * without appending history. Null means an expected absent branch.
         */
        String verifyExpectedHead(String expectedHeadCommit) throws IOException;

        /** Reads immutable metadata from the exact commit, without consulting the branch head. */
        CommitMetadata commitMetadata(String commitId) throws IOException;

        /**
         * Classifies each candidate relative to the exact descendant commit, in
         * input order (including duplicates). SAME and ANCESTOR are positive
         * reachability proofs; UNRELATED and UNAVAILABLE are not.
         * A missing, invalid or unreadable descendant fails the whole operation.
         * A malformed, missing or unreadable candidate is UNAVAILABLE, never a
         * successful proof. No history or repository handles escape this call.
         */
        List<CommitRelationship> relationshipsTo(String descendantCommitId,
                                                List<String> candidateCommitIds) throws IOException;
    }

    /** Detached immutable evidence; knowledge-specific authority checks remain with the caller. */
    record CommitMetadata(String summary, String author, String message, List<String> parents) {
        public CommitMetadata {
            summary = Objects.requireNonNull(summary, "summary");
            author = Objects.requireNonNull(author, "author");
            message = Objects.requireNonNull(message, "message");
            parents = List.copyOf(parents);
        }
    }

    enum CommitRelationship { SAME, ANCESTOR, UNRELATED, UNAVAILABLE }

}
