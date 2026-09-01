package com.taxonomy.portfolio.workbench;

import com.taxonomy.archimate.ArchiMateModel;
import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.export.ArchiMateDiagramService;
import com.taxonomy.export.ArchiMateXmlExporter;
import com.taxonomy.export.MermaidExportService;
import com.taxonomy.export.StructurizrExportService;
import com.taxonomy.export.SvgDiagramRenderer;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.Projection;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Renders hand-off artefacts from one already persisted architecture snapshot.
 *
 * <p>This service deliberately has no LLM dependency. Every format is derived from
 * the exact {@link Projection#diagram()} loaded by the architecture workbench, so
 * downloading a file cannot trigger or substitute a new analysis.</p>
 */
@Service
public class ArchitectureSnapshotExportService {

    private static final Pattern SNAPSHOT_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final ArchitectureWorkbenchService workbenchService;
    private final SvgDiagramRenderer svgRenderer;
    private final ArchitecturePdfRenderer pdfRenderer;
    private final ArchiMateDiagramService archiMateDiagramService;
    private final ArchiMateXmlExporter archiMateXmlExporter;
    private final MermaidExportService mermaidExportService;
    private final StructurizrExportService structurizrExportService;
    private final ObjectMapper objectMapper;

    public ArchitectureSnapshotExportService(
            ArchitectureWorkbenchService workbenchService,
            SvgDiagramRenderer svgRenderer,
            ArchitecturePdfRenderer pdfRenderer,
            ArchiMateDiagramService archiMateDiagramService,
            ArchiMateXmlExporter archiMateXmlExporter,
            MermaidExportService mermaidExportService,
            StructurizrExportService structurizrExportService,
            ObjectMapper objectMapper) {
        this.workbenchService = workbenchService;
        this.svgRenderer = svgRenderer;
        this.pdfRenderer = pdfRenderer;
        this.archiMateDiagramService = archiMateDiagramService;
        this.archiMateXmlExporter = archiMateXmlExporter;
        this.mermaidExportService = mermaidExportService;
        this.structurizrExportService = structurizrExportService;
        this.objectMapper = objectMapper;
    }

    public SnapshotArtifact export(
            Long projectId,
            String snapshotId,
            String username,
            WorkspaceContext context,
            String formatId) {
        if (projectId == null || projectId <= 0) {
            throw PortfolioException.validation("projectId must be a positive integer");
        }
        String normalizedSnapshotId = normalizeSnapshotId(snapshotId);
        ExportFormat format = ExportFormat.fromId(formatId);

        Projection projection = workbenchService.load(
                projectId, normalizedSnapshotId, username, context);
        if (projection == null || projection.diagram() == null) {
            throw PortfolioException.conflict(
                    "Snapshot " + normalizedSnapshotId
                            + " contains no persisted architecture diagram");
        }
        if (!normalizedSnapshotId.equals(projection.snapshotId())) {
            throw new IllegalStateException(
                    "Loaded architecture snapshot does not match the requested snapshot ID");
        }

        String graphSha256 = graphFingerprint(projection.diagram());
        byte[] content = render(format, projection, graphSha256);
        return new SnapshotArtifact(
                format,
                content,
                projection.snapshotId(),
                projection.commitSha(),
                graphSha256,
                sha256(content));
    }

    private byte[] render(
            ExportFormat format, Projection projection, String graphSha256) {
        DiagramModel diagram = projection.diagram();
        return switch (format) {
            case JSON -> json(projection, graphSha256);
            case SVG -> utf8(svgRenderer.render(projection.scene()));
            case PDF -> pdfRenderer.render(projection);
            case ARCHIMATE -> {
                ArchiMateModel model = archiMateDiagramService.convert(diagram);
                yield archiMateXmlExporter.export(model);
            }
            case MERMAID -> utf8(mermaidExportService.export(diagram));
            case STRUCTURIZR -> utf8(structurizrExportService.export(diagram));
        };
    }

    private byte[] json(Projection projection, String graphSha256) {
        SnapshotEvidence evidence = new SnapshotEvidence(
                1,
                projection.snapshotId(),
                projection.commitSha(),
                graphSha256,
                projection.projectId(),
                projection.requirementId(),
                projection.requirementVersionId(),
                projection.requirementVersionNumber(),
                projection.requirementContentHash(),
                projection.workspaceId(),
                projection.branchName(),
                projection.provider(),
                projection.modelName(),
                projection.taxonomyFingerprint(),
                projection.promptFingerprint(),
                exportProfiles(),
                projection);
        try {
            return utf8(objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(evidence));
        } catch (JacksonException error) {
            throw new IllegalStateException(
                    "Unable to render architecture snapshot evidence JSON", error);
        }
    }

    private static List<ExportProfile> exportProfiles() {
        return Arrays.stream(ExportFormat.values())
                .map(format -> new ExportProfile(
                        format.id(),
                        format.mediaType(),
                        format.fileName(),
                        format.profileId(),
                        format.handoffRole()))
                .toList();
    }

    static String graphFingerprint(DiagramModel diagram) {
        Objects.requireNonNull(diagram, "diagram");
        StringBuilder canonical = new StringBuilder("taxonomy-architecture-graph-v1\n");

        safe(diagram.nodes()).stream()
                .sorted(Comparator
                        .comparing((DiagramNode node) -> value(node.id()))
                        .thenComparing(node -> value(node.type()))
                        .thenComparing(node -> value(node.label())))
                .forEach(node -> appendRecord(canonical, "node",
                        node.id(),
                        node.label(),
                        node.type(),
                        Double.toHexString(node.relevance()),
                        Boolean.toString(node.anchor()),
                        Integer.toString(node.layer()),
                        Integer.toString(node.depth()),
                        Boolean.toString(node.selectedForImpact()),
                        node.parentId(),
                        Boolean.toString(node.container())));

        safe(diagram.edges()).stream()
                .sorted(Comparator
                        .comparing((DiagramEdge edge) -> value(edge.id()))
                        .thenComparing(edge -> value(edge.sourceId()))
                        .thenComparing(edge -> value(edge.targetId()))
                        .thenComparing(edge -> value(edge.relationType())))
                .forEach(edge -> appendRecord(canonical, "edge",
                        edge.id(),
                        edge.sourceId(),
                        edge.targetId(),
                        edge.relationType(),
                        Double.toHexString(edge.relevance()),
                        edge.relationCategory()));

        return sha256(utf8(canonical.toString()));
    }

    private static void appendRecord(
            StringBuilder target, String recordType, String... fields) {
        target.append(recordType).append('|');
        for (String field : fields) {
            String normalized = value(field);
            target.append(normalized.length()).append(':').append(normalized).append('|');
        }
        target.append('\n');
    }

    private static String normalizeSnapshotId(String value) {
        String normalized = value(value).strip();
        if (!SNAPSHOT_ID.matcher(normalized).matches()) {
            throw PortfolioException.validation("snapshotId is invalid");
        }
        return normalized;
    }

    private static byte[] utf8(String value) {
        return value(value).getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    public enum ExportFormat {
        JSON("json", "application/json", "architecture-snapshot.json",
                "taxonomy-snapshot-evidence-v1", "canonical-evidence"),
        SVG("svg", "image/svg+xml", "architecture.svg",
                "taxonomy-snapshot-svg-v1", "stable-human-view"),
        PDF("pdf", "application/pdf", "architecture.pdf",
                "taxonomy-snapshot-pdf-v1", "stable-human-view"),
        ARCHIMATE("archimate", "application/xml", "architecture.xml",
                "archimate-exchange-3.1-taxonomy-v1", "experimental-model-exchange"),
        MERMAID("mermaid", "text/plain;charset=UTF-8", "architecture.mmd",
                "mermaid-flowchart-taxonomy-v1", "lossy-text-projection"),
        STRUCTURIZR("structurizr", "text/plain;charset=UTF-8", "architecture.dsl",
                "structurizr-dsl-taxonomy-v1", "lossy-text-projection");

        private final String id;
        private final String mediaType;
        private final String fileName;
        private final String profileId;
        private final String handoffRole;

        ExportFormat(
                String id,
                String mediaType,
                String fileName,
                String profileId,
                String handoffRole) {
            this.id = id;
            this.mediaType = mediaType;
            this.fileName = fileName;
            this.profileId = profileId;
            this.handoffRole = handoffRole;
        }

        public String id() {
            return id;
        }

        public String mediaType() {
            return mediaType;
        }

        public String fileName() {
            return fileName;
        }

        public String profileId() {
            return profileId;
        }

        public String handoffRole() {
            return handoffRole;
        }

        static ExportFormat fromId(String value) {
            String normalized = ArchitectureSnapshotExportService.value(value)
                    .strip().toLowerCase(Locale.ROOT);
            for (ExportFormat format : values()) {
                if (format.id.equals(normalized)) {
                    return format;
                }
            }
            throw PortfolioException.validation(
                    "Unsupported architecture export format: " + normalized);
        }
    }

    public record ExportProfile(
            String formatId,
            String mediaType,
            String fileName,
            String profileId,
            String handoffRole) {
    }

    public record SnapshotEvidence(
            int schemaVersion,
            String snapshotId,
            String commitSha,
            String graphSha256,
            Long projectId,
            Long requirementId,
            Long requirementVersionId,
            int requirementVersionNumber,
            String requirementContentHash,
            String workspaceId,
            String branch,
            String provider,
            String modelName,
            String taxonomyFingerprint,
            String promptFingerprint,
            List<ExportProfile> exportProfiles,
            Projection projection) {
    }

    public record SnapshotArtifact(
            ExportFormat format,
            byte[] content,
            String snapshotId,
            String commitSha,
            String graphSha256,
            String contentSha256) {

        public SnapshotArtifact {
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(snapshotId, "snapshotId");
            Objects.requireNonNull(graphSha256, "graphSha256");
            Objects.requireNonNull(contentSha256, "contentSha256");
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
