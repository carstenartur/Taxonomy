package com.taxonomy.reformulation;

import com.taxonomy.identity.StableIdentityHash;
import java.util.Map;
import java.util.Objects;

/** Exact immutable source and context CONTENT; no framework or active-version authority. */
public record ReformulationBaseline(Scope scope, long sourceVersionId, String originalText,
        String originalTextHash, String snapshotId, String snapshotPayload,
        Map<String,String> frozenContext, String language, String algorithmVersion) {
    public ReformulationBaseline {
        Objects.requireNonNull(scope, "scope");
        if (sourceVersionId <= 0) throw new IllegalArgumentException("sourceVersionId must be positive");
        Objects.requireNonNull(originalText, "originalText");
        if (!StableIdentityHash.sha256(originalText).equals(originalTextHash))
            throw new IllegalArgumentException("Original text hash does not match exact source");
        requireText(snapshotId,"snapshotId"); requireText(snapshotPayload,"snapshotPayload");
        frozenContext = Map.copyOf(frozenContext);
        if (!"de".equals(language) && !"en".equals(language)) throw new IllegalArgumentException("language must be de or en");
        requireText(algorithmVersion,"algorithmVersion");
    }
    public static ReformulationBaseline freeze(Source source, Snapshot snapshot, Map<String,String> content,
            String language, String algorithmVersion) {
        if (!source.scope().equals(snapshot.scope()) || source.versionId() != snapshot.sourceVersionId())
            throw new IllegalArgumentException("Snapshot must match the exact requirement, version and scope");
        return new ReformulationBaseline(source.scope(),source.versionId(),source.text(),StableIdentityHash.sha256(source.text()),
                snapshot.id(),snapshot.payload(),content,language,algorithmVersion);
    }
    public record Scope(String repositoryId, String workspaceId, String branch, long projectId, long requirementId) {
        public Scope {
            requireText(repositoryId,"repositoryId"); requireText(branch,"branch");
            if (workspaceId != null) requireText(workspaceId,"workspaceId");
            if (projectId <= 0 || requirementId <= 0) throw new IllegalArgumentException("Project and requirement IDs must be positive");
        }
    }
    public record Source(Scope scope, long versionId, String text) {
        public Source { Objects.requireNonNull(scope); Objects.requireNonNull(text); if(versionId<=0) throw new IllegalArgumentException("versionId"); }
    }
    public record Snapshot(Scope scope, String id, long sourceVersionId, String payload) {
        public Snapshot { Objects.requireNonNull(scope); requireText(id,"snapshotId"); requireText(payload,"payload"); }
    }
    static String requireText(String value,String field) {
        if(value==null || value.isBlank()) throw new IllegalArgumentException(field+" is required"); return value;
    }
}
