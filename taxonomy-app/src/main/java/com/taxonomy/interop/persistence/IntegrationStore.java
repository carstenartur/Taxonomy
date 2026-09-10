package com.taxonomy.interop.persistence;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.IntegrationJson;
import com.taxonomy.interop.IntegrationProblem;
import com.taxonomy.workspace.service.RepositoryContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Durable operation authority, shared by every connector. Git and remote transport are separate recoverable phases. */
@Repository
public class IntegrationStore {
    private final EntityManager em;
    private final TransactionTemplate transaction;
    private final IntegrationJson json;
    public IntegrationStore(EntityManagerFactory factory, PlatformTransactionManager manager, IntegrationJson json) {
        this.em = SharedEntityManagerCreator.createSharedEntityManager(factory); this.transaction = new TransactionTemplate(manager); this.json = json;
    }
    public record Connection(UUID id, String organizationId, String displayName, String connectorId, String profileVersion,
                             AuthorityMode authority, ExternalScope externalScope, Long projectId, String remoteProfile,
                             long revision, UUID checkpointId, UUID activeOperationId, String createdBy) {}
    public record Operation(UUID id, UUID connectionId, IntegrationContext context, String direction, OperationStatus status,
                            String fingerprint, long connectionRevision, ExchangeDocument document, List<IntegrationChange> changes,
                            ReviewedChangeSet review, String reviewFingerprint, String resultCommit, Long resultRevision,
                            ExchangeDocument resultDocument, InternalState resultState, @com.fasterxml.jackson.annotation.JsonIgnore ExchangeFile resultFile, String failureCode, Instant createdAt) {}
    public record OperationSummary(UUID id, UUID connectionId, String direction, OperationStatus status, String fingerprint,
                                   String resultCommit, Long resultRevision, String failureCode, Instant createdAt) {}
    public record Identity(String externalId, String businessIdentity, Long requirementId, String externalVersion,
                           String fingerprint, Artifact external, Artifact internal, UUID operationId, boolean removed) {}
    public record Checkpoint(UUID id, UUID operationId, String gitCommit, String externalVersion,
                             String fingerprint, InternalState state, Instant createdAt) {}
    public record Event(String type, String actor, Instant occurredAt, String rationale, String failureCode) {}

    public Connection create(RepositoryContext context, UUID id, String organization, String name, String connector,
                             String profileVersion, AuthorityMode authority, ExternalScope externalScope, Long projectId, String remoteProfile) {
        return transaction.execute(status -> {
            IntegrationConnectionEntity prior = em.find(IntegrationConnectionEntity.class, id.toString());
            if (prior != null) {
                if (!prior.scopeId.equals(scope(context))) throw IntegrationProblem.missing();
                if (!Objects.equals(prior.displayName, name) || !prior.connectorId.equals(connector) || !prior.authorityMode.equals(authority.name())
                        || !Objects.equals(prior.projectId, projectId) || !Objects.equals(prior.remoteProfile, remoteProfile)
                        || !json.read(prior.externalScope, ExternalScope.class).equals(externalScope)) throw IntegrationProblem.conflict("CONNECTION_ID_REUSED");
                return connection(prior);
            }
            var entity = new IntegrationConnectionEntity(); entity.id = id.toString(); entity.scopeId = scope(context);
            entity.repositoryId = context.repositoryId(); entity.organizationId = organization; entity.displayName = name;
            entity.connectorId = connector; entity.profileVersion = profileVersion; entity.authorityMode = authority.name();
            entity.externalScope = json.write(externalScope); entity.projectId = projectId; entity.remoteProfile = remoteProfile;
            entity.createdBy = context.username(); entity.createdAt = Instant.now().toString(); em.persist(entity); em.flush();
            return connection(entity);
        });
    }
    public List<Connection> list(RepositoryContext context) {
        return transaction.execute(status -> em.createQuery("select c from IntegrationConnectionEntity c where c.scopeId=:scope order by c.displayName,c.id", IntegrationConnectionEntity.class)
                .setParameter("scope", scope(context)).setMaxResults(200).getResultList().stream().map(this::connection).toList());
    }
    public Connection read(RepositoryContext context, UUID id) { return transaction.execute(status -> connection(requireConnection(context, id, false))); }
    public <T> T locked(RepositoryContext context, UUID connectionId, Function<Session, T> action) {
        return transaction.execute(status -> { T result = action.apply(new Session(context, requireConnection(context, connectionId, true))); em.flush(); return result; });
    }
    public Operation operation(RepositoryContext context, UUID connectionId, UUID id) { return locked(context, connectionId, session -> session.operation(id)); }
    public List<Identity> identities(RepositoryContext context, UUID connectionId) { return locked(context, connectionId, Session::identities); }
    public List<OperationSummary> history(RepositoryContext context, UUID connectionId) {
        return transaction.execute(status -> {
            requireConnection(context, connectionId, false);
            return em.createQuery("select o.id,o.connectionId,o.direction,o.status,o.fingerprint,o.resultCommit,o.resultRevision,o.failureCode,o.createdAt "
                            + "from IntegrationOperationEntity o where o.scopeId=:scope and o.connectionId=:connection order by o.createdAt desc,o.id", Object[].class)
                    .setParameter("scope", scope(context)).setParameter("connection", connectionId.toString()).setMaxResults(100).getResultList().stream()
                    .map(row -> new OperationSummary(uuid((String) row[0]), uuid((String) row[1]), (String) row[2], OperationStatus.valueOf((String) row[3]),
                            (String) row[4], (String) row[5], row[6] == null ? null : ((Number) row[6]).longValue(), (String) row[7], Instant.parse((String) row[8]))).toList();
        });
    }
    public List<Event> events(RepositoryContext context, UUID connectionId, UUID operationId) {
        return locked(context, connectionId, session -> {
            session.operation(operationId);
            return em.createQuery("select e from IntegrationEventEntity e where e.scopeId=:scope and e.operationId=:operation order by e.occurredAt,e.id", IntegrationEventEntity.class)
                    .setParameter("scope", scope(context)).setParameter("operation", operationId.toString()).getResultList().stream()
                    .map(e -> new Event(e.eventType, e.actor, Instant.parse(e.occurredAt), e.rationale, e.failureCode)).toList();
        });
    }
    public Checkpoint checkpoint(RepositoryContext context, UUID connectionId) {
        return locked(context, connectionId, session -> {
            if (session.connection.checkpointId == null) return null;
            var value = em.find(IntegrationCheckpointEntity.class, session.connection.checkpointId);
            return new Checkpoint(uuid(value.id), uuid(value.operationId), value.gitCommit, value.externalVersion, value.fingerprint,
                    json.read(value.contextJson, InternalState.class), Instant.parse(value.createdAt));
        });
    }

