package com.taxonomy.portfolio.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taxonomy.portfolio.dto.PortfolioDtos.ConflictView;
import com.taxonomy.portfolio.dto.PortfolioDtos.MatrixView;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectPortfolioView;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectSolutionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.model.RequirementAnalysisSnapshot;
import com.taxonomy.portfolio.repository.RequirementAnalysisSnapshotRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Renders human-readable and machine-readable reports from one exact portfolio view. */
@Service
public class PortfolioReportService {

    public enum Format {
        MARKDOWN("text/markdown", "md"),
        HTML("text/html", "html"),
        DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
        JSON("application/json", "json"),
        CSV("text/csv", "csv");

        private final String contentType;
        private final String extension;

        Format(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        public String contentType() { return contentType; }
        public String extension() { return extension; }
    }

    public record RenderedReport(byte[] bytes, String contentType, String filename) {
    }

    private final PortfolioAggregationService aggregationService;
    private final RequirementAnalysisSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    public PortfolioReportService(PortfolioAggregationService aggregationService,
                                  RequirementAnalysisSnapshotRepository snapshotRepository,
                                  ObjectMapper objectMapper) {
        this.aggregationService = aggregationService;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public RenderedReport render(Long projectId,
                                 Long requirementId,
                                 Format format,
                                 String matrix,
                                 String username,
                                 WorkspaceContext context) {
        ProjectPortfolioView portfolio = aggregationService.build(projectId, username, context);
        ReportModel model = buildModel(portfolio, requirementId);
        String baseName = safeFilename(portfolio.project().projectKey()
                + (requirementId != null ? "-" + model.requirements().get(0).requirementKey() : "-portfolio"));
        return switch (format) {
            case MARKDOWN -> bytes(markdown(model), format, baseName);
            case HTML -> bytes(html(model), format, baseName);
            case JSON -> bytes(json(model), format, baseName);
            case CSV -> bytes(csv(model, matrix), format, baseName + "-" + normalizeMatrix(matrix));
            case DOCX -> new RenderedReport(docx(model), format.contentType(), baseName + "." + format.extension());
        };
    }

    private RenderedReport bytes(String content, Format format, String filename) {
        return new RenderedReport(
                content.getBytes(StandardCharsets.UTF_8),
                format.contentType(),
                filename + "." + format.extension());
    }

    private ReportModel buildModel(ProjectPortfolioView portfolio, Long requirementId) {
        List<RequirementView> requirements = requirementId == null
                ? portfolio.requirements()
                : portfolio.requirements().stream()
                        .filter(requirement -> requirement.id().equals(requirementId))
                        .toList();
        if (requirements.isEmpty()) {
            throw PortfolioException.notFound("Requirement not found in project: " + requirementId);
        }
        List<Long> requirementIds = requirements.stream().map(RequirementView::id).toList();
        List<ProjectSolutionView> solutions = portfolio.solutions().stream()
                .filter(solution -> requirementId == null
                        || solution.requirements().stream()
                                .anyMatch(link -> requirementIds.contains(link.requirementId())))
                .toList();
        List<ConflictView> conflicts = portfolio.conflicts().stream()
                .filter(conflict -> requirementId == null
                        || requirementIds.contains(conflict.requirementAId())
                        || requirementIds.contains(conflict.requirementBId()))
                .toList();
        List<SnapshotBaseline> baselines = new ArrayList<>();
        for (RequirementView requirement : requirements) {
            if (requirement.currentAnalysisSnapshotId() == null) continue;
            snapshotRepository.findByIdAndProjectId(
                            requirement.currentAnalysisSnapshotId(), portfolio.project().id())
                    .map(snapshot -> baseline(requirement, snapshot))
                    .ifPresent(baselines::add);
        }
        return new ReportModel(
                requirementId == null ? "PROJECT" : "REQUIREMENT",
                Instant.now(),
                portfolio.project(),
                portfolio.metrics(),
                requirements,
                portfolio.taxonomyNodes(),
                solutions,
                conflicts,
                portfolio.requirementTaxonomyMatrix(),
                portfolio.requirementSolutionMatrix(),
                portfolio.solutionProductMatrix(),
                baselines);
    }

    private SnapshotBaseline baseline(RequirementView requirement,
                                      RequirementAnalysisSnapshot snapshot) {
        return new SnapshotBaseline(
                requirement.id(), requirement.requirementKey(), snapshot.getId(),
                snapshot.getRequirementVersion().getVersionNumber(), snapshot.getProvider(),
                snapshot.getModelName(), snapshot.getTaxonomyFingerprint(),
                snapshot.getPromptFingerprint(), snapshot.getBranchName(),
                snapshot.getCommitSha(), snapshot.getCreatedAt());
    }

    private String json(ReportModel model) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to render portfolio report JSON", error);
        }
    }

