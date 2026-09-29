package com.taxonomy.analysis.service;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Process-local admission shared by live analysis paths. No prompts, credentials,
 * worker threads or durable-job copies are stored here. Existing executors own
 * execution; only workers which have reached admission participate in scheduling.
 */
public final class AnalysisAdmissionQueue {
    public enum State { QUEUED, RUNNING, CLOSED }
    public enum Rejection { GLOBAL_QUEUE_FULL, OWNER_QUEUE_FULL }

    public record Limits(int maxRunning, int maxQueued, int maxRunningPerOwner, int maxQueuedPerOwner) {
        public Limits {
            requireRange(maxRunning, 1, 64, "maxRunning");
            requireRange(maxQueued, 1, 10_000, "maxQueued");
            requireRange(maxRunningPerOwner, 1, maxRunning, "maxRunningPerOwner");
            requireRange(maxQueuedPerOwner, 1, maxQueued, "maxQueuedPerOwner");
        }
        private static void requireRange(int value, int minimum, int maximum, String name) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
            }
        }
    }

    public static final class CapacityException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Rejection reason;
        private CapacityException(Rejection reason) {
            super(reason.name());
            this.reason = reason;
        }
        public Rejection reason() { return reason; }
    }

    private static final class Owner {
        private final ArrayDeque<Ticket> waiting = new ArrayDeque<>();
        private int running;
    }

    private final Limits limits;
    // Insertion order is the ready owners' round-robin order, not a public queue rank.
    private final Map<String, Owner> owners = new LinkedHashMap<>();
    private int running;
    private int queued;

    public AnalysisAdmissionQueue(Limits limits) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
    }

    public synchronized Ticket reserve(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("owner is required");
        if (queued >= limits.maxQueued()) throw new CapacityException(Rejection.GLOBAL_QUEUE_FULL);
        Owner owner = owners.get(ownerId);
        if (owner != null && owner.waiting.size() >= limits.maxQueuedPerOwner()) {
            throw new CapacityException(Rejection.OWNER_QUEUE_FULL);
        }
        if (owner == null) {
            owner = new Owner();
            owners.put(ownerId, owner);
        }
        Ticket ticket = new Ticket(ownerId, owner);
        owner.waiting.addLast(ticket);
        queued++;
        return ticket;
    }

    public synchronized int running() { return running; }
    public synchronized int queued() { return queued; }
    synchronized int ownerCount() { return owners.size(); }

    private Ticket nextReady() {
        if (running >= limits.maxRunning()) return null;
        for (Owner owner : owners.values()) {
            if (owner.running >= limits.maxRunningPerOwner()) continue;
            for (Ticket ticket : owner.waiting) {
                if (ticket.ready) return ticket;
            }
        }
        return null;
    }

    public final class Ticket implements AutoCloseable {
        private final String ownerId;
        private final Owner owner;
        private State state = State.QUEUED;
        private boolean ready;

        private Ticket(String ownerId, Owner owner) {
            this.ownerId = ownerId;
            this.owner = owner;
        }

        public State state() {
            synchronized (AnalysisAdmissionQueue.this) { return state; }
        }

        /** Nonblocking: the caller supplies cancellable waiting and its operation deadline. */
        public boolean tryStart() {
            synchronized (AnalysisAdmissionQueue.this) {
                if (state != State.QUEUED) return state == State.RUNNING;
                ready = true;
                if (nextReady() != this) return false;
                owner.waiting.remove(this);
                queued--;
                owner.running++;
                running++;
                state = State.RUNNING;
                owners.remove(ownerId);
                owners.put(ownerId, owner);
                return true;
            }
        }

        /** Cancellation before start frees backlog immediately; completion releases one permit. */
        @Override public void close() {
            synchronized (AnalysisAdmissionQueue.this) {
                if (state == State.CLOSED) return;
                if (state == State.QUEUED) {
                    owner.waiting.remove(this);
                    queued--;
                } else {
                    owner.running--;
                    running--;
                }
                state = State.CLOSED;
                if (owner.running == 0 && owner.waiting.isEmpty()) owners.remove(ownerId);
            }
        }
    }
}
