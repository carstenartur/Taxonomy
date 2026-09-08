package com.taxonomy.extension.api.integration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Framework-neutral exchange values. These are transport/evidence values, never a second authoring model. */
public final class IntegrationContracts {
    private IntegrationContracts() {}

    public enum AuthorityMode { LINK_ONLY, IMPORT_COPY, MIRROR_READ, PUBLISH_TARGET, BIDIRECTIONAL }
    public enum Capability { FILE_IMPORT, FILE_EXPORT, DISCOVERY, READ_LINK, CONDITIONAL_PUBLISH }
    public enum ArtifactKind { REQUIREMENT, ELEMENT, VIEW, SPECIFICATION, RELATION, PLACEMENT, METADATA }
    public enum ChangeKind { ADD, UPDATE, MOVE, REMOVE_CANDIDATE, RELATION, CONFLICT, UNCHANGED }
    public enum Decision { ACCEPT, REJECT, TAKE_EXTERNAL, KEEP_INTERNAL }
    public enum OperationStatus { FETCH_PENDING, FETCH_FAILED, PREVIEWED, APPLYING, APPLIED, CHECKPOINT_PENDING, COMPLETED, CONFLICT, CANCELLED, FAILED, PARTIAL }
    public enum LossDisposition { MAPPED, PRESERVED_EXTENSION, TRANSFORMED, UNSUPPORTED }

    public record IntegrationDescriptor(String id, String version, String title,
                                        Set<Capability> capabilities, Set<String> mediaTypes) {
        public IntegrationDescriptor {
            require(id, "connector id"); require(version, "connector version");
            capabilities = Set.copyOf(capabilities); mediaTypes = Set.copyOf(mediaTypes);
        }
    }

    /** Both editing revision and checkpoint matter; a Git SHA is never a semantic operation identity. */
    public record InternalState(String repositoryId, String workspaceScopeKey, String branch,
                                String commitId, long semanticRevision, Long projectId,
                                String projectFingerprint) {
        public InternalState {
            require(repositoryId, "repositoryId"); require(workspaceScopeKey, "workspaceScopeKey");
            require(branch, "branch");
            if (semanticRevision < 0) throw new IllegalArgumentException("Negative semantic revision");
        }
    }

    public record ExternalScope(String systemType, String repository, String configuration) {
        public ExternalScope { require(systemType, "external system"); require(repository, "external repository"); }
    }

    /** Credentials and private endpoint settings deliberately have no fields in this value. */
    public record IntegrationContext(UUID connectionId, AuthorityMode authority, ExternalScope externalScope,
                                     InternalState internalState, String actor, String profile, String profileVersion) {
        public IntegrationContext {
            Objects.requireNonNull(connectionId); Objects.requireNonNull(authority);
            Objects.requireNonNull(externalScope); Objects.requireNonNull(internalState);
            require(actor, "actor"); require(profile, "profile"); require(profileVersion, "profileVersion");
        }
    }

    public record ExternalArtifactReference(UUID connectionId, ExternalScope scope, String resourceId,
                                            String externalVersion, String contentFingerprint,
                                            InternalState internalState, String businessIdentity,
                                            String profile, String profileVersion, UUID lastOperationId) {}

    public record Artifact(String id, ArtifactKind kind, String type, String title, String text,
                           Map<String, String> attributes, Map<String, String> extensions) {
        public Artifact {
            require(id, "external id"); Objects.requireNonNull(kind);
            attributes = immutable(attributes); extensions = immutable(extensions);
            title = title == null ? "" : title; text = text == null ? "" : text;
        }
    }

    public record Relation(String id, String type, String source, String target,
                           Map<String, String> attributes, Map<String, String> extensions) {
        public Relation {
            require(id, "relation id"); require(source, "source"); require(target, "target");
            attributes = immutable(attributes); extensions = immutable(extensions);
        }
    }

