package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.dag.AnalysisCompletionMessage;
import com.taxonomy.analysis.dag.AnalysisMessage;
import com.taxonomy.analysis.dag.AnalysisTaskCompletionStore;
import com.taxonomy.analysis.dag.AnalysisTaskHandlers;
import com.taxonomy.analysis.dag.AnalysisTaskMessage;
import com.taxonomy.analysis.dag.AnalysisTaskType;
import com.taxonomy.analysis.dag.AnalysisWorkerShards;
import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import com.taxonomy.analysis.dag.json.AnalysisMessageFormatException;
import jakarta.jms.BytesMessage;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shard worker: consumes the configured roots' task queues in transacted JMS
 * sessions and applies the worker completion protocol of #1161:
 *
 * <ol>
 *   <li>receive inside a local JMS transaction;</li>
 *   <li>decode the versioned contract and verify it belongs to this queue;</li>
 *   <li>answer from the durable completion ledger if the task already ran;</li>
 *   <li>otherwise prepare the result without durable mutations or a database transaction;</li>
 *   <li>atomically reserve the task, persist its result and record completion in the database;</li>
 *   <li>send the completion and commit the delivery in one JMS transaction.</li>
 * </ol>
 *
 * A failure before the commit rolls back the delivery, so the broker redelivers it
 * (bounded by its max-delivery-attempts, then the dead-letter address). A crash
 * after step 5 redelivers, observes the recorded completion and does not repeat
 * durable effects. Malformed, unknown-schema and misrouted messages are moved to
 * the rejected queue immediately and never reach a handler.
 */
