package com.taxonomy.extension.api.inventory;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import javax.net.ssl.SSLContext;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/** Source observations, never canonical architecture, suitability decisions or deletion authority. */
public final class InventoryReadContracts {
    private InventoryReadContracts() { }
    public enum ObjectKind { APPLICATION, SERVICE, TECHNOLOGY_PRODUCT, DEPLOYMENT, OTHER }
    public enum State { PRESENT, NULL, ABSENT, NOT_SUPPORTED, UNAVAILABLE }
    public enum Completeness { SELECTED_SCOPE_READ_NON_ATOMIC }
    public enum AssertionKind { DECLARED, PLANNED, OBSERVED }

    public record ExternalKey(String instance, String namespace, String id) {
        public ExternalKey { text(instance, 200); text(namespace, 200); text(id, 2048); }
    }
    /** Raw field observation. Parsing a date or deciding suitability belongs to the shared consumer. */
    public record Field(State state, String value, String sourceField) {
        public Field {
            Objects.requireNonNull(state); InventoryReadContracts.text(sourceField, 256);
            if ((state == State.PRESENT) != (value != null)) throw new IllegalArgumentException("Inconsistent field state");
            if (value != null && value.length() > 65536) throw new IllegalArgumentException("Oversized field");
        }
        public static Field text(String value, String sourceField) { return new Field(State.PRESENT, value, sourceField); }
        public static Field missing(State state, String sourceField) { return new Field(state, null, sourceField); }
    }
    public record TypeProjection(String sourceType, ObjectKind defaultKind, String subtypeField,
                                 Map<String, ObjectKind> subtypeKinds, Map<String, String> textFields) {
        public TypeProjection {
            text(sourceType, 200); Objects.requireNonNull(defaultKind);
            subtypeKinds = Map.copyOf(subtypeKinds); textFields = Map.copyOf(textFields);
            if (subtypeKinds.size() > 32 || textFields.size() > 8) throw new IllegalArgumentException("Projection limit");
            if (subtypeField != null) text(subtypeField, 200);
            if (subtypeField == null && !subtypeKinds.isEmpty()) throw new IllegalArgumentException("Missing discriminator field");
            subtypeKinds.keySet().forEach(v -> text(v, 200));
            textFields.forEach((k, v) -> { text(k, 100); text(v, 200); });
        }
        public ObjectKind classify(Field subtype) {
            if (subtypeField == null) return defaultKind;
            return subtype.state() == State.PRESENT ? subtypeKinds.getOrDefault(subtype.value(), ObjectKind.OTHER) : ObjectKind.OTHER;
        }
    }
    /** All limits cover the whole invocation, including authentication and all relation pages. */
    public record Limits(int pageSize, int maxRequests, int maxObjects, int maxRelations,
                         int maxResponseBytes, int maxTotalBytes, int timeoutSeconds) {
        public Limits {
            bound(pageSize, 1, 500); bound(maxRequests, 2, 500);
            bound(maxObjects, 1, 10000); bound(maxRelations, 1, 20000);
            bound(maxResponseBytes, 1024, 2 * 1024 * 1024);
            bound(maxTotalBytes, maxResponseBytes, 16 * 1024 * 1024); bound(timeoutSeconds, 1, 120);
        }
        public static Limits defaults() { return new Limits(100, 100, 2000, 5000, 1048576, 8388608, 60); }
    }
    /** Trusted administrative request. It must not be deserialized from arbitrary browser input. */
    public record Request(IntegrationContext context, String instanceId, String configurationRevision, URI origin,
                          List<TypeProjection> projections, Set<String> selectedIds,
                          boolean readUsageRelations, Limits limits) {
        public Request {
            Objects.requireNonNull(context); text(instanceId, 200); text(configurationRevision, 200); Objects.requireNonNull(origin); Objects.requireNonNull(limits);
            if (context.authority() != AuthorityMode.LINK_ONLY) throw new IllegalArgumentException("Inventory reads require LINK_ONLY");
            projections = List.copyOf(projections); selectedIds = Set.copyOf(selectedIds);
            if (projections.isEmpty() || projections.size() > 16 || selectedIds.size() > 200) throw new IllegalArgumentException("Selection limit");
            if (projections.stream().map(TypeProjection::sourceType).distinct().count() != projections.size())
                throw new IllegalArgumentException("Duplicate source type");
            selectedIds.forEach(v -> text(v, 2048));
        }
    }
    /** Host grants one already-authorized source credential, not arbitrary secret-store access.
     * The plugin wipes the returned owned array. TLS permits the host's enterprise CA configuration. */
    @FunctionalInterface public interface ReadAccess {
        char[] credential();
        default SSLContext tls() {
            try { return SSLContext.getDefault(); }
            catch (java.security.NoSuchAlgorithmException e) { throw new ReadFailure("TLS_UNAVAILABLE"); }
        }
        default boolean allowPrivateNetworks() { return false; }
    }
    public record LifecyclePhase(Field phase, Field startDate, AssertionKind assertionKind) {
        public LifecyclePhase { Objects.requireNonNull(phase); Objects.requireNonNull(startDate); Objects.requireNonNull(assertionKind); }
    }
    public record ObservedObject(ExternalKey key, String sourceType, Field subtype, ObjectKind kind,
                                 String revision, Map<String, Field> fields, State lifecycleState,
                                 List<LifecyclePhase> lifecycle) {
        public ObservedObject {
            Objects.requireNonNull(key); text(sourceType, 200); Objects.requireNonNull(subtype); Objects.requireNonNull(kind);
            if (revision != null) text(revision, 200);
            fields = Map.copyOf(fields); Objects.requireNonNull(lifecycleState); lifecycle = List.copyOf(lifecycle);
            if (lifecycleState != State.PRESENT && !lifecycle.isEmpty()) throw new IllegalArgumentException("Inconsistent lifecycle state");
        }
    }
    /** observationId is local to this observation. A source without a durable relation ID uses key=null. */
    public record ObservedRelation(String observationId, ExternalKey key, ExternalKey source, ExternalKey target,
                                   String sourceType, String meaning, Map<String, Field> fields) {
        public ObservedRelation {
            text(observationId, 2048); Objects.requireNonNull(source); Objects.requireNonNull(target);
            text(sourceType, 200); text(meaning, 100); fields = Map.copyOf(fields);
        }
    }
    public record Observation(Request request, Instant startedAt, Instant finishedAt,
                              Completeness completeness, int requests, long bytes,
                              List<ObservedObject> objects, List<ObservedRelation> relations, Set<String> limitations) {
        public Observation {
            Objects.requireNonNull(request); Objects.requireNonNull(startedAt); Objects.requireNonNull(finishedAt);
            Objects.requireNonNull(completeness); objects = List.copyOf(objects); relations = List.copyOf(relations);
            limitations = Set.copyOf(limitations);
            if (requests < 0 || bytes < 0 || finishedAt.isBefore(startedAt)) throw new IllegalArgumentException("Invalid observation bounds");
        }
    }
    /** Codes only; never chain transport exceptions containing headers or response bodies. */
    public static final class ReadFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        public ReadFailure(String code) {
            super("Inventory read failed; previous evidence remains unchanged (" + safeCode(code) + ")");
            this.code = code;
        }
        public String code() { return code; }
        private static String safeCode(String code) {
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,79}")) throw new IllegalArgumentException("Invalid failure code");
            return code;
        }
    }
    private static void bound(int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException("Read budget outside bounds");
    }
    private static void text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid inventory metadata");
    }
}