    /** Occurrence identity preserves repeated requirements and independently named specification hierarchies. */
    public record Placement(String id, String containerId, String parentId, String artifactId, int position,
                            Map<String, String> attributes) {
        public Placement { require(id, "occurrence id"); attributes = immutable(attributes); }
    }

    public record MappingLoss(String artifactId, String field, String code,
                              LossDisposition disposition, String detail) {}

    /** Safe source is retained as exchange evidence; outbound adapters must overlay the frozen canonical values. */
    public record ExchangeDocument(String profile, String profileVersion, String externalVersion,
                                   boolean completeScope, String source,
                                   List<Artifact> artifacts, List<Relation> relations, List<Placement> placements,
                                   Map<String, String> metadata, List<MappingLoss> losses) {
        public ExchangeDocument {
            artifacts = List.copyOf(artifacts); relations = List.copyOf(relations);
            placements = List.copyOf(placements); metadata = immutable(metadata); losses = List.copyOf(losses);
        }
    }

    public record IntegrationChange(String id, String externalId, ChangeKind kind, Set<String> fields,
                                    String expectedInternalFingerprint, String expectedExternalFingerprint,
                                    Artifact before, Artifact after, List<String> conflicts) {
        public IntegrationChange { fields = Set.copyOf(fields); conflicts = List.copyOf(conflicts); }
    }

    public record IntegrationChangeSet(UUID operationId, IntegrationContext context, String fingerprint,
                                       List<IntegrationChange> changes, List<MappingLoss> losses,
                                       OperationStatus status, String resultCommit, Instant createdAt) {
        public IntegrationChangeSet { changes = List.copyOf(changes); losses = List.copyOf(losses); }
    }

    public record MappingOverride(String canonicalType, String titleAttribute, String textAttribute, String internalIdentity) {}
    public record ReviewedChangeSet(UUID operationId, String previewFingerprint,
                                    Map<String, Decision> decisions, String rationale, Map<String, MappingOverride> mappings) {
        public ReviewedChangeSet(UUID id, String fingerprint, Map<String, Decision> decisions, String rationale) {
            this(id, fingerprint, decisions, rationale, Map.of());
        }
        public ReviewedChangeSet {
            Objects.requireNonNull(operationId); require(previewFingerprint, "preview fingerprint");
            decisions = Map.copyOf(decisions); require(rationale, "rationale");
            mappings = mappings == null ? Map.of() : Map.copyOf(mappings);
            if (rationale.length() > 1000 || rationale.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Rationale must be bounded plain text");
        }
    }

    public record InboundRequest(IntegrationContext context, String mediaType, byte[] content,
                                 String externalVersion, boolean completeScope) {
        public InboundRequest { content = content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }
    public record OutboundRequest(IntegrationContext context, ExchangeDocument document, String expectedExternalVersion) {}
    public record ExchangeFile(String mediaType, String filename, byte[] content, List<MappingLoss> losses) {
        public ExchangeFile { content = content.clone(); losses = List.copyOf(losses); }
        @Override public byte[] content() { return content.clone(); }
    }
    public record DiscoveryResource(String uri, String type, String title, String version, String configuration) {}
    public record DiscoveryResult(List<DiscoveryResource> resources, boolean complete, String version) {
        public DiscoveryResult { resources = List.copyOf(resources); }
    }
    /** Per-item outcomes make partial publication explicit; a timeout is never a successful checkpoint. */
    public record PublishItem(String resourceId, String expectedVersion, String resultVersion,
                              String contentFingerprint, OperationStatus status, String failureCode) {}
    public record PublishResult(UUID operationId, OperationStatus status, List<PublishItem> items) {
        public PublishResult { items = List.copyOf(items); }
    }

    private static Map<String, String> immutable(Map<String, String> value) {
        return value == null ? Map.of() : Map.copyOf(value);
    }
    private static void require(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 2048)
            throw new IllegalArgumentException("Missing or oversized " + field);
    }
}
