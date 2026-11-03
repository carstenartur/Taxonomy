package com.taxonomy.composition.architecture;

import java.util.*;

/** Exact retained-source pins; a source is never an editable semantic revision. */
public final class ArchitectureSourceFocusDtos {
    private ArchitectureSourceFocusDtos() { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Source(String kind, String repositoryId, String workspaceScopeKey, String branch,
                         Long version, Long projectId, Long requirementId, Long requirementVersionId, String snapshotId) {
        public Source {
            if (!Set.of("ANALYSIS_DRAFT","ANALYSIS_SNAPSHOT").contains(Objects.toString(kind,"")))
                throw new IllegalArgumentException("Source kind must be ANALYSIS_DRAFT or ANALYSIS_SNAPSHOT");
            for (String pin : Arrays.asList(repositoryId,workspaceScopeKey,branch))
                if (!bounded(pin,255)) throw new IllegalArgumentException("Exact bounded source repository, workspace and branch are required");
            if ("ANALYSIS_DRAFT".equals(kind)) {
                if (version == null || version < 0 || projectId != null || requirementId != null || requirementVersionId != null || snapshotId != null)
                    throw new IllegalArgumentException("A draft requires its exact version and no snapshot pins");
            } else {
                if (version != null || !bounded(snapshotId,255)) throw new IllegalArgumentException("A snapshot requires its exact ID and no draft version");
                for (Long id : Arrays.asList(projectId,requirementId,requirementVersionId))
                    if (id == null || id < 1) throw new IllegalArgumentException("Exact source project, requirement and version are required");
            }
        }
    }
    public record Request(Source source, String sourceId, Integer candidateLimit, Boolean useAi, String language) {
        public Request {
            Objects.requireNonNull(source,"An explicit retained source is required");
            if (!bounded(sourceId,200)) throw new IllegalArgumentException("An actual retained element ID is required");
            candidateLimit = candidateLimit == null ? 8 : candidateLimit;
            if (candidateLimit < 1 || candidateLimit > 24) throw new IllegalArgumentException("candidateLimit must be 1..24");
            useAi = Boolean.TRUE.equals(useAi); language = language == null ? "en" : language;
            if (!Set.of("en","de").contains(language)) throw new IllegalArgumentException("language must be en or de");
        }
    }
    public record Response(Source source, String sourceId, String sourceHash, String method, String outcome,
                           int modelCalls, int candidateCount, List<ArchitectureFocusSuggestionService.Suggestion> suggestions) {
        public Response { suggestions = List.copyOf(suggestions); }
    }
    private static boolean bounded(String value,int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && value.chars().noneMatch(c -> Character.isISOControl(c) || c == 0xfffe || c == 0xffff);
    }
}
