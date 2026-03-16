package com.taxonomy.shared.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
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
public class AppInitializationStateService {

    public enum State {
        STARTING,
        LOADING_TAXONOMY,
        BUILDING_INDEX,
        READY,
        FAILED
    }

    /** Immutable snapshot of the current initialisation state. */
    public record StateSnapshot(State state, String message, Instant updatedAt, String error) {}

    private final AtomicReference<StateSnapshot> snapshot =
            new AtomicReference<>(new StateSnapshot(State.STARTING, "Starting application", Instant.now(), null));

    public State getState() {
        return snapshot.get().state();
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
        snapshot.set(new StateSnapshot(newState, newMessage, Instant.now(), null));
    }

    public void fail(String newMessage, Throwable t) {
        snapshot.set(new StateSnapshot(State.FAILED, newMessage, Instant.now(),
                t == null ? null : t.getMessage()));
    }
}
