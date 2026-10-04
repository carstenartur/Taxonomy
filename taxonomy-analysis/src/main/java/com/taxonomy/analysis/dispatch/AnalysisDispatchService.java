package com.taxonomy.analysis.dispatch;

import com.taxonomy.analysis.dag.AnalysisMessage;
import com.taxonomy.analysis.dag.AnalysisTaskMessage;
import com.taxonomy.analysis.dag.AnalysisTaskPublisher;
import com.taxonomy.analysis.dag.AnalysisTransportUnavailableException;
import com.taxonomy.analysis.dag.json.AnalysisMessageFormatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Closes the database-commit → broker-send gap without making polling the scheduler.
 *
 * <ol>
 *   <li>{@link #dispatch} persists a {@code DISPATCH_PENDING} intent in the
 *       caller's transaction.</li>
 *   <li>After that transaction commits, each task is published immediately and
 *       the intent is acknowledged with a compare-and-set.</li>
 *   <li>A crash between commit, send and acknowledgement leaves a recoverable
 *       intent that only {@link #recover} re-publishes — on startup, explicit
 *       repair or broker reconnection. Duplicate sends are harmless because the
 *       task identity is stable and worker effects are idempotent.</li>
 * </ol>
 *
 * <p>This class has no timer, scheduler or background thread.</p>
 */
public final class AnalysisDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisDispatchService.class);

    /** Result of one publish attempt. */
    public enum PublishOutcome { DISPATCHED, ALREADY_SETTLED, WAITING_FOR_BROKER, DISPATCH_FAILED }

    /** Bounded, content-free summary of one recovery run. */
    public record RecoveryReport(AnalysisDispatchRecoveryTrigger trigger, int scanned, int dispatched,
                                 int waiting, int failed, boolean truncated, boolean brokerUnavailable) { }

    private final AnalysisDispatchStore store;
    private final AnalysisTaskPublisher publisher;
    private final int recoveryBatch;
    private final int recoveryLimit;
    private final AtomicBoolean recovering = new AtomicBoolean();
    private final AtomicReference<AnalysisDispatchRecoveryTrigger> requestedAgain = new AtomicReference<>();

    public AnalysisDispatchService(AnalysisDispatchStore store, AnalysisTaskPublisher publisher,
                                   int recoveryBatch, int recoveryLimit) {
        this.store = Objects.requireNonNull(store, "store");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        if (recoveryBatch < 1 || recoveryLimit < recoveryBatch) {
            throw new IllegalArgumentException("Require 1 <= recoveryBatch <= recoveryLimit");
        }
        this.recoveryBatch = recoveryBatch;
        this.recoveryLimit = recoveryLimit;
    }

    /**
     * Record dispatch intents and publish them once the surrounding transaction
     * commits. Without an active transaction the intents are committed in their own
     * transaction and published immediately.
     */
    public void dispatch(Collection<? extends AnalysisTaskMessage> tasks) {
        Objects.requireNonNull(tasks, "tasks");
        if (tasks.isEmpty()) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            List<String> ids = store.recordIntents(tasks);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishAll(ids);
                }
            });
        } else {
            publishAll(store.recordIntentsInNewTransaction(tasks));
        }
    }

    private void publishAll(List<String> ids) {
        for (String id : ids) {
            try {
                if (publish(id) == PublishOutcome.WAITING_FOR_BROKER) return;
            } catch (RuntimeException unexpected) {
                // Never fail the already committed caller; the intent stays recoverable.
                log.warn("Analysis task dispatch left pending ({}); recovery will retry",
                        unexpected.getClass().getSimpleName());
                return;
            }
        }
    }

    /** Publish one recorded intent and acknowledge it. */
    public PublishOutcome publish(String id) {
        var intent = store.find(id).orElse(null);
        if (intent == null || !intent.status().recoverable()) return PublishOutcome.ALREADY_SETTLED;
        AnalysisMessage message;
        try {
            message = store.decode(intent);
        } catch (AnalysisMessageFormatException invalid) {
            store.markFailed(id, invalid.kind().name());
            return PublishOutcome.DISPATCH_FAILED;
        }
        if (!(message instanceof AnalysisTaskMessage task) || !task.taskId().equals(intent.taskId())) {
            store.markFailed(id, "INVALID_CONTRACT");
            return PublishOutcome.DISPATCH_FAILED;
        }
        try {
            publisher.publish(task);
        } catch (AnalysisTransportUnavailableException unavailable) {
            store.markWaiting(id, "BROKER_UNAVAILABLE");
            return PublishOutcome.WAITING_FOR_BROKER;
        } catch (AnalysisMessageFormatException invalid) {
            store.markFailed(id, invalid.kind().name());
            return PublishOutcome.DISPATCH_FAILED;
        }
        // The broker accepted the task. A crash before this CAS only causes a harmless re-send.
        return store.acknowledge(id) ? PublishOutcome.DISPATCHED : PublishOutcome.ALREADY_SETTLED;
    }

    /**
     * Bounded scan for intents that were committed but not acknowledged. Concurrent
     * triggers coalesce into one additional pass instead of parallel scans.
     */
    public RecoveryReport recover(AnalysisDispatchRecoveryTrigger trigger) {
        Objects.requireNonNull(trigger, "trigger");
        if (!recovering.compareAndSet(false, true)) {
            requestedAgain.set(trigger);
            return new RecoveryReport(trigger, 0, 0, 0, 0, false, false);
        }
        RecoveryReport report;
        try {
            report = scan(trigger);
            AnalysisDispatchRecoveryTrigger again;
            while (!report.brokerUnavailable() && (again = requestedAgain.getAndSet(null)) != null) {
                report = scan(again);
            }
        } finally {
            recovering.set(false);
        }
        if (report.scanned() > 0) {
            log.info("Analysis dispatch recovery ({}): scanned={}, dispatched={}, waiting={}, failed={}, truncated={}",
                    report.trigger(), report.scanned(), report.dispatched(), report.waiting(), report.failed(),
                    report.truncated());
        }
        return report;
    }

    private RecoveryReport scan(AnalysisDispatchRecoveryTrigger trigger) {
        int scanned = 0;
        int dispatched = 0;
        int failed = 0;
        var cursor = AnalysisDispatchStore.Cursor.START;
        while (scanned < recoveryLimit) {
            var page = store.recoverable(cursor, Math.min(recoveryBatch, recoveryLimit - scanned));
            if (page.isEmpty()) return new RecoveryReport(trigger, scanned, dispatched, 0, failed, false, false);
            for (var intent : page) {
                scanned++;
                cursor = new AnalysisDispatchStore.Cursor(intent.createdAt(), intent.id());
                switch (publish(intent.id())) {
                    case DISPATCHED -> dispatched++;
                    case DISPATCH_FAILED -> failed++;
                    case WAITING_FOR_BROKER -> {
                        // Stop early: the next reconnect is the trigger, not a retry loop.
                        return new RecoveryReport(trigger, scanned, dispatched,
                                (int) Math.min(Integer.MAX_VALUE,
                                        store.count(AnalysisDispatchStatus.WAITING_FOR_BROKER)),
                                failed, false, true);
                    }
                    case ALREADY_SETTLED -> { }
                }
            }
        }
        boolean more = !store.recoverable(cursor, 1).isEmpty();
        return new RecoveryReport(trigger, scanned, dispatched, 0, failed, more, false);
    }

    /** Explicit operator/user repair entry point. */
    public RecoveryReport repair() {
        return recover(AnalysisDispatchRecoveryTrigger.EXPLICIT_REPAIR);
    }
}
