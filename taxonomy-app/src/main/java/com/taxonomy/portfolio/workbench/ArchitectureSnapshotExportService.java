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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Authorizes an immutable architecture snapshot and exports its persisted
 * canonical graph without running analysis or applying current preferences.
 */
@Service
public class ArchitectureSnapshotExportService {

    private static final String FINGERPRINT_VERSION =
            "taxonomy-canonical-diagram-v1";

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
        String graphFingerprint = fingerprint(canonicalDiagram);
        byte[] content = switch (format) {
            case ARCHIMATE_XML ->
                    diagramExportService.exportAsArchiMate(canonicalDiagram);
            case VISIO_VSDX ->
                    diagramExportService.exportAsVisio(canonicalDiagram);
        };
        if (content == null || content.length == 0) {
            throw new IllegalStateException(
                    "The canonical diagram exporter returned no content");
        }

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

    private static String fingerprint(DiagramModel diagram) {
        MessageDigest digest = sha256Digest();
        putText(digest, FINGERPRINT_VERSION);
        putText(digest, diagram.title());
        putText(digest, diagram.layout().direction());
        putBoolean(digest, diagram.layout().groupByLayer());

        putInt(digest, diagram.nodes().size());
        for (DiagramNode node : diagram.nodes()) {
            if (node == null) {
                throw PortfolioException.conflict(
                        "The selected snapshot contains a null architecture element");
            }
            putText(digest, node.id());
            putText(digest, node.label());
            putText(digest, node.type());
            putDouble(digest, node.relevance());
            putBoolean(digest, node.anchor());
            putInt(digest, node.layer());
            putInt(digest, node.depth());
            putBoolean(digest, node.selectedForImpact());
            putText(digest, node.parentId());
            putBoolean(digest, node.container());
        }

        putInt(digest, diagram.edges().size());
        for (DiagramEdge edge : diagram.edges()) {
            if (edge == null) {
                throw PortfolioException.conflict(
                        "The selected snapshot contains a null architecture relation");
            }
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
        digest.update(
                ByteBuffer.allocate(Integer.BYTES)
                        .putInt(value)
                        .array());
    }

    private static void putDouble(MessageDigest digest, double value) {
        digest.update(
                ByteBuffer.allocate(Long.BYTES)
                        .putLong(Double.doubleToLongBits(value))
                        .array());
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
