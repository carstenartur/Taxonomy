package com.taxonomy.portfolio.service;

import com.taxonomy.workspace.service.WorkspaceContext;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Project-level binding of the analysis/reformulation provider.
 *
 * <p>Ordinary projects follow the configured provider policy. A bound project
 * may use only the provider its binding admits for the exact requirement
 * versions; there is no fallback to a live, mock or globally selected
 * provider. The portfolio context owns this contract; implementations are
 * contributed by the composition root.</p>
 */
@FunctionalInterface
public interface ProjectProviderBinding {

    /** Exact requirement version targeted by analysis or reformulation. */
    record Target(Long requirementId, Long requirementVersionId) {
        public Target {
            Objects.requireNonNull(requirementId, "requirementId");
            Objects.requireNonNull(requirementVersionId, "requirementVersionId");
        }
    }

    /**
     * Provider admitted for the targets of a bound project, or empty for an ordinary project.
     *
     * <p>Must be called inside the caller's write transaction. Implementations hold
     * a write lock on their binding until that transaction ends, so concurrent
     * binding changes serialize with the admission. Violations raise a
     * {@link PortfolioException} with a stable code.</p>
     *
     * @param requestedProvider provider requested by the client, {@code null} when none was requested
     */
    Optional<String> admittedProvider(Long projectId, List<Target> targets, String requestedProvider,
                                      String username, WorkspaceContext context);

    /** Null object: no project is bound. */
    ProjectProviderBinding NONE = (projectId, targets, requestedProvider, username, context) -> Optional.empty();
}