    private String markdown(ReportModel model) {
        StringBuilder output = new StringBuilder();
        output.append("# ").append(model.reportType().equals("PROJECT")
                ? "Project portfolio report" : "Requirement report").append("\n\n");
        output.append("- **Project:** ").append(model.project().projectKey())
                .append(" — ").append(model.project().title()).append("\n");
        output.append("- **Generated:** ").append(model.generatedAt()).append("\n");
        output.append("- **Workspace:** ").append(nullSafe(model.project().workspaceId())).append("\n\n");
        output.append("## Management summary\n\n");
        output.append("- Requirements: ").append(model.requirements().size()).append("\n");
        output.append("- Analysed: ").append(model.metrics().analyzedRequirements()).append("\n");
        output.append("- Requirements without confirmed solution: ")
                .append(model.metrics().requirementsWithoutConfirmedSolution()).append("\n");
        output.append("- Solutions: ").append(model.solutions().size()).append("\n");
        output.append("- Selected products: ").append(model.metrics().selectedProducts()).append("\n");
        output.append("- Open conflicts: ").append(model.conflicts().stream()
                .filter(conflict -> conflict.status().name().equals("PROPOSED")
                        || conflict.status().name().equals("CONFIRMED"))
                .count()).append("\n\n");

        output.append("## Requirements\n\n");
        for (RequirementView requirement : model.requirements()) {
            output.append("### ").append(requirement.requirementKey()).append(" — ")
                    .append(requirement.title()).append("\n\n");
            output.append(requirement.currentVersion() != null
                    ? requirement.currentVersion().text() : "No current text").append("\n\n");
            if (requirement.currentVersion() != null && requirement.currentVersion().source() != null) {
                var source = requirement.currentVersion().source();
                output.append("**Source:** artifact ").append(nullSafe(source.sourceArtifactId()))
                        .append(", section ").append(nullSafe(source.sectionReference()))
                        .append(", page ").append(nullSafe(source.pageNumber())).append("\n\n");
            }
        }

        output.append("## Taxonomy coverage\n\n");
        output.append("| Node | Title | Requirements | Max score | Average relevance |\n")
                .append("|---|---|---:|---:|---:|\n");
        model.taxonomyNodes().forEach(node -> output.append('|').append(node.nodeCode())
                .append('|').append(escapeMarkdown(node.title())).append('|')
                .append(node.requirementCount()).append('|').append(node.maximumDirectScore())
                .append("%|").append(Math.round(node.averageRelevance() * 100)).append("%|\n"));
        output.append("\n## Solutions and products\n\n");
        for (ProjectSolutionView solution : model.solutions()) {
            output.append("### ").append(solution.solution().solutionKey()).append(" — ")
                    .append(solution.solution().title()).append("\n\n")
                    .append("- Status: ").append(solution.status()).append("\n")
                    .append("- Action: ").append(solution.actionStatus()).append("\n")
                    .append("- Requirements: ")
                    .append(solution.requirements().stream()
                            .map(link -> link.requirementKey() + " (" + link.coveragePercent() + "%)")
                            .reduce((left, right) -> left + ", " + right).orElse("—"))
                    .append("\n")
                    .append("- Product candidates: ")
                    .append(solution.productCandidates().stream()
                            .map(candidate -> candidate.product().productKey() + " ("
                                    + candidate.selectionStatus() + ")")
                            .reduce((left, right) -> left + ", " + right).orElse("—"))
                    .append("\n\n");
        }

        output.append("## Conflicts and open decisions\n\n");
        if (model.conflicts().isEmpty()) output.append("No conflict hypotheses recorded.\n\n");
        for (ConflictView conflict : model.conflicts()) {
            output.append("- **").append(conflict.requirementAKey()).append(" ↔ ")
                    .append(conflict.requirementBKey()).append(":** ")
                    .append(conflict.title()).append(" — ").append(conflict.status())
                    .append(". ").append(nullSafe(conflict.evidence())).append("\n");
        }

        output.append("\n## Reproducibility baseline\n\n");
        output.append("| Requirement | Snapshot | Version | Provider/model | Taxonomy | Prompt | Branch/commit |\n")
                .append("|---|---|---:|---|---|---|---|\n");
        model.baselines().forEach(baseline -> output.append('|')
                .append(baseline.requirementKey()).append('|').append(baseline.snapshotId())
                .append('|').append(baseline.requirementVersion()).append('|')
                .append(nullSafe(baseline.provider())).append('/').append(nullSafe(baseline.modelName()))
                .append('|').append(shortHash(baseline.taxonomyFingerprint()))
                .append('|').append(shortHash(baseline.promptFingerprint()))
                .append('|').append(nullSafe(baseline.branchName())).append('/')
                .append(shortHash(baseline.commitSha())).append("|\n"));
        return output.toString();
    }

