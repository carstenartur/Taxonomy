package com.taxonomy.catalog.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Centralises application initialisation state tracking.
 *
 * <p>The state machine follows this happy path:
 * {@code STARTING} → {@code LOADING_TAXONOMY} → {@code BUILDING_INDEX} → {@code READY}
 *
 * <p>Any phase can transition to {@code FAILED} via {@link #fail(String, Throwable)}.
 *
 * <p>All mutable fields are wrapped in an immutable {@link StateSnapshot} and stored in a
 * single {@link AtomicReference} to guarantee a consistent view across threads.
 */
@Service
public class AppInitializationStateService
        implements ApplicationEventPublisherAware {
    private static final Logger log = LoggerFactory.getLogger(AppInitializationStateService.class);

    public enum State {
        STARTING,
        LOADING_TAXONOMY,
        BUILDING_INDEX,
        READY,
        FAILED
    }

    /** Immutable snapshot of the current initialisation state. */
    public record StateSnapshot(
            State state,
            String message,
            Instant updatedAt,
            String error,
            String phase,
            long processed,
            long total) {
        public StateSnapshot(State state, String message, Instant updatedAt, String error) {
            this(state, message, updatedAt, error, defaultPhase(state), 0, -1);
        }
    }

    /** Emitted synchronously after the application enters the READY state. */
    public record InitializationReadyEvent(StateSnapshot snapshot) {
        public InitializationReadyEvent {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /** Terminal failure signal; dependent startup tasks must not poll or infer success. */
    public record InitializationFailedEvent(StateSnapshot snapshot) {
        public InitializationFailedEvent {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    private final AtomicReference<StateSnapshot> snapshot =
            new AtomicReference<>(new StateSnapshot(
                    State.STARTING,
                    "Starting application",
                    Instant.now(),
                    null));

    private volatile ApplicationEventPublisher eventPublisher;

    @Override
    public void setApplicationEventPublisher(
            ApplicationEventPublisher applicationEventPublisher) {
        this.eventPublisher = Objects.requireNonNull(
                applicationEventPublisher,
                "applicationEventPublisher");
    }

    public State getState() {
        return snapshot.get().state();
    }

    /** One consistent observation for status responses; total=-1 means indeterminate work. */
    public StateSnapshot getSnapshot() {
        return snapshot.get();
    }

    public String getMessage() {
        return snapshot.get().message();
    }

    public Instant getUpdatedAt() {
        return snapshot.get().updatedAt();
    }

    public String getError() {
        return snapshot.get().error();
    }

    public boolean isReady() {
        return snapshot.get().state() == State.READY;
    }

    public void update(State newState, String newMessage) {
        updateProgress(newState, defaultPhase(newState), newMessage, 0, -1);
    }

    public void updateProgress(State newState, String phase, String newMessage, long processed, long total) {
        if (processed < 0 || total < -1) {
            throw new IllegalArgumentException("Initialization progress cannot be negative");
        }
        StateSnapshot updated = new StateSnapshot(
                Objects.requireNonNull(newState),
                newMessage,
                Instant.now(),
                null, Objects.requireNonNull(phase), processed, total);
        snapshot.set(updated);

        if (newState == State.READY) {
            publish(new InitializationReadyEvent(updated));
        }
    }

    public void fail(String newMessage, Throwable t) {
        StateSnapshot previous = snapshot.get();
        StateSnapshot failed = new StateSnapshot(
                State.FAILED,
                newMessage,
                Instant.now(),
                t == null ? null : "INITIALIZATION_FAILED",
                previous.phase(), previous.processed(), previous.total());
        snapshot.set(failed);
        publish(new InitializationFailedEvent(failed));
    }

    private void publish(Object event) {
        ApplicationEventPublisher publisher = eventPublisher;
        if (publisher == null) return;
        try {
            publisher.publishEvent(event);
        } catch (RuntimeException exception) {
            // Catalogue/index readiness is already decided. A dependent listener cannot
            // turn a verified index into FAILED or disclose private exception messages.
            log.error("Initialization completion listener failed (code=INITIALIZATION_LISTENER_FAILED)");
        }
    }

    private static String defaultPhase(State state) {
        return switch (state) {
            case LOADING_TAXONOMY -> "LOADING_CATALOGUE";
            case BUILDING_INDEX -> "CHECKING_NODE_INDEX";
            default -> state.name();
        };
    }
}
