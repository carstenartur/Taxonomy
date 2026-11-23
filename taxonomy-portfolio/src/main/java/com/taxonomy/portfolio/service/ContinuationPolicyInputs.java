package com.taxonomy.portfolio.service;

import com.taxonomy.dto.AnalysisResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable snapshot fields consulted by continuation policy. */
record ContinuationPolicyInputs(String status, Recovery recovery, String errorMessage, List<String> warnings,
                                RelationEvidence relation, boolean modelBound) {
    ContinuationPolicyInputs {
        // Historical warning arrays can contain null. Policy decides whether to inspect them.
        warnings = warnings == null ? null : Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    record Recovery(String id, long version, String state, List<OpenQuestion> openQuestions) {
        Recovery { openQuestions = List.copyOf(openQuestions); }
    }

    record OpenQuestion(String key, String error, boolean skipped, boolean outcomeUncertain) { }

    record RelationEvidence(String stopReason, List<Source> sources, List<Unfinished> unfinished, List<String> warnings) {
        RelationEvidence {
            sources = List.copyOf(sources);
            unfinished = List.copyOf(unfinished);
            warnings = List.copyOf(warnings);
        }
    }

    record Source(String nodeId, String question) { }

    record Unfinished(String reason, String question) { }

    static ContinuationPolicyInputs from(AnalysisResult analysis) {
        if (analysis == null) return null;
        var recovery = analysis.getRecovery();
        var report = analysis.getRelationSearchReport();
        return new ContinuationPolicyInputs(analysis.getStatus(),
                recovery == null ? null : new Recovery(recovery.id(), recovery.version(), recovery.state(),
                        recovery.openQuestions().stream().map(question -> new OpenQuestion(
                                question.key(), question.error(), question.skipped(), question.outcomeUncertain())).toList()),
                analysis.getErrorMessage(), analysis.getWarnings(),
                report == null ? null : new RelationEvidence(report.stopReason(),
                        report.sources().stream().map(source -> new Source(
                                source.node() == null ? null : source.node().id(), source.question())).toList(),
                        report.result().unfinished().stream().map(work -> new Unfinished(work.reason(), work.question())).toList(),
                        report.warnings()),
                analysis.getModelBinding() != null);
    }

    /** Conservative accounting for strings, record headers, list storage and references. */
    long retainedBytes() {
        long bytes = 128L + stringBytes(status) + stringBytes(errorMessage) + stringsBytes(warnings);
        if (recovery != null) {
            bytes += 192L + stringBytes(recovery.id()) + stringBytes(recovery.state());
            for (var question : recovery.openQuestions()) {
                bytes += 80L + stringBytes(question.key()) + stringBytes(question.error());
            }
        }
        if (relation != null) {
            bytes += 320L + stringBytes(relation.stopReason()) + stringsBytes(relation.warnings());
            for (var source : relation.sources()) {
                bytes += 80L + stringBytes(source.nodeId()) + stringBytes(source.question());
            }
            for (var work : relation.unfinished()) {
                bytes += 80L + stringBytes(work.reason()) + stringBytes(work.question());
            }
        }
        return bytes;
    }

    private static long stringsBytes(List<String> strings) {
        if (strings == null) return 0;
        long bytes = 128L;
        for (String value : strings) bytes += 16L + stringBytes(value);
        return bytes;
    }

    static long stringBytes(String value) {
        return value == null ? 0 : 64L + 2L * value.length();
    }
}
