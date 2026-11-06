package com.taxonomy.extension.api.integration;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Framework-neutral exchange values. These are transport/evidence values, never a second authoring model. */
public final class IntegrationContracts {
    private IntegrationContracts() {}

    public enum AuthorityMode { LINK_ONLY, IMPORT_COPY, MIRROR_READ, PUBLISH_TARGET, BIDIRECTIONAL }
    public enum Capability {
        FILE_IMPORT, FILE_EXPORT, DISCOVERY, READ_LINK, CONDITIONAL_PUBLISH, ARCHITECTURE_MODEL,
        /**
         * Version 1 of the neutral canonical projection: ELEMENT/RELATION
         * extensions.canonicalType, SPECIFICATION packages, hierarchical package
         * placements and VIEW-container diagram placements. Missing types and
         * typed relation endpoints are resolved by explicit host review. Native
         * approval metadata is never an authorization grant from the source.
         * Raw artifact type and attributes remain external evidence.
         */
        CANONICAL_PROJECTION_V1,
        /** File output from an immutable operator-supplied basis with host-reserved identities. */
        BASED_FILE_EXPORT_V1
    }
    public enum ArtifactKind { REQUIREMENT, ELEMENT, VIEW, SPECIFICATION, FEATURE, RELATION, PLACEMENT, METADATA }
    public enum ChangeKind { ADD, UPDATE, MOVE, REMOVE_CANDIDATE, RELATION, CONFLICT, UNCHANGED }
    public enum Decision { ACCEPT, REJECT, TAKE_EXTERNAL, KEEP_INTERNAL }
    public enum OperationStatus { FETCH_PENDING, FETCH_FAILED, PREVIEWED, APPLYING, APPLIED, CHECKPOINT_PENDING, COMPLETED, CONFLICT, CANCELLED, FAILED, PARTIAL }
    public enum LossDisposition { MAPPED, PRESERVED_EXTENSION, TRANSFORMED, UNSUPPORTED }
    public enum FileExportMode { NEW_PROJECT, UPDATE_COPY }
    public enum ExternalIdentityFormat { UUID_BRACED_UPPER }

    /** Neutral host projection policy; native serialization stays inside the connector. */
    public record FileExportProfile(Set<FileExportMode> modes, ExternalIdentityFormat identityFormat,
                                    Map<String, String> elementTypes, Map<String, String> relationTypes,
                                    String requirementType, String packageType, String viewType) {
        public FileExportProfile {
            modes = Set.copyOf(modes); Objects.requireNonNull(identityFormat);
            if (modes.isEmpty()) throw new IllegalArgumentException("At least one file export mode is required");
            elementTypes = Map.copyOf(elementTypes); relationTypes = Map.copyOf(relationTypes);
            elementTypes.forEach((canonical, transport) -> { require(canonical, "canonical element type"); require(transport, "element transport type"); });
            relationTypes.forEach((canonical, transport) -> { require(canonical, "canonical relation type"); require(transport, "relation transport type"); });
            require(requirementType, "requirement type"); require(packageType, "package type"); require(viewType, "view type");
        }
    }

    /** Adapter guarantees are profile-specific, not claims about every product that reads the format. */
    public record PlanningSupport(String profile, String version, Set<String> fields,
                                  boolean readable, boolean writable, String representation,
                                  String roundTripScope, boolean nativeEvaluation) {
        public PlanningSupport { fields = Set.copyOf(fields); }
    }
    public record IntegrationDescriptor(String id, String version, String title,
                                        Set<Capability> capabilities, Set<String> mediaTypes,
                                        List<PlanningSupport> planningSupport) {
        public IntegrationDescriptor(String id, String version, String title, Set<Capability> capabilities, Set<String> mediaTypes) {
            this(id, version, title, capabilities, mediaTypes, List.of());
        }
        public IntegrationDescriptor {
            require(id, "connector id"); require(version, "connector version");
            capabilities = Set.copyOf(capabilities); mediaTypes = Set.copyOf(mediaTypes);
            planningSupport = planningSupport == null ? List.of() : List.copyOf(planningSupport);
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
        public Placement {
            require(id, "occurrence id"); require(containerId, "container id");
            if (position < 0) throw new IllegalArgumentException("Occurrence position must not be negative");
            attributes = immutable(attributes);
        }
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
        public IntegrationChange {
            // This set is an HTTP array too; retained reviews need the same order after a JVM restart.
            fields = Collections.unmodifiableSet(new TreeSet<>(fields));
            conflicts = List.copyOf(conflicts);
        }
    }

    public record IntegrationChangeSet(UUID operationId, IntegrationContext context, String fingerprint,
                                       List<IntegrationChange> changes, List<MappingLoss> losses,
                                       OperationStatus status, String resultCommit, Instant createdAt) {
        public IntegrationChangeSet { changes = List.copyOf(changes); losses = List.copyOf(losses); }
    }

    public record MappingOverride(String canonicalType, String titleAttribute, String textAttribute, String internalIdentity) {}
    public enum RelationProjection { ARCHITECTURE_RELATION, REQUIREMENT_MAPPING, PRESERVE_ONLY }
    public record EndpointOverride(String sourceInternalIdentity, String targetInternalIdentity,
                                   RelationProjection projection, String canonicalType) {}
    public record ReviewedChangeSet(UUID operationId, String previewFingerprint,
                                    Map<String, Decision> decisions, String rationale, Map<String, MappingOverride> mappings,
                                    Map<String, EndpointOverride> endpoints) {
        public ReviewedChangeSet(UUID id, String fingerprint, Map<String, Decision> decisions, String rationale,
                                 Map<String, MappingOverride> mappings) {
            this(id, fingerprint, decisions, rationale, mappings, Map.of());
        }
        public ReviewedChangeSet(UUID id, String fingerprint, Map<String, Decision> decisions, String rationale) {
            this(id, fingerprint, decisions, rationale, Map.of());
        }
        public ReviewedChangeSet {
            Objects.requireNonNull(operationId); require(previewFingerprint, "preview fingerprint");
            decisions = Map.copyOf(decisions); require(rationale, "rationale");
            mappings = mappings == null ? Map.of() : Map.copyOf(mappings);
            endpoints = endpoints == null ? Map.of() : Map.copyOf(endpoints);
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
    public record BasedOutboundRequest(OutboundRequest outbound, FileExportMode mode, UUID identityNamespace,
                                       String baseSha256, String baseMediaType, byte[] baseContent) {
        public BasedOutboundRequest {
            Objects.requireNonNull(outbound); Objects.requireNonNull(mode); Objects.requireNonNull(identityNamespace);
            if (baseSha256 == null || !baseSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("A lowercase SHA-256 basis digest is required");
            require(baseMediaType, "basis media type");
            baseContent = Objects.requireNonNull(baseContent).clone();
            if (baseContent.length == 0) throw new IllegalArgumentException("A nonempty file basis is required");
        }
        @Override public byte[] baseContent() { return baseContent.clone(); }
    }
    public record ExchangeFile(String mediaType, String filename, byte[] content, List<MappingLoss> losses) {
        public ExchangeFile {
            require(filename, "filename");
            if (filename.length() > 240 || filename.equals(".") || filename.equals("..") || filename.contains("/") || filename.contains("\\")
                    || filename.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Unsafe exchange filename");
            content = content.clone(); losses = List.copyOf(losses);
        }
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
        if (value == null) return Map.of();
        for (var entry : value.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("Integration maps must not contain null keys or values");
            }
        }
        return Map.copyOf(value);
    }
    private static void require(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 2048)
            throw new IllegalArgumentException("Missing or oversized " + field);
    }
}
