package com.taxonomy.backup;

import io.swagger.v3.oas.annotations.media.Schema;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;

/** Bounded explicit JSON binding. No class-name-based polymorphic deserialization. */
public final class BackupManifestCodec {
    public static final int MAX_MANIFEST_BYTES = BackupLimits.MAX_MANIFEST_BYTES;
    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxDocumentLength(MAX_MANIFEST_BYTES).maxNestingDepth(20)
                            .maxStringLength(8192).maxNameLength(512).maxNumberLength(32)
                            .maxTokenCount(500_000).build())
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    public BackupManifest read(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_MANIFEST_BYTES) throw new IllegalArgumentException("Invalid manifest size");
        try {
            JsonNode document = mapper.readTree(bytes);
            validateWireLimits(document);
            return mapper.treeToValue(document, WireManifest.class).domain();
        } catch (RuntimeException failure) {
            // Do not echo package data: malformed input can contain secrets.
            throw new IllegalArgumentException("Invalid or unsupported backup manifest");
        }
    }

    /** Count before binding to sets: duplicate input elements must not evade collection limits. */
    private static void validateWireLimits(JsonNode value) {
        if (value == null) throw new IllegalArgumentException("Missing manifest");
        if (value.isString() && value.stringValue().codePointCount(0, value.stringValue().length()) > BackupLimits.MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Manifest string exceeds limit");
        }
        if (value.isContainer()) {
            if (value.size() > BackupLimits.MAX_ITEMS) throw new IllegalArgumentException("Manifest collection exceeds limit");
            for (var child : value) validateWireLimits(child);
        }
    }

    public byte[] write(BackupManifest manifest) {
        byte[] bytes = mapper.writeValueAsBytes(WireManifest.from(Objects.requireNonNull(manifest)));
        if (bytes.length > MAX_MANIFEST_BYTES) throw new IllegalArgumentException("Manifest exceeds size limit");
        // A writer must never emit a document rejected by its own bounded reader (including token limits).
        read(bytes);
        return bytes;
    }

    public static final int MAX_REQUEST_BYTES = 64 * 1024;
    public static final int MAX_AUTHORIZATION_BYTES = MAX_REQUEST_BYTES + 2048;

    public BackupRequest readRequest(byte[] bytes) {
        try { return readDocument(bytes, MAX_REQUEST_BYTES, WireRequest.class).domain(); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("Invalid backup request document"); }
    }
    public byte[] writeRequest(BackupRequest request) {
        byte[] bytes = mapper.writeValueAsBytes(WireRequest.from(request)); readRequest(bytes); return bytes;
    }
    public WireRequest requestView(BackupRequest request) { return WireRequest.from(request); }
    public byte[] writeAuthorization(AuthorizedBackupRequest authorization) {
        byte[] bytes = mapper.writeValueAsBytes(new WireAuthorization(WireRequest.from(authorization.request()),
                authorization.principalId().value().toString(), authorization.decisionId(), authorization.authorizedAt().toString(), authorization.capabilities()));
        readAuthorization(bytes); return bytes;
    }
    public AuthorizedBackupRequest readAuthorization(byte[] bytes) {
        var wire = readDocument(bytes, MAX_AUTHORIZATION_BYTES, WireAuthorization.class);
        try {
            return new AuthorizedBackupRequest(wire.request().domain(), new PrincipalId(UUID.fromString(wire.principalId())),
                    wire.decisionId(), Instant.parse(wire.authorizedAt()), wire.capabilities());
        } catch (RuntimeException failure) { throw new IllegalArgumentException("Invalid backup authorization record"); }
    }
    private <T> T readDocument(byte[] bytes, int limit, Class<T> type) {
        if (bytes == null || bytes.length > limit) throw new IllegalArgumentException("Backup request size limit exceeded");
        try {
            var document = mapper.readTree(bytes);
            if (document == null || !document.isObject()) throw new IllegalArgumentException("Object required");
            validateWireLimits(document); return mapper.treeToValue(document, type);
        } catch (RuntimeException failure) { throw new IllegalArgumentException("Invalid backup request document"); }
    }
    private record WireAuthorization(WireRequest request, String principalId, String decisionId,
                                     String authorizedAt, Set<BackupCapability> capabilities) { }

    public enum ScopeKind { WORKSPACE, REPOSITORIES, INSTALLATION }
    public enum TimeKind { CURRENT, SELECTED_VERSION, HISTORY }

    @Schema(description = "All fields must be supplied; unused scalar IDs are null and unused selections are empty",
            requiredProperties = {"kind", "repositoryId", "workspaceId", "workspacesByRepository"})
    public record WireScope(
            @Schema(description = "Select WORKSPACE, REPOSITORIES or INSTALLATION") ScopeKind kind,
            @Schema(description = "Repository ID for WORKSPACE; null for other kinds", nullable = true) String repositoryId,
            @Schema(description = "Workspace ID for WORKSPACE; null for other kinds", nullable = true) String workspaceId,
            @Schema(description = "Explicit repository/workspace sets for REPOSITORIES; empty for other kinds") Map<String, Set<String>> workspacesByRepository) {
        BackupScope domain() {
            Objects.requireNonNull(workspacesByRepository);
            return switch (kind) {
                case WORKSPACE -> {
                    if (!workspacesByRepository.isEmpty()) throw new IllegalArgumentException("Conflicting workspace scope");
                    yield new BackupScope.Workspace(repositoryId, workspaceId);
                }
                case REPOSITORIES -> {
                    if (repositoryId != null || workspaceId != null) throw new IllegalArgumentException("Conflicting repository scope");
                    yield new BackupScope.Repositories(workspacesByRepository);
                }
                case INSTALLATION -> {
                    if (repositoryId != null || workspaceId != null || !workspacesByRepository.isEmpty()) {
                        throw new IllegalArgumentException("Conflicting installation scope");
                    }
                    yield new BackupScope.Installation();
                }
            };
        }
        static WireScope from(BackupScope scope) {
            return switch (scope) {
                case BackupScope.Workspace w -> new WireScope(ScopeKind.WORKSPACE, w.repositoryId(), w.workspaceId(), Map.of());
                case BackupScope.Repositories r -> new WireScope(ScopeKind.REPOSITORIES, null, null, r.workspacesByRepository());
                case BackupScope.Installation ignored -> new WireScope(ScopeKind.INSTALLATION, null, null, Map.of());
            };
        }
    }

    @Schema(description = "One exact commit per selected repository", requiredProperties = {"repository", "commit"})
    public record WireSelectedCommit(BackupRepositoryKey repository, String commit) { }
    @Schema(description = "Time axis must agree with the selected profile", requiredProperties = {"kind", "commitsByRepository"})
    public record WireTime(
            @Schema(description = "CURRENT, SELECTED_VERSION or HISTORY") TimeKind kind,
            @Schema(description = "Exact commits for SELECTED_VERSION; an empty list for CURRENT or HISTORY") List<WireSelectedCommit> commitsByRepository) {
        BackupTime domain() {
            Objects.requireNonNull(commitsByRepository);
            if (kind != TimeKind.SELECTED_VERSION && !commitsByRepository.isEmpty()) throw new IllegalArgumentException("Unexpected selected commits");
            return switch (kind) {
                case CURRENT -> new BackupTime.Current();
                case HISTORY -> new BackupTime.History();
                case SELECTED_VERSION -> {
                    var commits = new HashMap<BackupRepositoryKey, String>();
                    for (var selected : commitsByRepository) {
                        if (commits.put(Objects.requireNonNull(selected.repository()), Objects.requireNonNull(selected.commit())) != null) {
                            throw new IllegalArgumentException("Duplicate selected repository");
                        }
                    }
                    yield new BackupTime.SelectedVersion(commits);
                }
            };
        }
        static WireTime from(BackupTime time) {
            return switch (time) {
                case BackupTime.Current ignored -> new WireTime(TimeKind.CURRENT, List.of());
                case BackupTime.History ignored -> new WireTime(TimeKind.HISTORY, List.of());
                case BackupTime.SelectedVersion v -> new WireTime(TimeKind.SELECTED_VERSION, v.commitsByRepository().entrySet().stream()
                        .map(e -> new WireSelectedCommit(e.getKey(), e.getValue())).toList());
            };
        }
    }

    @Schema(description = "Strict backup JSON wire contract; unknown and missing fields are rejected",
            requiredProperties = {"profile", "scope", "time", "gitRepresentation", "secrets"})
    public record WireRequest(
            @Schema(description = "Backup profile; must agree with the installation scope and time selection") BackupProfile profile,
            @Schema(description = "Explicit workspace, repository-set or installation selection") WireScope scope,
            @Schema(description = "Current state, exact selected commits or complete history, as required by the profile") WireTime time,
            @Schema(description = "Git representation; NONE is invalid for history profiles") GitRepresentation gitRepresentation,
            @Schema(description = "Secret handling; INCLUDE_ENCRYPTED requires INSTALLATION_FULL and appropriate authorization") SecretsSelection secrets) {
        BackupRequest domain() { return new BackupRequest(profile, scope.domain(), time.domain(), gitRepresentation, secrets); }
        static WireRequest from(BackupRequest r) {
            return new WireRequest(r.profile(), WireScope.from(r.scope()), WireTime.from(r.time()), r.gitRepresentation(), r.secrets());
        }
    }

    public record WireComponent(String id, int version, BackupCompleteness completeness,
                                List<String> entryPaths, Set<String> dependencies) {
        BackupManifest.Component domain() {
            return new BackupManifest.Component(new BackupComponentId(id), version, completeness, entryPaths,
                    dependencies.stream().map(BackupComponentId::new).collect(java.util.stream.Collectors.toSet()));
        }
        static WireComponent from(BackupManifest.Component c) {
            return new WireComponent(c.id().value(), c.version(), c.completeness(), c.entryPaths(),
                    c.dependencies().stream().map(BackupComponentId::value).collect(java.util.stream.Collectors.toSet()));
        }
    }

    public record WireManifest(int formatVersion, String applicationVersion, String build, String backupId,
                               String sourceInstallationId, WireRequest request, String captureStartedAt,
                               String captureCompletedAt, String consistencyEvidence, Set<String> requiredFeatures,
                               List<WireComponent> components, List<BackupManifest.Repository> repositories,
                               List<BackupEntry> entries, List<String> dependencies, List<String> omissions) {
        BackupManifest domain() {
            return new BackupManifest(formatVersion, applicationVersion, build, new BackupId(UUID.fromString(backupId)),
                    sourceInstallationId, request.domain(), Instant.parse(captureStartedAt), Instant.parse(captureCompletedAt),
                    consistencyEvidence, requiredFeatures, components.stream().map(WireComponent::domain).toList(),
                    repositories, entries, dependencies, omissions);
        }
        static WireManifest from(BackupManifest m) {
            return new WireManifest(m.formatVersion(), m.applicationVersion(), m.build(), m.backupId().value().toString(),
                    m.sourceInstallationId(), WireRequest.from(m.request()), m.captureStartedAt().toString(), m.captureCompletedAt().toString(),
                    m.consistencyEvidence(), new TreeSet<>(m.requiredFeatures()), m.components().stream().map(WireComponent::from).toList(),
                    m.repositories(), m.entries(), m.dependencies(), m.omissions());
        }
    }
}
