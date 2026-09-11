package com.taxonomy.interop;

import com.taxonomy.dsl.command.ArchitectureCommand;
import com.taxonomy.dsl.command.ArchitectureCommand.StoreExchangeEvidence;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.exchange.ArchiMateExchangeCodec;
import com.taxonomy.exchange.ExchangeXml;
import com.taxonomy.exchange.ReqifExchangeCodec;
import com.taxonomy.exchange.OslcRequirementsCodec;
import com.taxonomy.interop.oslc.OslcTransport;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.persistence.IntegrationStore;
import com.taxonomy.interop.persistence.IntegrationStore.*;
import com.taxonomy.workspace.service.*;
import com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort.CommandMetadata;
import com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort.State;
import com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort.WorkspaceDocument;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Exact-scope reviewed operations. The ORM commit precedes the independently retryable Git checkpoint. */
@Service
public class IntegrationService {
    private final IntegrationStore store;
    private final ExchangeConnectorRegistry connectors;
    private final IntegrationDomainAdapter domain;
    private final WorkspaceArchitectureIntegrationPort editor;
    private final IntegrationDiff diff;
    private final IntegrationJson json;
    private final SystemRepositoryService repositories;
    private final RepositoryMembershipService memberships;
    private final OslcTransport remote;
    private final WorkspaceAccessService workspaceAccess;

    public IntegrationService(IntegrationStore store, ExchangeConnectorRegistry connectors, IntegrationDomainAdapter domain,
                              WorkspaceArchitectureIntegrationPort editor, IntegrationDiff diff, IntegrationJson json,
                              SystemRepositoryService repositories, RepositoryMembershipService memberships, OslcTransport remote, WorkspaceAccessService workspaceAccess) {
        this.store = store; this.connectors = connectors; this.domain = domain; this.editor = editor;
        this.diff = diff; this.json = json; this.repositories = repositories; this.memberships = memberships;
        this.remote = remote;
        this.workspaceAccess = workspaceAccess;
    }
    public record CreateConnection(UUID id, String name, String connectorId, AuthorityMode authority,
                                   ExternalScope externalScope, Long projectId, String remoteProfile) {}
    public record PreviewRequest(UUID operationId, InternalState expected, String mediaType, boolean completeScope) {}
    public record ExportRequest(UUID operationId, InternalState expected, String expectedExternalVersion) {}
    public record RemoteRequest(UUID operationId, InternalState expected, String resource, String expectedExternalVersion) {}
    public record Overview(Connection connection, InternalState current, Checkpoint checkpoint, List<OperationSummary> history, String oslcCatalogPath) {}

