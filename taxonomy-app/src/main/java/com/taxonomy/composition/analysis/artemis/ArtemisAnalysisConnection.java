package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.dag.AnalysisTransportUnavailableException;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.Session;
import org.apache.activemq.artemis.api.core.client.FailoverEventType;
import org.apache.activemq.artemis.jms.client.ActiveMQConnection;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One shared, self-reconnecting JMS connection to an external Artemis broker.
 *
 * <p>The connection is opened on a background thread so application startup
 * never blocks on the broker. Artemis' own failover/reconnect logic re-attaches
 * sessions and consumers; this class only reports the transitions so that
 * dispatch recovery runs on <em>events</em> — first connect and reconnect —
 * rather than on a timer.</p>
 */
public final class ArtemisAnalysisConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ArtemisAnalysisConnection.class);

    /** Observable connection state; contains no broker address or credential. */
    public enum State { CONNECTING, CONNECTED, RECONNECTING, FAILED, CLOSED }

    /** Callback after the connection is usable. */
    @FunctionalInterface
    public interface Listener {
        /** @param reconnect {@code false} for the first connection, {@code true} after a detected failure */
        void connected(boolean reconnect);
    }

    private final ActiveMQConnectionFactory factory;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile State state = State.CONNECTING;
    private volatile Connection connection;
    private volatile Thread connector;

    public ArtemisAnalysisConnection(ActiveMQConnectionFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        if (state == State.CONNECTED) listener.connected(false);
    }

    /** Start connecting in the background; returns immediately. */
    public synchronized void start() {
        if (connector != null || state == State.CLOSED) return;
        connector = Thread.ofPlatform().daemon().name("taxonomy-analysis-broker-connect").start(this::connect);
    }

    private void connect() {
        try {
            Connection opened = factory.createConnection();
            ((ActiveMQConnection) opened).setFailoverListener(this::failover);
            // Artemis reports transient failures here asynchronously, possibly after the reconnect
            // completed; the failover events above are the authoritative state transitions.
            opened.setExceptionListener(failure -> log.debug("Analysis broker connection exception ({})",
                    failure.getClass().getSimpleName()));
            opened.start();
            synchronized (this) {
                if (state == State.CLOSED) {
                    opened.close();
                    return;
                }
                connection = opened;
                state = State.CONNECTED;
            }
            log.info("Analysis broker connection established");
            notifyListeners(false);
        } catch (JMSException | RuntimeException failure) {
            if (state != State.CLOSED) {
                state = State.FAILED;
                log.warn("Analysis broker connection could not be established ({})",
                        failure.getClass().getSimpleName());
            }
        }
    }

    private void failover(FailoverEventType type) {
        if (state == State.CLOSED) return;
        switch (type) {
            case FAILURE_DETECTED -> {
                state = State.RECONNECTING;
                log.warn("Analysis broker connection lost; reconnecting");
            }
            case FAILOVER_COMPLETED -> {
                state = State.CONNECTED;
                log.info("Analysis broker connection re-established");
                // Listener work (e.g. dispatch recovery) must not run on the client's failover thread.
                Thread.ofPlatform().daemon().name("taxonomy-analysis-broker-reconnected")
                        .start(() -> notifyListeners(true));
            }
            case FAILOVER_FAILED -> {
                state = State.FAILED;
                log.warn("Analysis broker reconnection failed");
            }
        }
    }

    private void notifyListeners(boolean reconnect) {
        for (Listener listener : listeners) {
            try {
                listener.connected(reconnect);
            } catch (RuntimeException failure) {
                log.warn("Analysis broker connection listener failed ({})", failure.getClass().getSimpleName());
            }
        }
    }

    public State state() {
        return state;
    }

    public boolean connected() {
        return state == State.CONNECTED;
    }

    /** Create a session, failing fast while the broker is not reachable. */
    public Session createSession(boolean transacted) {
        Connection current = connection;
        if (current == null || state != State.CONNECTED) {
            throw new AnalysisTransportUnavailableException("Analysis broker is not connected", null);
        }
        try {
            return transacted ? current.createSession(true, Session.SESSION_TRANSACTED)
                    : current.createSession(false, Session.AUTO_ACKNOWLEDGE);
        } catch (JMSException failure) {
            throw new AnalysisTransportUnavailableException("Analysis broker session unavailable", failure);
        }
    }

    @Override
    public void close() {
        Connection current;
        synchronized (this) {
            state = State.CLOSED;
            current = connection;
            connection = null;
        }
        try {
            if (current != null) current.close();
        } catch (JMSException ignored) {
            // Closing is best effort; the broker redelivers unacknowledged work.
        } finally {
            factory.close();
            Thread pending = connector;
            if (pending != null) pending.interrupt();
        }
    }
}
