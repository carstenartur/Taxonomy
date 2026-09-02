package com.taxonomy.portfolio.workbench;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.export.service.CanonicalDiagramExportService;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.Projection;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authorizes an immutable architecture snapshot and exports its persisted
 * canonical graph without running analysis or applying current preferences.
 */
@Service
public class ArchitectureSnapshotExportService {

    private static final String FINGERPRINT_VERSION =
            "taxonomy-canonical-graph-v1";

    private final ArchitectureWorkbenchService workbenchService;
    private final CanonicalDiagramExportService diagramExportService;

    public ArchitectureSnapshotExportService(
            ArchitectureWorkbenchService workbenchService,
            CanonicalDiagramExportService diagramExportService) {
        this.workbenchService = workbenchService;
        this.diagramExportService = diagramExportService;
    }

    @Transactional(readOnly = true)
    public Artifact exportArchiMate(
            Long projectId,
            String snapshotId,
            String username,
            WorkspaceContext context) {
        return export(
                projectId,
                snapshotId,
                username,
                context,
                Format.ARCHIMATE_XML);
    }

    @Transactional(readOnly = true)
    public Artifact exportVisio(
            Long projectId,
            String snapshotId,
            String username,
            WorkspaceContext context) {
        return export(
                projectId,
                snapshotId,
                username,
                context,
                Format.VISIO_VSDX);
    }

    private Artifact export(
            Long projectId,
            String snapshotId,
            String username,
            WorkspaceContext context,
            Format format) {
        if (projectId == null) {
            throw PortfolioException.validation("projectId is required");
        }
        String requestedSnapshotId = requireSnapshotId(snapshotId);
        Projection projection = workbenchService.load(
                projectId,
                requestedSnapshotId,
                username,
                context);
        verifyExactCoordinates(projectId, requestedSnapshotId, projection);

        DiagramModel canonicalDiagram = freeze(projection.diagram());
        byte[] content = serialize(canonicalDiagram, format);
        if (content == null || content.length == 0) {
            throw PortfolioException.conflict(
                    "The canonical diagram exporter returned no content");
        }
        String graphFingerprint = fingerprint(canonicalDiagram);

        return new Artifact(
                format,
                fileName(projection, format),
                format.mediaType(),
                format.profile(),
                projection.projectId(),
                projection.requirementId(),
                projection.snapshotId(),
                projection.snapshotStatus(),
                projection.workspaceId(),
                projection.branchName(),
                projection.commitSha(),
                projection.provider(),
                projection.modelName(),
                graphFingerprint,
                sha256(content),
                content);
    }

    private byte[] serialize(DiagramModel canonicalDiagram, Format format) {
        try {
            return switch (format) {
                case ARCHIMATE_XML ->
                        diagramExportService.exportAsArchiMate(canonicalDiagram);
                case VISIO_VSDX ->
                        diagramExportService.exportAsVisio(canonicalDiagram);
            };
        } catch (IllegalArgumentException exception) {
            throw new PortfolioException(
                    PortfolioException.Kind.CONFLICT,
                    "The selected snapshot violates the canonical export contract",
                    exception);
        }
    }

    private static void verifyExactCoordinates(
            Long projectId,
            String snapshotId,
            Projection projection) {
        if (projection == null
                || !Objects.equals(projectId, projection.projectId())
                || !snapshotId.equals(projection.snapshotId())) {
            throw PortfolioException.conflict(
                    "The resolved architecture snapshot does not match "
                            + "the explicitly requested coordinates");
        }
        requireAuthority(projection.workspaceId(), "workspaceId");
        requireAuthority(projection.branchName(), "branchName");
        requireAuthority(projection.commitSha(), "commitSha");
    }