    public List<IntegrationDescriptor> profiles(RepositoryContext context) { authorize(context, false); return connectors.descriptors(); }
    public List<Connection> connections(RepositoryContext context) { authorize(context, false); return store.list(context); }
    public Connection create(RepositoryContext context, CreateConnection request) {
        authorize(context, true);
        if (request.id() == null || request.authority() == null || request.externalScope() == null)
            throw new IllegalArgumentException("Connection identity, authority and external scope are required");
        if (request.name() == null || request.name().isBlank() || request.name().length() > 160)
            throw new IllegalArgumentException("Connection name must have 1–160 characters");
        var descriptor = connectors.require(request.connectorId()).descriptor();
        if (!Set.of(AuthorityMode.LINK_ONLY, AuthorityMode.PUBLISH_TARGET).contains(request.authority())
                && !descriptor.capabilities().contains(Capability.FILE_IMPORT) && !descriptor.capabilities().contains(Capability.READ_LINK))
            throw new IllegalArgumentException("Profile has no inbound capability");
        if (Set.of(ReqifExchangeCodec.PROFILE, OslcRequirementsCodec.PROFILE).contains(request.connectorId())) {
            if (request.projectId() == null) throw new IllegalArgumentException("ReqIF requires a project");
            domain.requireProject(context, request.projectId());
        }
        if (OslcRequirementsCodec.PROFILE.equals(request.connectorId()) && (request.remoteProfile() == null || !request.remoteProfile().matches("[A-Za-z0-9_-]{1,100}")))
            throw new IllegalArgumentException("OSLC requires a configured remote profile");
        if (ArchiMateExchangeCodec.PROFILE.equals(request.connectorId()) && request.projectId() != null) throw new IllegalArgumentException("ArchiMate targets a workspace model, not a requirements project");
        if (request.projectId() != null) domain.requireProject(context, request.projectId());
        var repository = repositories.getRepository(context.repositoryId());
        return store.create(context, request.id(), repository.getOwnerType() + ":" + repository.getOwnerId(), request.name(), descriptor.id(),
                descriptor.version(), request.authority(), request.externalScope(), request.projectId(), request.remoteProfile());
    }
    public Overview overview(RepositoryContext context, UUID connectionId) {
        authorize(context, false); Connection connection = store.read(context, connectionId);
        return new Overview(connection, domain.snapshot(context, connection, store.identities(context, connectionId), read(context)).state(),
                store.checkpoint(context, connectionId), store.history(context, connectionId), "/oslc/scopes/" + context.repositoryWorkspaceScopeKey()
                + "/catalog?repositoryId=" + encode(context.repositoryId()) + "&workspaceId=" + encode(context.workspaceId()) + "&branch=" + encode(context.branch()));
    }
    public Operation operation(RepositoryContext context, UUID connectionId, UUID operationId) {
        authorize(context, false); return store.operation(context, connectionId, operationId);
    }
    public List<Event> events(RepositoryContext context, UUID connectionId, UUID operationId) {
        authorize(context, false); return store.events(context, connectionId, operationId);
    }
    public List<Identity> identities(RepositoryContext context, UUID connectionId) {
        authorize(context, false); return store.identities(context, connectionId);
    }
    public Operation preview(RepositoryContext context, UUID connectionId, PreviewRequest request, byte[] content) {
        authorize(context, true);
        if (request.operationId() == null || request.expected() == null) throw precondition();
        if (content.length > ExchangeXml.MAX_BYTES) throw new IntegrationProblem("PAYLOAD_LIMIT", 413, "Exchange exceeds 16 MiB");
        Connection connection = store.read(context, connectionId); requireProfile(connection);
        if (connection.authority() == AuthorityMode.PUBLISH_TARGET) throw new IntegrationProblem("AUTHORITY_MODE", 409, "Publish targets cannot import");
        var connector = connectors.require(connection.connectorId());
        if (!connector.descriptor().capabilities().contains(Capability.FILE_IMPORT)
                || !connector.descriptor().mediaTypes().contains(request.mediaType())) throw new IntegrationProblem("UNSUPPORTED_MEDIA", 415, "Profile does not accept this media type");
        String fingerprint = json.fingerprint(Arrays.asList(connection.id(), context.username(), request, ReqifExchangeCodec.digest(content)));
        // Retry uses the frozen request, even after the accepted operation advanced the workspace.
        Operation prior = store.locked(context, connectionId, session -> session.find(request.operationId()));
        if (prior != null) return replay(prior, fingerprint);
        IntegrationContext authority = authority(context, connection, request.expected());
        ExchangeDocument document = connector.previewInbound(new InboundRequest(authority, request.mediaType(), content,
                ReqifExchangeCodec.digest(content), request.completeScope()));
        return store.locked(context, connectionId, session -> {
            Operation existing = session.find(request.operationId()); if (existing != null) return replay(existing, fingerprint);
            var state = domain.snapshot(context, session.connection(), session.identities(), read(context));
            expect(request.expected(), state.state());
            return session.preview(request.operationId(), authority, "INBOUND", fingerprint, document,
                    diff.compare(document, connection.authority(), session.identities(), state.items()));
        });
    }

