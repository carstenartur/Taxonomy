package com.taxonomy.analysis.dag;

import java.util.Objects;

/**
 * A computed result and its short, transactional durable mutation.
 *
 * <p>Preparation (including provider calls) must have no durable effects. The
 * completion store invokes persistEffect only for the winning task, in the SAME
 * database transaction as its completion record. The callback must join that
 * transaction, revalidate current operation authority/cancellation, and must not
 * use REQUIRES_NEW or perform external I/O. Failure rolls back both writes.</p>
 */
public record PreparedAnalysisCompletion<C extends AnalysisCompletionMessage>(C completion, Runnable persistEffect) {
    public PreparedAnalysisCompletion {
        Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(persistEffect, "persistEffect");
    }

    public static <C extends AnalysisCompletionMessage> PreparedAnalysisCompletion<C> withoutEffects(C completion) {
        return new PreparedAnalysisCompletion<>(completion, () -> { });
    }
}
