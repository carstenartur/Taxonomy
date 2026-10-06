package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.cluster.ClusterAnalysisSignals;
import com.taxonomy.analysis.cluster.ClusterAnalysisStore;
import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import jakarta.jms.Session;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Durable completion consumers and ephemeral per-pod observation. Never polls the database. */
public final class ArtemisClusterCoordinator implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ArtemisClusterCoordinator.class);
    private record Subscription(Session session, MessageConsumer consumer, MessageProducer producer) { }
    private final ArtemisAnalysisConnection connection;
    private final AnalysisDestinations destinations;
    private final AnalysisMessageCodec codec;
    private final ClusterAnalysisStore store;
    private final AnalysisTaskCompletionStore ledger;
    private final ClusterAnalysisSignals signals;
    private final boolean coordinator;
    private final List<Subscription> subscriptions = new ArrayList<>();
    private final AtomicLong accepted = new AtomicLong(), failures = new AtomicLong(), redeliveries = new AtomicLong();
    private boolean started, closed;
    private volatile java.util.function.Consumer<AnalysisOperationContext> finalizer = context -> { };
    private volatile ArtemisAnalysisMetrics metrics;
    public void metrics(ArtemisAnalysisMetrics metrics) { this.metrics = metrics; }

    public ArtemisClusterCoordinator(ArtemisAnalysisConnection connection, AnalysisDestinations destinations,
            AnalysisMessageCodec codec, ClusterAnalysisStore store, AnalysisTaskCompletionStore ledger,
            ClusterAnalysisSignals signals, boolean coordinator) {
        this.connection = connection; this.destinations = destinations; this.codec = codec;
        this.store = store; this.ledger = ledger; this.signals = signals; this.coordinator = coordinator;
        signals.workerReconciliation(store::terminal);
    }

    public void attach() {
        connection.addListener(reconnect -> { start(); if (reconnect) signals.reconnected(); });
    }
    public void finalizer(java.util.function.Consumer<AnalysisOperationContext> finalizer) {
        this.finalizer = java.util.Objects.requireNonNull(finalizer);
    }

    public synchronized void start() {
        if (closed || started) return;
        try {
            live(destinations.progress()); live(destinations.control());
            if (coordinator) {
                durable(destinations.completion(), null);
                durable(destinations.deadLetter(), "BROKER_DEAD_LETTER");
                durable(destinations.expiry(), "BROKER_EXPIRED");
            }
            started = true;
            signals.reconnected();
        } catch (JMSException | RuntimeException failure) {
            closeSubscriptions();
            throw new IllegalStateException("Could not subscribe clustered analysis coordinator", failure);
        }
    }

    private void live(String name) throws JMSException {
        Session session = connection.createSession(false);
        MessageConsumer consumer = session.createConsumer(session.createTopic(name));
        subscriptions.add(new Subscription(session, consumer, null));
        consumer.setMessageListener(message -> {
            try {
                var decoded = codec.decode(ArtemisAnalysisMessages.body(message));
                if (name.equals(destinations.progress()) && decoded instanceof AnalysisProgressEvent event) signals.progress(event);
                else if (name.equals(destinations.control()) && decoded instanceof AnalysisCancellationEvent event) signals.cancellation(event);
            } catch (JMSException | RuntimeException invalid) {
                log.debug("Discarded invalid live analysis event ({})", invalid.getClass().getSimpleName());
            }
        });
    }

    private void durable(String queue, String failureReason) throws JMSException {
        Session session = connection.createSession(true);
        MessageConsumer consumer = session.createConsumer(session.createQueue(queue));
        MessageProducer producer = session.createProducer(null); producer.setDeliveryMode(DeliveryMode.PERSISTENT);
        var subscription = new Subscription(session, consumer, producer); subscriptions.add(subscription);
        consumer.setMessageListener(message -> receive(subscription, queue, failureReason, message));
    }

    private void receive(Subscription subscription, String queue, String failureReason, Message message) {
        try {
            AnalysisMessage decoded = null;
            AnalysisOperationContext finalizeContext = null;
            try {
                decoded = codec.decode(ArtemisAnalysisMessages.body(message));
                if (decoded instanceof AnalysisCompletionMessage completion) {
                    accept(completion);
                    finalizeContext = ClusterAnalysisStore.context(completion.envelope());
                } else if (failureReason != null && decoded instanceof AnalysisTaskMessage task) {
                    var completion = ledger.find(task).orElseGet(() -> {
                        var factory = new AnalysisMessageFactory(ClusterAnalysisStore.context(task.envelope()), Clock.systemUTC());
                        AnalysisCompletionMessage failed = task instanceof SubtaxonomyAnalysisTask root
                                ? factory.completed(root, AnalysisTaskOutcome.FAILED, null, 0, null)
                                : factory.completed((RelationAnalysisTask) task, AnalysisTaskOutcome.FAILED, 0, null);
                        return ledger.commit(new PreparedAnalysisCompletion<>(failed, () -> store.persistFailure(task, failureReason)));
                    });
                    accept(completion);
                    finalizeContext = ClusterAnalysisStore.context(completion.envelope());
                    diagnostic(subscription, queue, decoded, failureReason);
                } else diagnostic(subscription, queue, decoded, "INVALID_CONTRACT");
            } catch (IllegalArgumentException | IllegalStateException invalid) {
                // Unknown/foreign references can never establish operation authority. Database
                // availability/transaction exceptions escape to rollback and broker redelivery.
                diagnostic(subscription, queue, decoded, "INVALID_REFERENCE");
            }
            // Database acceptance has committed. Slow rendering runs outside its transaction;
            // if this pod dies, the unacknowledged completion repeats this idempotent step.
            if (finalizeContext != null) {
                finalizer.accept(finalizeContext);
                // Repeat the persisted revision even when acceptance/finalization was already
                // committed by a failed coordinator. Fan-out and completion acknowledgement
                // share this JMS transaction, so a lost notification cannot strand observers
                // on another still-connected pod. Send failures must remain retryable.
                var event = store.latestEvent(finalizeContext);
                var session = subscription.session();
                subscription.producer().send(session.createTopic(destinations.progress()),
                        ArtemisAnalysisMessages.encode(session, codec, event));
            }
            subscription.session().commit();
        } catch (JMSException | RuntimeException failure) {
            redeliveries.incrementAndGet();
            log.warn("Analysis coordination rolled back ({})", failure.getClass().getSimpleName());
            try { subscription.session().rollback(); } catch (JMSException | RuntimeException ignored) { }
        }
    }

    private void accept(AnalysisCompletionMessage completion) {
        Runnable action = () -> { if (store.accept(completion)) accepted.incrementAndGet(); };
        if (metrics == null) action.run(); else metrics.coordination(completion, action);
    }

    /** Deliberately contains only validated identifiers, never the original message body. */
    private void diagnostic(Subscription subscription, String queue, AnalysisMessage original, String reason) throws JMSException {
        var session = subscription.session(); Message message = session.createMessage();
        message.setStringProperty(ArtemisAnalysisMessages.ORIGIN, queue);
        message.setStringProperty(ArtemisAnalysisMessages.REJECTION, reason);
        if (original != null) {
            message.setStringProperty("operationId", original.envelope().operationId());
            if (original.envelope().taskId() != null) message.setStringProperty("taskId", original.envelope().taskId().value());
        }
        subscription.producer().send(session.createQueue(destinations.failed()), message); failures.incrementAndGet();
    }

    public long accepted() { return accepted.get(); }
    public long failures() { return failures.get(); }
    public long redeliveries() { return redeliveries.get(); }
    private void closeSubscriptions() {
        for (var subscription : subscriptions) try { subscription.session().close(); }
        catch (JMSException | RuntimeException ignored) { }
        subscriptions.clear();
    }
    @Override public synchronized void close() { closed = true; closeSubscriptions(); }
}