    public final class Session {
        private final RepositoryContext context;
        private final IntegrationConnectionEntity connection;
        private Session(RepositoryContext context, IntegrationConnectionEntity connection) { this.context = context; this.connection = connection; }
        public Connection connection() { return IntegrationStore.this.connection(connection); }
        public Operation operation(UUID id) { return view(requireOperation(id)); }
        public Operation find(UUID id) {
            IntegrationOperationEntity entity = em.find(IntegrationOperationEntity.class, id.toString());
            if (entity == null) return null;
            requireScope(entity); return view(entity);
        }
        public List<Identity> identities() {
            return em.createQuery("select m from ExternalIdentityMappingEntity m where m.scopeId=:scope and m.connectionId=:connection order by m.externalId", ExternalIdentityMappingEntity.class)
                    .setParameter("scope", connection.scopeId).setParameter("connection", connection.id).getResultList().stream().map(IntegrationStore.this::identity).toList();
        }
        public Operation preview(UUID id, IntegrationContext authority, String direction, String fingerprint,
                                 ExchangeDocument document, List<IntegrationChange> changes) {
            Operation existing = find(id);
            if (existing != null) {
                if (!existing.fingerprint.equals(fingerprint)) throw IntegrationProblem.conflict("OPERATION_ID_REUSED");
                return existing;
            }
            if (connection.activeOperationId != null) throw IntegrationProblem.conflict("INTEGRATION_PENDING");
            var entity = new IntegrationOperationEntity(); entity.id = id.toString(); entity.scopeId = connection.scopeId;
            entity.connectionId = connection.id; entity.actor = context.username(); entity.status = OperationStatus.PREVIEWED.name();
            entity.direction = direction; entity.fingerprint = fingerprint; entity.connectionRevision = connection.revision;
            entity.contextJson = json.write(authority); entity.documentJson = json.write(document); entity.changesJson = json.write(changes);
            entity.createdAt = Instant.now().toString(); entity.updatedAt = entity.createdAt; em.persist(entity);
            event(entity, "PREVIEWED", null, null); return view(entity);
        }
        public void beginReview(ReviewedChangeSet review) {
            IntegrationOperationEntity operation = requireOperation(review.operationId());
            if (!operation.fingerprint.equals(review.previewFingerprint())) throw IntegrationProblem.conflict("PREVIEW_CHANGED");
            String fingerprint = json.fingerprint(review);
            if (operation.reviewFingerprint != null && !operation.reviewFingerprint.equals(fingerprint)) throw IntegrationProblem.conflict("REVIEW_ID_REUSED");
            if (!operation.actor.equals(context.username())) throw new IntegrationProblem("REVIEW_ACTOR", 403, "Only the preview actor may apply this review");
            if (!operation.status.equals(OperationStatus.PREVIEWED.name())) throw IntegrationProblem.conflict("OPERATION_STATE");
            if (operation.connectionRevision != connection.revision || (connection.activeOperationId != null && !connection.activeOperationId.equals(operation.id)))
                throw IntegrationProblem.conflict("CHECKPOINT_CHANGED");
            operation.reviewJson = json.write(review); operation.reviewFingerprint = fingerprint;
            connection.activeOperationId = operation.id;
            transition(operation, OperationStatus.APPLYING, "REVIEW_ACCEPTED", review.rationale(), null);
        }
        public void fetching(UUID id) { transition(requireOperation(id), OperationStatus.FETCH_PENDING, "FETCH_REQUESTED", null, null); }
        public void fetched(UUID id, ExchangeDocument document, List<IntegrationChange> changes) {
            var operation = requireOperation(id);
            if (!List.of(OperationStatus.FETCH_PENDING.name(), OperationStatus.FETCH_FAILED.name()).contains(operation.status)) throw IntegrationProblem.conflict("OPERATION_STATE");
            operation.documentJson = json.write(document); operation.changesJson = json.write(changes);
            transition(operation, OperationStatus.PREVIEWED, "FETCH_COMPLETED", null, null);
        }
        public void fetchFailed(UUID id, String code) {
            var operation = requireOperation(id);
            if (operation.status.equals(OperationStatus.FETCH_PENDING.name())) transition(operation, OperationStatus.FETCH_FAILED, "FETCH_FAILED", null, code);
        }
        public void applied(UUID id, InternalState state, ExchangeDocument result, boolean checkpointNeeded) {
            IntegrationOperationEntity operation = requireOperation(id); operation.resultRevision = state.semanticRevision(); operation.resultJson = json.write(result);
            operation.resultStateJson = json.write(state);
            connection.revision++;
            transition(operation, checkpointNeeded ? OperationStatus.CHECKPOINT_PENDING : OperationStatus.APPLIED, "APPLIED", null, null);
        }
        public void mapping(UUID operationId, String externalId, String businessIdentity, Long requirementId,
                            String externalVersion, Artifact external, Artifact internal, boolean removed) {
            String id = identityId(connection.id, externalId);
            ExternalIdentityMappingEntity entity = em.find(ExternalIdentityMappingEntity.class, id);
            boolean created = entity == null;
            if (entity == null) {
                entity = new ExternalIdentityMappingEntity(); entity.id = id; entity.scopeId = connection.scopeId;
                entity.connectionId = connection.id; entity.externalId = externalId;
            }
            entity.businessIdentity = businessIdentity; entity.requirementId = requirementId; entity.externalVersion = externalVersion;
            entity.externalJson = json.write(external); entity.internalJson = json.write(internal);
            entity.fingerprint = json.fingerprint(external); entity.operationId = operationId.toString(); entity.removed = removed;
            if (created) em.persist(entity);
        }
        public void file(UUID operationId, ExchangeFile file) {
            var operation = requireOperation(operationId);
            if (!operation.status.equals(OperationStatus.APPLIED.name())) throw IntegrationProblem.conflict("OPERATION_STATE");
            operation.resultFileJson = json.write(file);
        }
        public void complete(UUID id, InternalState state, String externalVersion, String fingerprint, boolean synchronizedState) {
            IntegrationOperationEntity operation = requireOperation(id);
            if (operation.status.equals(OperationStatus.COMPLETED.name())) return;
            operation.resultCommit = state.commitId(); operation.resultRevision = state.semanticRevision();
            operation.resultStateJson = json.write(state);
            // Delivery of a file is auditable but never a claim that an external server applied it.
            if (synchronizedState) {
                var checkpoint = new IntegrationCheckpointEntity(); checkpoint.id = id.toString(); checkpoint.scopeId = connection.scopeId;
                checkpoint.connectionId = connection.id; checkpoint.operationId = operation.id; checkpoint.contextJson = json.write(state);
                checkpoint.gitCommit = state.commitId(); checkpoint.externalVersion = externalVersion; checkpoint.fingerprint = fingerprint;
                checkpoint.createdAt = Instant.now().toString(); em.persist(checkpoint); connection.checkpointId = checkpoint.id;
            }
            connection.activeOperationId = null; transition(operation, OperationStatus.COMPLETED, synchronizedState ? "CHECKPOINT_COMPLETED" : "FILE_READY", null, null);
        }
        public void cancel(UUID id, String rationale) {
            var operation = requireOperation(id);
            if (!List.of(OperationStatus.PREVIEWED.name(), OperationStatus.FETCH_PENDING.name(), OperationStatus.FETCH_FAILED.name()).contains(operation.status)) throw IntegrationProblem.conflict("CANNOT_CANCEL_APPLIED_OPERATION");
            transition(operation, OperationStatus.CANCELLED, "CANCELLED", rationale, null);
        }
        public void failure(UUID id, String code) {
            var operation = requireOperation(id);
            operation.failureCode = code; event(operation, "ATTEMPT_FAILED", null, code);
            // Preserve the recoverable phase and reservation; a transport failure does not undo accepted data.
        }
        public void checkpointConflict(UUID id) {
            var operation = requireOperation(id);
            if (!operation.status.equals(OperationStatus.CHECKPOINT_PENDING.name())) throw IntegrationProblem.conflict("OPERATION_STATE");
            connection.activeOperationId = null;
            transition(operation, OperationStatus.CONFLICT, "MODEL_APPLIED_CHECKPOINT_CONFLICT", json.read(operation.reviewJson, ReviewedChangeSet.class).rationale(), "CHECKPOINT_CONFLICT");
        }
        private IntegrationOperationEntity requireOperation(UUID id) {
            var entity = em.find(IntegrationOperationEntity.class, id.toString()); if (entity == null) throw IntegrationProblem.missing(); requireScope(entity); return entity;
        }
        private void requireScope(IntegrationOperationEntity entity) {
            if (!entity.scopeId.equals(connection.scopeId) || !entity.connectionId.equals(connection.id)) throw IntegrationProblem.missing();
        }
        private void transition(IntegrationOperationEntity entity, OperationStatus status, String event, String rationale, String code) {
            entity.status = status.name(); entity.updatedAt = Instant.now().toString(); entity.failureCode = code; event(entity, event, rationale, code);
        }
        private void event(IntegrationOperationEntity operation, String type, String rationale, String code) {
            var event = new IntegrationEventEntity(); event.id = UUID.randomUUID().toString(); event.scopeId = connection.scopeId;
            event.connectionId = connection.id; event.operationId = operation.id; event.eventType = type; event.actor = context.username();
            event.occurredAt = Instant.now().toString(); event.rationale = rationale; event.failureCode = code; em.persist(event);
        }
    }

