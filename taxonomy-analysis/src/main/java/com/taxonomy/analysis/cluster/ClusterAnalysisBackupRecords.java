package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.backup.AnalysisBackupContributor.ResumePolicy;
import com.taxonomy.analysis.dag.AnalysisOperationContext;
import com.taxonomy.backup.SourceRecordId;
import java.util.List;

/** Explicit archive DTOs. Source IDs name archive-local evidence, never target database keys. */
public final class ClusterAnalysisBackupRecords {
    private ClusterAnalysisBackupRecords() { }
    public record Run(SourceRecordId sourceId, String principalScope, AnalysisOperationContext context,
                      String commandJson, String viewJson, String resultJson, String relationPlanJson,
                      String sourceState, ResumePolicy resumePolicy, int totalRoots, int completedRoots,
                      long revision, long createdAt, long updatedAt) { }
    public record Work(SourceRecordId sourceId, SourceRecordId run, String taskId, String taskType,
                       String root, int ordinal, String messageJson, String inputJson, String resultJson,
                       String failureReason, String sourceState, boolean settled, int attempts,
                       Long startedAt, Long finishedAt, String progressJson) {
        public Work(SourceRecordId sourceId, SourceRecordId run, String taskId, String taskType, String root, int ordinal,
                    String messageJson, String inputJson, String resultJson, String failureReason, String sourceState,
                    boolean settled, int attempts, Long startedAt, Long finishedAt) {
            this(sourceId, run, taskId, taskType, root, ordinal, messageJson, inputJson, resultJson, failureReason,
                    sourceState, settled, attempts, startedAt, finishedAt, null);
        }
    }
    public record Input(SourceRecordId sourceId, SourceRecordId run, String root, String inputJson) { }
    public record Event(SourceRecordId sourceId, SourceRecordId run, long revision, String eventJson) { }
    public record Call(SourceRecordId sourceId, SourceRecordId run, String taskId, long callNumber,
                       String executionId, int logicalOrdinal, int deliveryAttempt, String provider, String node,
                       String state, long startedAt, long durationMillis, long eventRevision,
                       String prompt, String response, String error, int promptLength, int responseLength) { }
    public record Attempt(SourceRecordId sourceId, SourceRecordId run, SourceRecordId call, String attemptId,
                          String invocationId, String provider, String source, int retryIndex, long startedAt,
                          String observationJson) { }
    public record Archive(Run run, List<Work> work, List<Input> inputs, List<Event> events, List<Call> calls, List<Attempt> attempts) {
        public Archive { work = List.copyOf(work); inputs = List.copyOf(inputs); events = List.copyOf(events);
            calls = calls == null ? List.of() : List.copyOf(calls); attempts = attempts == null ? List.of() : List.copyOf(attempts); }
        public Archive(Run run, List<Work> work, List<Input> inputs, List<Event> events) { this(run, work, inputs, events, List.of(), List.of()); }
    }
}
