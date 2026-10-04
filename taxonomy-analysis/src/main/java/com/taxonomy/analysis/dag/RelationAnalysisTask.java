package com.taxonomy.analysis.dag;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Relation work over the results of prerequisite sub-taxonomy tasks. The message
 * references the prerequisite results by task identity instead of copying source
 * trees, scores or prompts.
 *
 * @param targetRoots      roots whose taxonomy data the relation work evaluates
 * @param prerequisiteTasks sub-taxonomy tasks whose persisted results are inputs
 */
public record RelationAnalysisTask(AnalysisEnvelope envelope, List<TaxonomyShardRoot> targetRoots,
                                   List<AnalysisTaskId> prerequisiteTasks)
        implements AnalysisTaskMessage {

    public RelationAnalysisTask {
        Objects.requireNonNull(envelope, "envelope");
        envelope.requireType(AnalysisMessageType.RELATION_ANALYSIS_TASK);
        targetRoots = List.copyOf(new TreeSet<>(Objects.requireNonNull(targetRoots, "targetRoots")));
        prerequisiteTasks = List.copyOf(Objects.requireNonNull(prerequisiteTasks, "prerequisiteTasks"));
        if (envelope.taskType() != AnalysisTaskType.RELATION_ANALYSIS
                || !envelope.taskId().matchesRelation(envelope.operationId(), targetRoots, envelope.schemaVersion())
                || !envelope.roots().equals(targetRoots)) {
            throw new IllegalArgumentException("Relation task identity does not match its target roots");
        }
        var distinctPrerequisites = new HashSet<AnalysisTaskId>();
        for (AnalysisTaskId prerequisite : prerequisiteTasks) {
            if (!prerequisite.operationId().equals(envelope.operationId())) {
                throw new IllegalArgumentException("Relation prerequisite belongs to another operation");
            }
            // Reconstruct the canonical root-task identity, rejecting relation/unknown
            // families, wildcard roots and root sets without duplicating the ID grammar.
            String rootCode = prerequisite.value().substring(prerequisite.value().lastIndexOf(':') + 1);
            var root = TaxonomyShardRoot.of(rootCode);
            if (!AnalysisTaskId.subtaxonomy(envelope.operationId(), root).equals(prerequisite)) {
                throw new IllegalArgumentException("Relation prerequisite must be a sub-taxonomy task");
            }
            if (!distinctPrerequisites.add(prerequisite)) {
                throw new IllegalArgumentException("Relation prerequisites must be distinct");
            }
        }
    }
}
