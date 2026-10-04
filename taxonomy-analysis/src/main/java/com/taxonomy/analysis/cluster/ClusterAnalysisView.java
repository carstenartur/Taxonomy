package com.taxonomy.analysis.cluster;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonValue;
import com.taxonomy.analysis.dag.AnalysisTaskType;
import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import com.taxonomy.analysis.service.AnalysisProgressRegistry;

import java.util.List;
import java.util.Objects;

/** Compatible live telemetry with durable cluster counts and immutable source identity. */
public record ClusterAnalysisView(@JsonUnwrapped AnalysisProgressRegistry.Snapshot snapshot,
                                  Transport transport, ObservationScope scope, TaskSummary cluster) {
    public ClusterAnalysisView {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(cluster, "cluster");
    }

    public enum Transport {
        ARTEMIS;

        @JsonValue public String value() { return "artemis"; }
    }

    public record ObservationScope(String workspaceId, String repositoryId, String branch, String sourceCommit) { }

    public record TaskSummary(int completedRoots, int totalRoots, List<TaskCounts> tasks) {
        public TaskSummary {
            if (completedRoots < 0 || totalRoots < completedRoots) {
                throw new IllegalArgumentException("Invalid root progress counts");
            }
            tasks = List.copyOf(tasks);
        }
    }

    /** A null root identifies shared relation preparation before target work is derived. */
    public record TaskCounts(AnalysisTaskType taskType, TaxonomyShardRoot root,
                             int queued, int running, int completed, int failed) {
        public TaskCounts {
            Objects.requireNonNull(taskType, "taskType");
            if (queued < 0 || running < 0 || completed < 0 || failed < 0) {
                throw new IllegalArgumentException("Task progress counts must be non-negative");
            }
        }
    }
}