public final class ArtemisAnalysisWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ArtemisAnalysisWorker.class);

    /** Test seam between the durable record and the acknowledgement. */
    @FunctionalInterface
    interface AcknowledgementHook {
        void beforeAcknowledge(AnalysisTaskMessage task);
    }

    private record Subscription(Session session, MessageConsumer consumer, MessageProducer producer) { }

    private final ArtemisAnalysisConnection connection;
    private final AnalysisDestinations destinations;
    private final AnalysisWorkerShards shards;
    private final int consumersPerQueue;
    private final AnalysisTaskHandlers handlers;
    private final AnalysisTaskCompletionStore completions;
    private final AnalysisMessageCodec codec;
    private final List<Subscription> subscriptions = new ArrayList<>();
    private final AtomicLong executions = new AtomicLong();
    private final AtomicLong idempotentReplays = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong rolledBack = new AtomicLong();
    private volatile AcknowledgementHook hook = task -> { };
    private boolean started;
    private boolean closed;

    public ArtemisAnalysisWorker(ArtemisAnalysisConnection connection, AnalysisDestinations destinations,
                                 AnalysisWorkerShards shards, int consumersPerQueue, AnalysisTaskHandlers handlers,
                                 AnalysisTaskCompletionStore completions, AnalysisMessageCodec codec) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.destinations = Objects.requireNonNull(destinations, "destinations");
        this.shards = Objects.requireNonNull(shards, "shards");
        if (consumersPerQueue < 1 || consumersPerQueue > 64) {
            throw new IllegalArgumentException("consumersPerQueue must be within 1..64");
        }
        this.consumersPerQueue = consumersPerQueue;
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.completions = Objects.requireNonNull(completions, "completions");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /** Subscribe once the broker connection is usable; idempotent. */
    public synchronized void start() {
        start(true, shards.roots().containsAll(TaxonomyShardRoot.DEFAULT_ROOTS));
    }

    public synchronized void start(boolean rootWork, boolean sharedPreparation) {
        if (started || closed) return;
        started = true;
        try {
            for (TaxonomyShardRoot root : rootWork ? shards.roots() : List.<TaxonomyShardRoot>of()) {
                if (handlers.handles(AnalysisTaskType.SUBTAXONOMY_ANALYSIS)) {
                    subscribe(destinations.subtaxonomy(root), AnalysisTaskType.SUBTAXONOMY_ANALYSIS, root);
                }
                if (handlers.handles(AnalysisTaskType.RELATION_ANALYSIS)) {
                    subscribe(destinations.relation(root), AnalysisTaskType.RELATION_ANALYSIS, root);
                }
            }
            if (handlers.handles(AnalysisTaskType.RELATION_ANALYSIS)
                    && sharedPreparation) {
                // Multi-root relation work needs every root's data: only full-catalogue workers take it.
                subscribe(destinations.generalRelation(), AnalysisTaskType.RELATION_ANALYSIS, null);
            }
        } catch (JMSException | RuntimeException failure) {
            closeSubscriptions();
            started = false;
            throw new IllegalStateException("Analysis worker could not subscribe ("
                    + failure.getClass().getSimpleName() + ")", failure);
        }
        log.info("Analysis worker consuming {} queue(s) for shards {}", subscriptions.size(), shards.roots());
    }

    private void subscribe(String queue, AnalysisTaskType family, TaxonomyShardRoot root) throws JMSException {
        for (int i = 0; i < consumersPerQueue; i++) {
            Session session = connection.createSession(true);
            MessageConsumer consumer = session.createConsumer(session.createQueue(queue));
            MessageProducer producer = session.createProducer(null);
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);
            var subscription = new Subscription(session, consumer, producer);
            subscriptions.add(subscription);
            consumer.setMessageListener(message -> onMessage(subscription, queue, family, root, message));
        }
    }

    private void onMessage(Subscription subscription, String queue, AnalysisTaskType family,
                           TaxonomyShardRoot root, Message message) {
        Session session = subscription.session();
        try {
            AnalysisTaskMessage task;
            try {
                task = accept(codec.decode(ArtemisAnalysisMessages.body(message)), family, root);
            } catch (AnalysisMessageFormatException invalid) {
                reject(subscription, queue, message, invalid.kind().name());
                return;
            }
            task = task.atAttempt(ArtemisAnalysisMessages.deliveryAttempt(message));
            AnalysisCompletionMessage completion;
            var recorded = completions.find(task);
            if (recorded.isPresent()) {
                idempotentReplays.incrementAndGet();
                completion = recorded.get();
            } else {
                completion = handlers.prepareAndCommit(task, completions, executions::incrementAndGet);
            }
            subscription.producer().send(session.createQueue(destinations.completion()),
                    ArtemisAnalysisMessages.encode(session, codec, completion));
            hook.beforeAcknowledge(task);
            session.commit();
        } catch (JMSException | RuntimeException failure) {
            rolledBack.incrementAndGet();
            log.warn("Analysis task delivery rolled back for redelivery ({})", failure.getClass().getSimpleName());
            try {
                session.rollback();
            } catch (JMSException | RuntimeException ignored) {
                // A broken session is recovered by the client; the broker still owns the delivery.
            }
        }
    }

    private static AnalysisTaskMessage accept(AnalysisMessage decoded, AnalysisTaskType family, TaxonomyShardRoot root) {
        if (!(decoded instanceof AnalysisTaskMessage task) || task.taskType() != family
                || !Objects.equals(task.routingRoot(), root)) {
            throw new AnalysisMessageFormatException(AnalysisMessageFormatException.Kind.INVALID_CONTRACT,
                    "message does not belong to this queue", null);
        }
        return task;
    }

    private void reject(Subscription subscription, String queue, Message original, String kind) throws JMSException {
        Session session = subscription.session();
        BytesMessage copy = session.createBytesMessage();
        if (original instanceof BytesMessage bytes) {
            long length = Math.min(bytes.getBodyLength(), AnalysisMessageCodec.MAX_MESSAGE_BYTES);
            byte[] body = new byte[(int) length];
            bytes.reset();
            bytes.readBytes(body);
            copy.writeBytes(body);
        }
        copy.setStringProperty(ArtemisAnalysisMessages.REJECTION, kind);
        copy.setStringProperty(ArtemisAnalysisMessages.ORIGIN, queue);
        subscription.producer().send(session.createQueue(destinations.rejected()), copy);
        session.commit();
        rejected.incrementAndGet();
        log.warn("Rejected analysis message from {} ({})", queue, kind);
    }

    void acknowledgementHook(AcknowledgementHook hook) {
        this.hook = Objects.requireNonNull(hook, "hook");
    }

    public long executions() {
        return executions.get();
    }

    public long idempotentReplays() {
        return idempotentReplays.get();
    }

    public long rejected() {
        return rejected.get();
    }

    public long rolledBack() {
        return rolledBack.get();
    }

    public synchronized int consumerCount() {
        return subscriptions.size();
    }

    private void closeSubscriptions() {
        for (Subscription subscription : subscriptions) {
            try {
                subscription.consumer().close();
                subscription.session().close();
            } catch (JMSException | RuntimeException ignored) {
                // Closing rolls back in-flight deliveries; the broker redelivers them.
            }
        }
        subscriptions.clear();
    }

    @Override
    public synchronized void close() {
        closed = true;
        closeSubscriptions();
    }
}