    private IntegrationConnectionEntity requireConnection(RepositoryContext context, UUID id, boolean lock) {
        var query = em.createQuery("select c from IntegrationConnectionEntity c where c.id=:id and c.scopeId=:scope", IntegrationConnectionEntity.class)
                .setParameter("id", id.toString()).setParameter("scope", scope(context));
        if (lock) query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        return query.getResultList().stream().findFirst().orElseThrow(IntegrationProblem::missing);
    }
    private Connection connection(IntegrationConnectionEntity c) {
        return new Connection(uuid(c.id), c.organizationId, c.displayName, c.connectorId, c.profileVersion, AuthorityMode.valueOf(c.authorityMode),
                json.read(c.externalScope, ExternalScope.class), c.projectId, c.remoteProfile, c.revision, uuid(c.checkpointId), uuid(c.activeOperationId), c.createdBy);
    }
    private Operation view(IntegrationOperationEntity o) {
        return new Operation(uuid(o.id), uuid(o.connectionId), json.read(o.contextJson, IntegrationContext.class), o.direction, OperationStatus.valueOf(o.status),
                o.fingerprint, o.connectionRevision, json.read(o.documentJson, ExchangeDocument.class), Arrays.asList(json.read(o.changesJson, IntegrationChange[].class)),
                json.read(o.reviewJson, ReviewedChangeSet.class), o.reviewFingerprint, o.resultCommit, o.resultRevision, json.read(o.resultJson, ExchangeDocument.class),
                json.read(o.resultStateJson, InternalState.class), json.read(o.resultFileJson, ExchangeFile.class), o.failureCode, Instant.parse(o.createdAt));
    }
    private Identity identity(ExternalIdentityMappingEntity m) {
        return new Identity(m.externalId, m.businessIdentity, m.requirementId, m.externalVersion, m.fingerprint,
                json.read(m.externalJson, Artifact.class), json.read(m.internalJson, Artifact.class), uuid(m.operationId), m.removed);
    }
    private static String scope(RepositoryContext context) { return context.repositoryWorkspaceScopeKey(); }
    private static String identityId(String connectionId, String externalId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((connectionId + "\u0000" + externalId).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
}