    private static String requireSnapshotId(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw PortfolioException.validation("snapshotId is required");
        }
        if (snapshotId.indexOf('\r') >= 0 || snapshotId.indexOf('\n') >= 0) {
            throw PortfolioException.validation(
                    "snapshotId contains unsafe control characters");
        }
        return snapshotId.strip();
    }

    private static void requireAuthority(String value, String field) {
        if (value == null
                || value.isBlank()
                || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw PortfolioException.conflict(
                    "The selected snapshot has no safe immutable "
                            + field + " authority");
        }
    }

    private static DiagramModel freeze(DiagramModel diagram) {
        if (diagram == null
                || diagram.nodes() == null
                || diagram.edges() == null
                || diagram.layout() == null) {
            throw PortfolioException.conflict(
                    "The selected snapshot has no complete canonical diagram");
        }
        if (diagram.nodes().isEmpty()) {
            throw PortfolioException.conflict(
                    "The selected snapshot has no exportable architecture elements");
        }
        if (diagram.nodes().stream().anyMatch(Objects::isNull)
                || diagram.edges().stream().anyMatch(Objects::isNull)) {
            throw PortfolioException.conflict(
                    "The selected snapshot contains an incomplete canonical graph");
        }
        return new DiagramModel(
                diagram.title(),
                List.copyOf(diagram.nodes()),
                List.copyOf(diagram.edges()),
                diagram.layout());
    }

    private static String fileName(Projection projection, Format format) {
        return "architecture-"
                + safeFileToken(projection.snapshotId(), 48)
                + "-"
                + safeFileToken(projection.commitSha(), 12)
                + "."
                + format.extension();
    }

    private static String safeFileToken(String value, int maximumLength) {
        String normalized = value.replaceAll("[^A-Za-z0-9._-]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[.-]+|[.-]+$", "");
        if (normalized.isBlank()) {
            normalized = "snapshot";
        }
        return normalized.substring(
                0,
                Math.min(maximumLength, normalized.length()));
    }

    /**
     * Fingerprints the semantic graph subset independently of display title,
     * layout options and collection order. Container-only nodes, relationships
     * touching them, visual container-parent references and node layer positions
     * are presentation data and are therefore excluded. Format bytes retain
     * their own independent artifact hash and HTTP ETag.
     */
    private static String fingerprint(DiagramModel diagram) {
        MessageDigest digest = sha256Digest();
        putText(digest, FINGERPRINT_VERSION);

        Set<String> semanticNodeIds = diagram.nodes().stream()
                .filter(node -> !node.container())
                .map(DiagramNode::id)
                .collect(Collectors.toUnmodifiableSet());
        List<DiagramNode> nodes = diagram.nodes().stream()
                .filter(node -> semanticNodeIds.contains(node.id()))
                .sorted(Comparator.comparing(DiagramNode::id))
                .toList();
        putInt(digest, nodes.size());
        for (DiagramNode node : nodes) {
            putText(digest, node.id());
            putText(digest, node.label());
            putText(digest, node.type());
            putDouble(digest, node.relevance());
            putBoolean(digest, node.anchor());
            putInt(digest, node.depth());
            putBoolean(digest, node.selectedForImpact());
            String parentId = node.parentId();
            putText(
                    digest,
                    parentId != null && semanticNodeIds.contains(parentId)
                            ? parentId
                            : null);
        }

        List<DiagramEdge> edges = diagram.edges().stream()
                .filter(edge -> semanticNodeIds.contains(edge.sourceId())
                        && semanticNodeIds.contains(edge.targetId()))
                .sorted(Comparator.comparing(DiagramEdge::id))
                .toList();
        putInt(digest, edges.size());
        for (DiagramEdge edge : edges) {
            putText(digest, edge.id());
            putText(digest, edge.sourceId());
            putText(digest, edge.targetId());
            putText(digest, edge.relationType());
            putDouble(digest, edge.relevance());
            putText(digest, edge.relationCategory());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] content) {
        MessageDigest digest = sha256Digest();
        return HexFormat.of().formatHex(digest.digest(content));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception);
        }
    }

    private static void putText(MessageDigest digest, String value) {
        if (value == null) {
            putInt(digest, -1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        putInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void putBoolean(MessageDigest digest, boolean value) {
        digest.update((byte) (value ? 1 : 0));
    }

    private static void putInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void putDouble(MessageDigest digest, double value) {
        putLong(digest, Double.doubleToLongBits(value));
    }

    private static void putLong(MessageDigest digest, long value) {
        digest.update((byte) (value >>> 56));
        digest.update((byte) (value >>> 48));
        digest.update((byte) (value >>> 40));
        digest.update((byte) (value >>> 32));
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    public enum Format {
        ARCHIMATE_XML(
                "application/xml",
                "archimate.xml",
                "archimate-exchange-3.1-supported-subset-v1"),
        VISIO_VSDX(
                "application/vnd.ms-visio.drawing",
                "vsdx",
                "visio-2012-opc-supported-subset-v1");

        private final String mediaType;
        private final String extension;
        private final String profile;

        Format(String mediaType, String extension, String profile) {
            this.mediaType = mediaType;
            this.extension = extension;
            this.profile = profile;
        }

        public String mediaType() {
            return mediaType;
        }

        public String extension() {
            return extension;
        }

        public String profile() {
            return profile;
        }
    }

    public record Artifact(
            Format format,
            String fileName,
            String mediaType,
            String exporterProfile,
            Long projectId,
            Long requirementId,
            String snapshotId,
            AnalysisStatus snapshotStatus,
            String workspaceId,
            String branchName,
            String commitSha,
            String provider,
            String modelName,
            String canonicalGraphSha256,
            String artifactSha256,
            byte[] content) {

        public Artifact {
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(mediaType, "mediaType");
            Objects.requireNonNull(exporterProfile, "exporterProfile");
            Objects.requireNonNull(snapshotId, "snapshotId");
            Objects.requireNonNull(snapshotStatus, "snapshotStatus");
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(branchName, "branchName");
            Objects.requireNonNull(commitSha, "commitSha");
            Objects.requireNonNull(
                    canonicalGraphSha256,
                    "canonicalGraphSha256");
            Objects.requireNonNull(artifactSha256, "artifactSha256");
            Objects.requireNonNull(content, "content");
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
