package com.taxonomy.analysis.dag;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Common, explicitly versioned header of every analysis task and event.
 *
 * <p>The envelope contains stable identifiers and immutable source references
 * only. It never contains credentials, the requirement text or a prompt, and it
 * is never forwarded into an LLM prompt.</p>
 *
 * @param schemaVersion  contract version; consumers reject unknown versions
 * @param messageType    explicit contract discriminator
 * @param operationId    durable analysis operation identity
 * @param taskId         executable unit, or {@code null} for operation-level events
 * @param taskType       task family, or {@code null} for operation-level events
 * @param authority      exact repository/workspace/branch/source-commit identity
 * @param requirement    requirement version reference
 * @param roots          sub-taxonomy roots addressed by this message (may be empty)
 * @param attempt        1-based delivery attempt observed by the producer/consumer
 * @param causationId    message that directly caused this one, or {@code null}
 * @param correlationId  operation-wide correlation identity
 * @param createdAt      creation instant
 * @param deadline       latest useful completion instant, or {@code null}
 */
public record AnalysisEnvelope(
        int schemaVersion,
        AnalysisMessageType messageType,
        String operationId,
        AnalysisTaskId taskId,
        AnalysisTaskType taskType,
        AnalysisSourceAuthority authority,
        RequirementReference requirement,
        List<TaxonomyShardRoot> roots,
        int attempt,
        String causationId,
        String correlationId,
        Instant createdAt,
        Instant deadline) {

    /** Current contract version. Increment for any incompatible change. */
    public static final int SCHEMA_VERSION = 1;

    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9._:+*-]{1,1280}");

    public AnalysisEnvelope {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported analysis message schema version: " + schemaVersion);
        }
        Objects.requireNonNull(messageType, "messageType");
        AnalysisTaskId.requireOperationId(operationId);
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(createdAt, "createdAt");
        roots = roots == null ? List.of() : List.copyOf(roots);
        if (attempt < 1) throw new IllegalArgumentException("attempt must be >= 1");
        if ((taskId == null) != (taskType == null)) {
            throw new IllegalArgumentException("taskId and taskType must be present together");
        }
        if (taskId != null && !taskId.operationId().equals(operationId)) {
            throw new IllegalArgumentException("taskId belongs to another operation");
        }
        requireReference(causationId, "causationId", true);
        requireReference(correlationId, "correlationId", false);
        if (deadline != null && deadline.isBefore(createdAt)) {
            throw new IllegalArgumentException("deadline must not precede createdAt");
        }
    }

    private static void requireReference(String value, String name, boolean nullable) {
        if (value == null) {
            if (nullable) return;
            throw new NullPointerException(name);
        }
        if (!REFERENCE.matcher(value).matches()) throw new IllegalArgumentException("Invalid " + name);
    }

    /** Envelope of a redelivered/retried message: same identities, next attempt. */
    public AnalysisEnvelope nextAttempt() {
        return new AnalysisEnvelope(schemaVersion, messageType, operationId, taskId, taskType, authority,
                requirement, roots, attempt + 1, causationId, correlationId, createdAt, deadline);
    }

    public boolean redelivered() {
        return attempt > 1;
    }

    /** Derive a reply/event header caused by this message, keeping operation identity. */
    public AnalysisEnvelope derive(AnalysisMessageType type, Instant now) {
        return new AnalysisEnvelope(SCHEMA_VERSION, type, operationId, taskId, taskType, authority,
                requirement, roots, 1, taskId == null ? correlationId : taskId.value(), correlationId,
                now, deadline);
    }

    /** Fail closed if a message does not target the expected contract. */
    public void requireType(AnalysisMessageType expected) {
        if (messageType != expected) {
            throw new IllegalArgumentException("Expected " + expected + " but envelope declares " + messageType);
        }
    }
}
