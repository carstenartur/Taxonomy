package com.taxonomy.analysis.dag;

import java.util.Objects;

/** Immutable task source identity, independent of attempts and completion timestamps. */
public final class AnalysisTaskIdentity {
    private AnalysisTaskIdentity() { }

    public static void requireSameSource(AnalysisEnvelope expected, AnalysisEnvelope actual) {
        if (expected == null || actual == null || expected.taskId() == null
                || expected.schemaVersion() != actual.schemaVersion()
                || !Objects.equals(expected.operationId(), actual.operationId())
                || !Objects.equals(expected.taskId(), actual.taskId())
                || expected.taskType() != actual.taskType()
                || !Objects.equals(expected.authority(), actual.authority())
                || !Objects.equals(expected.requirement(), actual.requirement())
                || !Objects.equals(expected.roots(), actual.roots())
                || !Objects.equals(expected.deadline(), actual.deadline())) {
            throw new IllegalStateException("Analysis task source identity mismatch");
        }
    }
}