    private String html(ReportModel model) {
        String markdown = markdown(model);
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(escapeHtml(model.project().projectKey())).append(" report</title>")
                .append("<style>body{font:16px system-ui;max-width:1100px;margin:auto;padding:2rem;line-height:1.5}")
                .append("table{border-collapse:collapse;width:100%;margin:1rem 0}th,td{border:1px solid #777;padding:.4rem;text-align:left}")
                .append("code{overflow-wrap:anywhere}.meta{color:#555}</style></head><body>");
        for (String line : markdown.split("\\R", -1)) {
            if (line.startsWith("# ")) html.append("<h1>").append(escapeHtml(line.substring(2))).append("</h1>");
            else if (line.startsWith("## ")) html.append("<h2>").append(escapeHtml(line.substring(3))).append("</h2>");
            else if (line.startsWith("### ")) html.append("<h3>").append(escapeHtml(line.substring(4))).append("</h3>");
            else if (line.startsWith("- ")) html.append("<p>• ").append(escapeHtml(line.substring(2))).append("</p>");
            else if (!line.startsWith("|") && !line.isBlank()) html.append("<p>").append(escapeHtml(line)).append("</p>");
        }
        html.append("<h2>Machine-readable baseline</h2><pre><code>")
                .append(escapeHtml(json(model))).append("</code></pre></body></html>");
        return html.toString();
    }

