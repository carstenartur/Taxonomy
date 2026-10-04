package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.dag.AnalysisCancellationEvent;
import com.taxonomy.analysis.dag.AnalysisEventPublisher;
import com.taxonomy.analysis.dag.AnalysisMessage;
import com.taxonomy.analysis.dag.AnalysisProgressEvent;
import com.taxonomy.analysis.dag.AnalysisTaskMessage;
import com.taxonomy.analysis.dag.AnalysisTaskPublisher;
import com.taxonomy.analysis.dag.AnalysisTransportUnavailableException;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import jakarta.jms.DeliveryMode;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;

import java.util.Objects;

/**
 * Publishes tasks to their persistent anycast shard queues and live events to
 * non-persistent multicast addresses. A durable send returns only after the
 * broker accepted the message; failures surface as
 * {@link AnalysisTransportUnavailableException} so the dispatch intent stays
 * recoverable.
 */
public final class ArtemisAnalysisTaskPublisher implements AnalysisTaskPublisher, AnalysisEventPublisher {

    private final ArtemisAnalysisConnection connection;
    private final AnalysisDestinations destinations;
    private final AnalysisMessageCodec codec;
    private Session session;
    private MessageProducer producer;

    public ArtemisAnalysisTaskPublisher(ArtemisAnalysisConnection connection, AnalysisDestinations destinations,
                                        AnalysisMessageCodec codec) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.destinations = Objects.requireNonNull(destinations, "destinations");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public void publish(AnalysisTaskMessage task) {
        Objects.requireNonNull(task, "task");
        send(task, destinations.queueFor(task), false, DeliveryMode.PERSISTENT);
    }

    @Override
    public void progress(AnalysisProgressEvent event) {
        sendLive(event, destinations.progress());
    }

    @Override
    public void cancellation(AnalysisCancellationEvent event) {
        sendLive(event, destinations.control());
    }

    private void sendLive(AnalysisMessage event, String address) {
        try {
            send(event, address, true, DeliveryMode.NON_PERSISTENT);
        } catch (AnalysisTransportUnavailableException unavailable) {
            // Live fan-out only accelerates; durable operation state remains the replay authority.
        }
    }

    private synchronized void send(AnalysisMessage message, String address, boolean multicast, int deliveryMode) {
        try {
            Session current = session();
            Destination destination = multicast ? current.createTopic(address) : current.createQueue(address);
            producer.send(destination, ArtemisAnalysisMessages.encode(current, codec, message), deliveryMode,
                    jakarta.jms.Message.DEFAULT_PRIORITY, jakarta.jms.Message.DEFAULT_TIME_TO_LIVE);
        } catch (JMSException | RuntimeException failure) {
            if (failure instanceof com.taxonomy.analysis.dag.json.AnalysisMessageFormatException format) throw format;
            reset();
            if (failure instanceof AnalysisTransportUnavailableException unavailable) throw unavailable;
            throw new AnalysisTransportUnavailableException("Analysis broker did not accept the message", failure);
        }
    }

    private Session session() throws JMSException {
        if (session == null) {
            session = connection.createSession(false);
            producer = session.createProducer(null);
        }
        return session;
    }

    private void reset() {
        try {
            if (session != null) session.close();
        } catch (JMSException | RuntimeException ignored) {
            // A failed session is replaced on the next send.
        }
        session = null;
        producer = null;
    }

    public synchronized void close() {
        reset();
    }
}
