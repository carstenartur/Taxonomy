package com.taxonomy.dto;

/** Source identity and declared template policy for a scoped pattern answer. */
public record PatternDetectionEvidence(String repositoryId, String workspaceId, String workspaceScopeKey,
        String branch, String commitId, String evidenceAuthority, String readAuthority, String readinessState,
        String templateVersion, int minimumScore, int eligibleAnchors, String completeness) { }
