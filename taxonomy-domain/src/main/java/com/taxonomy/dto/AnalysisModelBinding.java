package com.taxonomy.dto;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Immutable, server-read architecture, catalogue and profile evidence for one accepted analysis. */
public record AnalysisModelBinding(
        State state, String repositoryId, String workspaceScopeKey, String branch, String commit,
        Long semanticRevision, String modelSource, String modelSha256, String catalogueRevision,
        String catalogueDefinitionVersion, Set<String> rootCodes, AnalysisScope scope,
        String briefVersion, Map<String, String> briefFields, List<Reference> references,
        Set<String> excludedRelationTypes, String profileProblem,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String executionCatalogueRevision) {

    public enum State {
        RESOLVED, MODEL_UNCOMMITTED, CATALOGUE_UNRESOLVED, BRIEF_UNASSIGNED, PROFILE_UNRESOLVED
    }

    public record Reference(String id, String sourceId, String sourceVersionId, String sourceFragmentId,
                            Map<String, String> values) {
        public Reference {
            Objects.requireNonNull(id, "reference id");
            Objects.requireNonNull(sourceId, "canonical source id");
            values = orderedMap(values);
        }
    }

    /** Stored bindings created before the execution catalogue was recorded still use their explicit project pin. */
    public AnalysisModelBinding(State state, String repositoryId, String workspaceScopeKey, String branch, String commit,
            Long semanticRevision, String modelSource, String modelSha256, String catalogueRevision,
            String catalogueDefinitionVersion, Set<String> rootCodes, AnalysisScope scope, String briefVersion,
            Map<String,String> briefFields, List<Reference> references, Set<String> excludedRelationTypes,
            String profileProblem) {
        this(state, repositoryId, workspaceScopeKey, branch, commit, semanticRevision, modelSource, modelSha256,
                catalogueRevision, catalogueDefinitionVersion, rootCodes, scope, briefVersion, briefFields,
                references, excludedRelationTypes, profileProblem, null);
    }

    public String analysisCatalogueRevision() {
        return executionCatalogueRevision == null ? catalogueRevision : executionCatalogueRevision;
    }

    public AnalysisModelBinding {
        Objects.requireNonNull(state, "model binding state");
        Objects.requireNonNull(repositoryId, "repository");
        Objects.requireNonNull(workspaceScopeKey, "workspace");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(modelSource, "model source");
        if (modelSha256 == null || !modelSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("An exact model content hash is required");
        if (commit != null && !commit.matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("Expected a full Git commit");
        if (semanticRevision != null && semanticRevision < 0)
            throw new IllegalArgumentException("Invalid semantic model revision");
        if (executionCatalogueRevision != null && (executionCatalogueRevision.isBlank()
                || catalogueRevision != null && !catalogueRevision.equals(executionCatalogueRevision))) {
            throw new IllegalArgumentException("The execution catalogue must honor the recorded project generation");
        }
        rootCodes = orderedSet(rootCodes);
        scope = Objects.requireNonNull(scope, "requested analysis scope");
        briefFields = orderedMap(briefFields);
        references = List.copyOf(references);
        excludedRelationTypes = orderedSet(excludedRelationTypes);
        if (state == State.RESOLVED && (commit == null || catalogueRevision == null || catalogueDefinitionVersion == null
                || rootCodes.isEmpty() || briefVersion == null || profileProblem != null))
            throw new IllegalArgumentException("A resolved binding requires its exact model, catalogue, scope and brief");
    }

    private static Map<String, String> orderedMap(Map<String, String> source) {
        return Collections.unmodifiableMap(new TreeMap<>(Map.copyOf(source)));
    }

    private static Set<String> orderedSet(Set<String> source) {
        return Collections.unmodifiableSet(new TreeSet<>(Set.copyOf(source)));
    }
}