    private byte[] docx(ReportModel model) {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            title(document, model.reportType().equals("PROJECT")
                    ? "Project portfolio report" : "Requirement report", 1);
            paragraph(document, model.project().projectKey() + " — " + model.project().title(), true);
            paragraph(document, "Generated: " + model.generatedAt(), false);
            title(document, "Management summary", 2);
            bullet(document, "Requirements: " + model.requirements().size());
            bullet(document, "Analysed: " + model.metrics().analyzedRequirements());
            bullet(document, "Requirements without confirmed solution: "
                    + model.metrics().requirementsWithoutConfirmedSolution());
            bullet(document, "Solutions: " + model.solutions().size());
            bullet(document, "Selected products: " + model.metrics().selectedProducts());
            title(document, "Requirements", 2);
            for (RequirementView requirement : model.requirements()) {
                title(document, requirement.requirementKey() + " — " + requirement.title(), 3);
                paragraph(document, requirement.currentVersion() != null
                        ? requirement.currentVersion().text() : "No current text", false);
                if (requirement.currentVersion() != null && requirement.currentVersion().source() != null) {
                    var source = requirement.currentVersion().source();
                    paragraph(document, "Source: section " + nullSafe(source.sectionReference())
                            + ", page " + nullSafe(source.pageNumber()), false);
                }
            }
            title(document, "Taxonomy coverage", 2);
            XWPFTable taxonomy = document.createTable(Math.max(1, model.taxonomyNodes().size() + 1), 5);
            tableHeader(taxonomy, "Node", "Title", "Requirements", "Max score", "Average relevance");
            for (int index = 0; index < model.taxonomyNodes().size(); index++) {
                var node = model.taxonomyNodes().get(index);
                tableRow(taxonomy, index + 1, node.nodeCode(), node.title(),
                        String.valueOf(node.requirementCount()), node.maximumDirectScore() + "%",
                        Math.round(node.averageRelevance() * 100) + "%");
            }
            title(document, "Solutions and products", 2);
            for (ProjectSolutionView solution : model.solutions()) {
                title(document, solution.solution().solutionKey() + " — "
                        + solution.solution().title(), 3);
                bullet(document, "Status: " + solution.status());
                bullet(document, "Action: " + solution.actionStatus());
                for (var link : solution.requirements()) {
                    bullet(document, link.requirementKey() + ": " + link.coveragePercent()
                            + "% (" + link.reviewStatus() + ")");
                }
                for (var candidate : solution.productCandidates()) {
                    bullet(document, candidate.product().manufacturer() + " "
                            + candidate.product().productName() + ": "
                            + candidate.selectionStatus() + ", source "
                            + candidate.product().sourceReference());
                }
            }
            title(document, "Conflicts", 2);
            for (ConflictView conflict : model.conflicts()) {
                bullet(document, conflict.requirementAKey() + " ↔ " + conflict.requirementBKey()
                        + ": " + conflict.title() + " — " + conflict.status());
            }
            title(document, "Reproducibility baseline", 2);
            XWPFTable baseline = document.createTable(Math.max(1, model.baselines().size() + 1), 6);
            tableHeader(baseline, "Requirement", "Snapshot", "Version", "Provider/model", "Taxonomy/prompt", "Branch/commit");
            for (int index = 0; index < model.baselines().size(); index++) {
                SnapshotBaseline item = model.baselines().get(index);
                tableRow(baseline, index + 1, item.requirementKey(), item.snapshotId(),
                        String.valueOf(item.requirementVersion()),
                        nullSafe(item.provider()) + "/" + nullSafe(item.modelName()),
                        shortHash(item.taxonomyFingerprint()) + "/" + shortHash(item.promptFingerprint()),
                        nullSafe(item.branchName()) + "/" + shortHash(item.commitSha()));
            }
            document.write(output);
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Unable to render portfolio DOCX report", error);
        }
    }

    private String csv(ReportModel model, String matrixName) {
        MatrixView matrix = switch (normalizeMatrix(matrixName)) {
            case "solutions" -> model.requirementSolutionMatrix();
            case "products" -> model.solutionProductMatrix();
            default -> model.requirementTaxonomyMatrix();
        };
        StringBuilder output = new StringBuilder("row");
        matrix.columns().forEach(column -> output.append(',').append(csvCell(column)));
        output.append('\n');
        for (String row : matrix.rows()) {
            output.append(csvCell(row));
            for (String column : matrix.columns()) {
                output.append(',').append(matrix.values().getOrDefault(row, Map.of())
                        .getOrDefault(column, 0));
            }
            output.append('\n');
        }
        return output.toString();
    }

    private static void title(XWPFDocument document, String text, int level) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle("Heading" + level);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        if (level == 1) {
            paragraph.setAlignment(ParagraphAlignment.CENTER);
            run.setFontSize(20);
        }
    }

    private static void paragraph(XWPFDocument document, String text, boolean bold) {
        XWPFRun run = document.createParagraph().createRun();
        run.setBold(bold);
        run.setText(nullSafe(text));
    }

    private static void bullet(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle("ListBullet");
        paragraph.createRun().setText(text);
    }

    private static void tableHeader(XWPFTable table, String... values) {
        tableRow(table, 0, values);
        for (var cell : table.getRow(0).getTableCells()) {
            cell.getParagraphs().get(0).getRuns().get(0).setBold(true);
        }
    }

    private static void tableRow(XWPFTable table, int rowIndex, String... values) {
        for (int column = 0; column < values.length; column++) {
            table.getRow(rowIndex).getCell(column).setText(nullSafe(values[column]));
        }
    }

    private static String normalizeMatrix(String value) {
        return switch (String.valueOf(value).toLowerCase()) {
            case "solution", "solutions", "requirement-solution" -> "solutions";
            case "product", "products", "solution-product" -> "products";
            default -> "taxonomy";
        };
    }

    private static String safeFilename(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return sanitized.isBlank() ? "taxonomy-report" : sanitized;
    }

    private static String csvCell(Object value) {
        String text = Objects.toString(value, "");
        return text.matches(".*[\",\\r\\n].*")
                ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }

    private static String shortHash(String value) {
        return value == null || value.isBlank() ? "—" : value.substring(0, Math.min(12, value.length()));
    }

    private static String nullSafe(Object value) {
        return value == null ? "—" : value.toString();
    }

    private static String escapeMarkdown(String value) {
        return nullSafe(value).replace("|", "\\|");
    }

    private static String escapeHtml(String value) {
        return nullSafe(value).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    public record SnapshotBaseline(
            Long requirementId,
            String requirementKey,
            String snapshotId,
            int requirementVersion,
            String provider,
            String modelName,
            String taxonomyFingerprint,
            String promptFingerprint,
            String branchName,
            String commitSha,
            Instant createdAt) {
    }

    public record ReportModel(
            String reportType,
            Instant generatedAt,
            com.taxonomy.portfolio.dto.PortfolioDtos.ProjectView project,
            com.taxonomy.portfolio.dto.PortfolioDtos.PortfolioMetrics metrics,
            List<RequirementView> requirements,
            List<com.taxonomy.portfolio.dto.PortfolioDtos.AggregatedTaxonomyNode> taxonomyNodes,
            List<ProjectSolutionView> solutions,
            List<ConflictView> conflicts,
            MatrixView requirementTaxonomyMatrix,
            MatrixView requirementSolutionMatrix,
            MatrixView solutionProductMatrix,
            List<SnapshotBaseline> baselines) {
    }
}