    public Operation apply(RepositoryContext context, UUID connectionId, ReviewedChangeSet review) {
        authorize(context, true);
        Operation result = store.locked(context, connectionId, session -> {
            Connection connection = session.connection(); requireProfile(connection);
            Operation operation = session.operation(review.operationId());
            requireActor(context, operation);
            if (operation.reviewFingerprint() != null) {
                if (!operation.reviewFingerprint().equals(json.fingerprint(review))) throw IntegrationProblem.conflict("REVIEW_ID_REUSED");
                return operation;
            }
            if (!"INBOUND".equals(operation.direction())) throw IntegrationProblem.conflict("OPERATION_DIRECTION");
            return workspace(context, before -> {
            domain.lockProject(context, connection.projectId());
            List<Identity> mappings = session.identities();
            var current = domain.snapshot(context, connection, mappings, before);
            expect(operation.context().internalState(), current.state());
            Map<String, Identity> known = new TreeMap<>(); mappings.forEach(m -> known.put(m.externalId(), m));
            Map<String, Artifact> selected = select(operation, review, current.items(), known);
            ExchangeDocument resultDocument = ExchangeItems.expand(operation.document(), selected);
            boolean linked = connection.authority() == AuthorityMode.LINK_ONLY;
            boolean changed = !semanticItems(current.items()).equals(semanticItems(selected));
            // Validate the reviewed dependency closure and schema before mutating any canonical data.
            if (!linked && changed) connectors.require(connection.connectorId()).previewOutbound(new OutboundRequest(operation.context(), resultDocument, operation.document().externalVersion()));
            List<ArchitectureCommand> commands = new ArrayList<>();
            if (!linked && changed && connection.connectorId().equals(ArchiMateExchangeCodec.PROFILE)) {
                commands.addAll(domain.architectureCommands(connection, before.dsl(), current.items(), selected, mappings));
                String preview = before.dsl();
                for (ArchitectureCommand command : commands) preview = new ArchitectureDslCommands().apply(preview, command).dsl();
            }
            session.beginReview(review);
            for (IntegrationChange change : operation.changes()) {
                Decision decision = review.decisions().get(change.id());
                if (decision != Decision.ACCEPT && decision != Decision.TAKE_EXTERNAL) continue;
                Artifact value = selected.get(change.externalId()); Identity previous = known.get(change.externalId());
                String business = previous == null ? domain.businessId(connection, null, change.after()) : previous.businessIdentity();
                Long requirementId = previous == null ? null : previous.requirementId();
                MappingOverride mapping = review.mappings().get(change.id());
                if (linked && mapping != null && mapping.internalIdentity() != null) {
                    var target = domain.linkTarget(context, connection, before.dsl(), mapping.internalIdentity());
                    business = target.businessIdentity(); requirementId = target.requirementId();
                }
                if (!linked && (value != null ? value.kind() == ArtifactKind.REQUIREMENT : previous != null && previous.internal().kind() == ArtifactKind.REQUIREMENT)) {
                    var applied = domain.applyRequirement(context, connection, value, previous, review.rationale());
                    business = applied.businessIdentity(); requirementId = applied.requirementId();
                } else if (!linked && value != null && value.kind() == ArtifactKind.RELATION && connection.connectorId().equals(ArchiMateExchangeCodec.PROFILE))
                    business = domain.relationBusinessId(value, connection, selected, mappings);
                session.mapping(operation.id(), change.externalId(), business, requirementId, operation.document().externalVersion(), change.after(), value, value == null);
            }
            State resultContext = before.state();
            if (!linked && changed) {
                // Only reviewed evidence is put in canonical versions; rejected source remains in the scoped operation journal.
                ExchangeDocument evidence = new ExchangeDocument(resultDocument.profile(), resultDocument.profileVersion(), resultDocument.externalVersion(),
                        resultDocument.completeScope(), "", resultDocument.artifacts(), resultDocument.relations(), resultDocument.placements(), resultDocument.metadata(), resultDocument.losses());
                String encoded = json.write(evidence);
                commands.add(new StoreExchangeEvidence("integration-" + connection.id(), connection.connectorId(), connection.profileVersion(), json.fingerprint(evidence), encoded));
                try {
                    resultContext = editor.acceptIntegration(context, before.state(), metadata(operation.id(), review.rationale()), json.fingerprint(review), commands,
                            connection.projectId() != null ? domain.portfolioContribution(context) : null, checkpointMetadata(operation.id(), review.rationale()));
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            }
            String projectFingerprint = domain.snapshot(context, connection, session.identities(), before).state().projectFingerprint();
            InternalState state = new InternalState(context.repositoryId(), resultContext.workspaceScopeKey(), context.branch(), resultContext.commitId(),
                    resultContext.semanticRevision(), connection.projectId(), projectFingerprint);
            session.applied(operation.id(), state, resultDocument, !linked && changed);
            if (linked || !changed) session.complete(operation.id(), state, resultDocument.externalVersion(), json.fingerprint(semanticItems(selected)), true);
            return session.operation(operation.id());
            });
        });
        return result.status() == OperationStatus.CHECKPOINT_PENDING ? finish(context, connectionId, result) : result;
    }

    /** A crash at any point after ORM commit resumes the same frozen Git intent, never reapplies model mutations. */
    public Operation retry(RepositoryContext context, UUID connectionId, UUID operationId) {
        authorize(context, true); Operation operation = store.operation(context, connectionId, operationId); requireActor(context, operation);
        if (operation.status() == OperationStatus.COMPLETED) return operation;
        if (operation.status() == OperationStatus.FETCH_PENDING || operation.status() == OperationStatus.FETCH_FAILED)
            return fetchRemote(context, connectionId, operation);
        if (operation.status() != OperationStatus.CHECKPOINT_PENDING) throw IntegrationProblem.conflict("NOT_RETRYABLE");
        return finish(context, connectionId, operation);
    }
    private Operation finish(RepositoryContext context, UUID connectionId, Operation operation) {
        try {
            InternalState state = operation.resultState();
            var checkpoint = editor.checkpoint(context,
                    new State(state.workspaceScopeKey(), state.commitId(), state.semanticRevision()),
                    checkpointMetadata(operation.id(), operation.review().rationale()));
            InternalState completed = new InternalState(state.repositoryId(), state.workspaceScopeKey(), state.branch(), checkpoint.state().commitId(),
                    checkpoint.state().semanticRevision(), state.projectId(), state.projectFingerprint());
            return store.locked(context, connectionId, session -> {
                session.complete(operation.id(), completed, operation.document().externalVersion(), json.fingerprint(semanticItems(ExchangeItems.flatten(operation.resultDocument()))), true);
                return session.operation(operation.id());
            });
        } catch (IOException | RuntimeException failure) {
            if (rejectedCheckpoint(failure)) {
                store.locked(context, connectionId, session -> { session.checkpointConflict(operation.id()); return null; });
                throw new IntegrationProblem("CHECKPOINT_CONFLICT", 409, "Accepted model changes and their snapshot are durable; reconcile the moved Git version explicitly before starting a new integration review");
            }
            store.locked(context, connectionId, session -> { session.failure(operation.id(), "CHECKPOINT_RETRY_REQUIRED"); return null; });
            throw new IntegrationProblem("CHECKPOINT_RETRY_REQUIRED", 503, "Model changes are durable; retry the pending checkpoint with the same operation identity");
        }
    }
    private static boolean rejectedCheckpoint(Throwable failure) {
        // Spring's repository exception translation may wrap a durable conflict signal on retry.
        for (int depth = 0; failure != null && depth < 20; depth++, failure = failure.getCause()) {
            if (failure instanceof WorkspaceRevisionConflict) return true;
            if (failure instanceof ArchitectureDslCommands.CommandProblem problem
                    && Set.of("CHECKPOINT_CONFLICT", "CHECKPOINT_REJECTED").contains(problem.code())) return true;
        }
        return false;
    }
    public Operation cancel(RepositoryContext context, UUID connectionId, UUID operationId, String rationale) {
        authorize(context, true); metadata(operationId, rationale);
        return store.locked(context, connectionId, session -> { requireActor(context, session.operation(operationId)); session.cancel(operationId, rationale); return session.operation(operationId); });
    }

    public Operation previewRemote(RepositoryContext context, UUID connectionId, RemoteRequest request) {
        authorize(context, true);
        if (request.operationId() == null || request.expected() == null) throw precondition();
        Connection connection = store.read(context, connectionId);
        if (!connection.connectorId().equals(OslcRequirementsCodec.PROFILE) || connection.authority() == AuthorityMode.PUBLISH_TARGET)
            throw new IntegrationProblem("AUTHORITY_MODE", 409, "Connection has no remote read capability");
        String resource = remote.validate(context, connection, request.resource()).toString();
        String fingerprint = json.fingerprint(Arrays.asList(connectionId, context.username(), request));
        Operation pending = store.locked(context, connectionId, session -> {
            Operation prior = session.find(request.operationId()); if (prior != null) return replay(prior, fingerprint);
            expect(request.expected(), domain.snapshot(context, connection, session.identities(), read(context)).state());
            var template = new ExchangeDocument(OslcRequirementsCodec.PROFILE, connection.profileVersion(), request.expectedExternalVersion(), false, "",
                    List.of(), List.of(), List.of(), Map.of("resource", resource, "remoteRequest", json.write(request)), List.of());
            session.preview(request.operationId(), authority(context, connection, request.expected()), "INBOUND", fingerprint, template, List.of());
            session.fetching(request.operationId()); return session.operation(request.operationId());
        });
        return pending.status() == OperationStatus.FETCH_PENDING || pending.status() == OperationStatus.FETCH_FAILED ? fetchRemote(context, connectionId, pending) : pending;
    }
    private Operation fetchRemote(RepositoryContext context, UUID connectionId, Operation pending) {
        try {
            Connection connection = store.read(context, connectionId); requireProfile(connection);
            RemoteRequest request = json.read(pending.document().metadata().get("remoteRequest"), RemoteRequest.class);
            var response = remote.read(context, connection, request.resource(), request.expectedExternalVersion());
            var codec = new OslcRequirementsCodec();
            ExchangeDocument received = codec.read(response.content(), response.resource(), response.etag(), connection.externalScope().configuration());
            Map<String, String> metadata = new TreeMap<>(received.metadata()); metadata.put("remoteRequest", json.write(request));
            metadata.put("discovery", json.write(codec.discover(response.content(), response.resource(), response.etag(), connection.externalScope().configuration())));
            ExchangeDocument document = new ExchangeDocument(received.profile(), received.profileVersion(), received.externalVersion(), false, received.source(),
                    received.artifacts(), received.relations(), received.placements(), metadata, received.losses());
            return store.locked(context, connectionId, session -> {
                Operation currentOperation = session.operation(pending.id());
                if (currentOperation.status() == OperationStatus.PREVIEWED) return currentOperation;
                var current = domain.snapshot(context, connection, session.identities(), read(context)); expect(pending.context().internalState(), current.state());
                session.fetched(pending.id(), document, diff.compare(document, connection.authority(), session.identities(), current.items()));
                return session.operation(pending.id());
            });
        } catch (RuntimeException failure) {
            String code = failure instanceof IntegrationProblem problem ? problem.code() : "REMOTE_FORMAT_REJECTED";
            store.locked(context, connectionId, session -> { session.fetchFailed(pending.id(), code); return null; });
            throw failure;
        }
    }

    public Operation previewExport(RepositoryContext context, UUID connectionId, ExportRequest request) {
        authorize(context, true);
        if (request.operationId() == null || request.expected() == null) throw precondition();
        return store.locked(context, connectionId, session -> {
            Connection connection = session.connection(); requireProfile(connection);
            if (Set.of(AuthorityMode.LINK_ONLY, AuthorityMode.MIRROR_READ).contains(connection.authority()))
                throw new IntegrationProblem("AUTHORITY_MODE", 409, "This authority mode does not permit publication");
            var connector = connectors.require(connection.connectorId());
            if (!connector.descriptor().capabilities().contains(Capability.FILE_EXPORT)) throw new IntegrationProblem("CAPABILITY_UNAVAILABLE", 409, "Profile has no file export capability");
            String fingerprint = json.fingerprint(Arrays.asList(connectionId, context.username(), request));
            Operation prior = session.find(request.operationId()); if (prior != null) return replay(prior, fingerprint);
            var document = read(context); List<Identity> mappings = session.identities();
            var current = domain.snapshot(context, connection, mappings, document); expect(request.expected(), current.state());
            ExchangeDocument previous = connection.checkpointId() == null ? null : session.operation(connection.checkpointId()).resultDocument();
            if (!Objects.equals(request.expectedExternalVersion(), previous == null ? null : previous.externalVersion())) throw IntegrationProblem.conflict("EXTERNAL_STATE_CHANGED");
            var authority = authority(context, connection, current.state());
            ExchangeDocument outgoing = domain.exportDocument(connection, current, document, mappings, previous);
            if (outgoing.losses().stream().noneMatch(loss -> loss.disposition() == LossDisposition.UNSUPPORTED)) {
            ExchangeFile file = connector.previewOutbound(new OutboundRequest(authority, outgoing, request.expectedExternalVersion()));
            // Normalize generated types and hierarchy now. The downloaded file is derived solely from this durable snapshot.
            ExchangeDocument normalized = connector.previewInbound(new InboundRequest(authority, file.mediaType(), file.content(), outgoing.externalVersion(), true));
            List<MappingLoss> losses = new ArrayList<>(outgoing.losses());
            file.losses().forEach(loss -> { if (!losses.contains(loss)) losses.add(loss); });
            normalized.losses().forEach(loss -> { if (!losses.contains(loss)) losses.add(loss); });
            outgoing = new ExchangeDocument(normalized.profile(), normalized.profileVersion(), normalized.externalVersion(), normalized.completeScope(), normalized.source(),
                    normalized.artifacts(), normalized.relations(), normalized.placements(), normalized.metadata(), losses);
            }
            List<IntegrationChange> changes = ExchangeItems.flatten(outgoing).entrySet().stream().map(e -> new IntegrationChange(e.getKey(), e.getKey(), ChangeKind.ADD,
                    ExchangeItems.fields(e.getValue()).keySet(), json.fingerprint(ExchangeItems.fields(e.getValue())), json.fingerprint(null), null, e.getValue(), List.of())).toList();
            return session.preview(request.operationId(), authority, "OUTBOUND", fingerprint, outgoing, changes);
        });
    }

    /** Reviewed file delivery is not a remote publish acknowledgement and does not advance the synchronization baseline. */
    public Operation prepareFile(RepositoryContext context, UUID connectionId, ReviewedChangeSet review) {
        authorize(context, true);
        return store.locked(context, connectionId, session -> {
            Operation operation = session.operation(review.operationId()); requireActor(context, operation); requireProfile(session.connection());
            if (!"OUTBOUND".equals(operation.direction())) throw IntegrationProblem.conflict("OPERATION_DIRECTION");
            if (operation.reviewFingerprint() != null) {
                if (!operation.reviewFingerprint().equals(json.fingerprint(review))) throw IntegrationProblem.conflict("REVIEW_ID_REUSED");
                return operation;
            }
            return workspace(context, before -> {
            domain.lockProject(context, session.connection().projectId());
            List<Identity> known = session.identities();
            var current = domain.snapshot(context, session.connection(), known, before);
            expect(operation.context().internalState(), current.state());
            Map<String, Artifact> selected = select(operation, review, Map.of(), Map.of());
            ExchangeDocument result = ExchangeItems.expand(operation.document(), selected);
            boolean complete = selected.size() == operation.changes().size();
            result = new ExchangeDocument(result.profile(), result.profileVersion(), result.externalVersion(), complete, result.source(), result.artifacts(), result.relations(), result.placements(), result.metadata(), result.losses());
            ExchangeFile file = connectors.require(operation.context().profile()).previewOutbound(new OutboundRequest(operation.context(), result, result.externalVersion()));
            session.beginReview(review); session.applied(operation.id(), operation.context().internalState(), result, false);
            Map<String, IntegrationDomainAdapter.AppliedRequirement> bindings = domain.exportBindings(session.connection(), current, before, selected);
            Set<String> existing = known.stream().map(Identity::externalId).collect(java.util.stream.Collectors.toSet());
            for (var entry : bindings.entrySet()) if (selected.containsKey(entry.getKey()) && !existing.contains(entry.getKey()))
                session.mapping(operation.id(), entry.getKey(), entry.getValue().businessIdentity(), entry.getValue().requirementId(),
                        null, null, selected.get(entry.getKey()), false);
            session.file(operation.id(), file);
            session.complete(operation.id(), operation.context().internalState(), result.externalVersion(), json.fingerprint(result), false);
            return session.operation(operation.id());
            });
        });
    }
    public ExchangeFile file(RepositoryContext context, UUID connectionId, UUID operationId) {
        authorize(context, false); Operation operation = store.operation(context, connectionId, operationId);
        if (!"OUTBOUND".equals(operation.direction()) || operation.status() != OperationStatus.COMPLETED) throw IntegrationProblem.conflict("FILE_NOT_READY");
        if (operation.resultFile() == null) throw IntegrationProblem.conflict("FILE_NOT_READY");
        return operation.resultFile();
    }

    private Map<String, Artifact> select(Operation operation, ReviewedChangeSet review, Map<String, Artifact> current, Map<String, Identity> known) {
        Map<String, Artifact> selected = new TreeMap<>(current);
        Set<String> ids = new HashSet<>(); operation.changes().forEach(c -> ids.add(c.id()));
        if (!ids.containsAll(review.decisions().keySet())) throw new IllegalArgumentException("Review contains unknown change identities");
        if (!review.decisions().keySet().containsAll(review.mappings().keySet())) throw new IllegalArgumentException("Mappings require an explicit item decision");
        if (operation.context().authority() != AuthorityMode.LINK_ONLY && review.mappings().values().stream().anyMatch(m -> m.internalIdentity() != null))
            throw new IllegalArgumentException("Internal trace targets require LINK_ONLY authority");
        for (IntegrationChange change : operation.changes()) {
            Decision decision = review.decisions().get(change.id());
            if (decision == null && change.kind() != ChangeKind.UNCHANGED) throw new IllegalArgumentException("Every proposed change needs an explicit decision");
            if (decision == null || decision == Decision.REJECT || decision == Decision.KEEP_INTERNAL) continue;
            if (change.kind() == ChangeKind.CONFLICT && decision != Decision.TAKE_EXTERNAL) throw IntegrationProblem.conflict("CONFLICT_REQUIRES_EXPLICIT_RESOLUTION");
            if (change.after() == null) selected.remove(change.externalId());
            else {
                Identity previous = known.get(change.externalId());
                Artifact baseline = previous == null ? null : previous.external() == null ? previous.internal() : previous.external();
                Artifact selectedValue = ExchangeItems.merge(baseline, current.get(change.externalId()), change.after());
                MappingOverride mapping = review.mappings().get(change.id());
                Artifact mapped = remap(selectedValue, mapping);
                if (operation.direction().equals("OUTBOUND") && operation.context().profile().equals(ArchiMateExchangeCodec.PROFILE)
                        && mapping != null && mapping.canonicalType() != null) {
                    String type = mapped.kind() == ArtifactKind.ELEMENT ? IntegrationDomainAdapter.archimateType(mapping.canonicalType())
                            : mapped.kind() == ArtifactKind.RELATION ? IntegrationDomainAdapter.archimateRelation(mapping.canonicalType()) : mapped.type();
                    mapped = new Artifact(mapped.id(), mapped.kind(), type, mapped.title(), mapped.text(), mapped.attributes(), mapped.extensions());
                }
                selected.put(change.externalId(), mapped);
            }
        }
        if (!selected.isEmpty() && !selected.containsKey("METADATA:package"))
            throw new IntegrationProblem("REVIEW_DEPENDENCY_REQUIRED", 422, "Accepted exchange objects require their reviewed type and package metadata");
        return selected;
    }
    private static Artifact remap(Artifact artifact, MappingOverride mapping) {
        if (mapping == null) return artifact;
        Map<String, String> extensions = new TreeMap<>(artifact.extensions()); String title = artifact.title(), text = artifact.text();
        if (mapping.canonicalType() != null && !mapping.canonicalType().isBlank()) {
            Set<String> supported = artifact.kind() == ArtifactKind.ELEMENT
                    ? Set.of("Capability", "Process", "CoreService", "COIService", "CommunicationsService", "UserApplication", "InformationProduct", "BusinessRole", "System", "Component")
                    : artifact.kind() == ArtifactKind.RELATION ? new HashSet<>(ArchiMateExchangeCodec.RELATION_TYPES.values()) : Set.of();
            if (!supported.contains(mapping.canonicalType())) throw new IllegalArgumentException("Unsupported canonical type mapping");
            extensions.put("canonicalType", mapping.canonicalType());
            extensions.put("taxonomy:" + (artifact.kind() == ArtifactKind.ELEMENT ? "ElementType" : "RelationType"), mapping.canonicalType());
        }
        if (mapping.titleAttribute() != null && !mapping.titleAttribute().isBlank()) {
            title = mappedText(artifact, mapping.titleAttribute()); extensions.put("titleAttribute", mapping.titleAttribute());
        }
        if (mapping.textAttribute() != null && !mapping.textAttribute().isBlank()) {
            text = mappedText(artifact, mapping.textAttribute()); extensions.put("textAttribute", mapping.textAttribute());
        }
        if (extensions.get("titleAttribute") != null && extensions.get("titleAttribute").equals(extensions.get("textAttribute")))
            throw new IllegalArgumentException("Title and body require independent mappings");
        return new Artifact(artifact.id(), artifact.kind(), artifact.type(), title, text, artifact.attributes(), extensions);
    }
    private static String mappedText(Artifact artifact, String attribute) {
        if (artifact.kind() != ArtifactKind.REQUIREMENT || !artifact.attributes().containsKey(attribute)) throw new IllegalArgumentException("Unknown requirement attribute mapping");
        String value = artifact.attributes().get(attribute);
        return "XHTML".equals(artifact.extensions().get("kind:" + attribute)) ? ExchangeXml.parse(value.getBytes(StandardCharsets.UTF_8)).getDocumentElement().getTextContent() : value;
    }
    private Map<String, Map<String, String>> semanticItems(Map<String, Artifact> items) {
        Map<String, Map<String, String>> values = new TreeMap<>(); items.forEach((key, value) -> values.put(key, ExchangeItems.fields(value))); return values;
    }
    private IntegrationContext authority(RepositoryContext context, Connection connection, InternalState state) {
        return new IntegrationContext(connection.id(), connection.authority(), connection.externalScope(), state, context.username(), connection.connectorId(), connection.profileVersion());
    }
    private void requireProfile(Connection connection) {
        if (!connectors.require(connection.connectorId()).descriptor().version().equals(connection.profileVersion())) throw IntegrationProblem.conflict("PROFILE_VERSION_CHANGED");
    }
    private WorkspaceDocument read(RepositoryContext context) {
        try { return editor.read(context, null); } catch (IOException failure) { throw new IntegrationProblem("VERSION_UNAVAILABLE", 503, "Architecture state is temporarily unavailable"); }
    }
    private <T> T workspace(RepositoryContext context, java.util.function.Function<WorkspaceDocument, T> action) {
        try { return editor.locked(context, action); } catch (IOException failure) { throw new IntegrationProblem("VERSION_UNAVAILABLE", 503, "Architecture state is temporarily unavailable"); }
    }
    private void authorize(RepositoryContext context, boolean write) {
        if (context == null || context.username() == null || context.scope() != RepositoryScope.WORKSPACE) throw new IntegrationProblem("PRIVATE_WORKSPACE_REQUIRED", 403, "Select an authorized private workspace");
        if (!workspaceAccess.canUsePrivateWorkspace(context)) throw IntegrationProblem.missing();
        var repository = repositories.getRepository(context.repositoryId());
        if (!(write ? memberships.canContribute(repository, context.username()) : memberships.canRead(repository, context.username()))) throw IntegrationProblem.missing();
    }
    private static void expect(InternalState expected, InternalState actual) {
        if (expected == null) throw precondition();
        if (!expected.equals(actual)) throw IntegrationProblem.conflict("INTERNAL_STATE_CHANGED");
    }
    private static String encode(String value) { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static IntegrationProblem precondition() { return new IntegrationProblem("EXACT_STATE_REQUIRED", 428, "An exact workspace revision and project fingerprint are required"); }
    private static Operation replay(Operation prior, String fingerprint) {
        if (!prior.fingerprint().equals(fingerprint)) throw IntegrationProblem.conflict("OPERATION_ID_REUSED"); return prior;
    }
    private static void requireActor(RepositoryContext context, Operation operation) {
        if (!context.username().equals(operation.context().actor())) throw IntegrationProblem.missing();
    }
    private static CommandMetadata metadata(UUID id, String rationale) {
        return new CommandMetadata(id, id, id, rationale);
    }
    private static CommandMetadata checkpointMetadata(UUID operation, String rationale) {
        UUID id = UUID.nameUUIDFromBytes((operation + ":checkpoint").getBytes(StandardCharsets.UTF_8));
        return new CommandMetadata(id, operation, operation, rationale);
    }
}
