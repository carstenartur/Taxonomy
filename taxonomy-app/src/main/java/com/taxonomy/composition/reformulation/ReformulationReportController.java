package com.taxonomy.composition.reformulation;

import com.taxonomy.export.reformulation.ReformulationReportRenderer;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.portfolio.reformulation.ReformulationReportService;
import com.taxonomy.portfolio.reformulation.ReformulationReportService.Report;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

/** Explicit historical revision/receipt downloads; no live architecture or model lookup. */
@RestController
@RequestMapping("/api/projects/{projectId}/requirements/{requirementId}/reformulations/{proposalId}")
public class ReformulationReportController {
    private final ReformulationReportService reports;
    private final WorkspaceResolver resolver;
    private final ObjectMapper json;

    public ReformulationReportController(ReformulationReportService reports, WorkspaceResolver resolver, ObjectMapper json) {
        this.reports = reports;
        this.resolver = resolver;
        this.json = json.rebuild().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    }

    @GetMapping("/revisions/{revision}/export")
    public ResponseEntity<byte[]> revision(@PathVariable Long projectId, @PathVariable Long requirementId,
            @PathVariable String proposalId, @PathVariable long revision, @RequestParam(defaultValue = "json") String format) {
        return response(reports.revision(projectId, requirementId, proposalId, revision,
                resolver.resolveCurrentUsername(), resolver.resolveCurrentContext()), format);
    }

    @GetMapping("/adoptions/{commandId}/export")
    public ResponseEntity<byte[]> adoption(@PathVariable Long projectId, @PathVariable Long requirementId,
            @PathVariable String proposalId, @PathVariable String commandId, @RequestParam(defaultValue = "json") String format) {
        return response(reports.adoption(projectId, requirementId, proposalId, commandId,
                resolver.resolveCurrentUsername(), resolver.resolveCurrentContext()), format);
    }

    private ResponseEntity<byte[]> response(Report report, String format) {
        MediaType type = switch (format) {
            case "json" -> new MediaType("application", "json", StandardCharsets.UTF_8);
            case "md" -> new MediaType("text", "markdown", StandardCharsets.UTF_8);
            case "html" -> new MediaType("text", "html", StandardCharsets.UTF_8);
            default -> throw PortfolioException.validation("Supported report formats: json, md, html");
        };
        String evidence = json.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        var revision = report.revision();
        var metadata = new TreeMap<String, String>();
        metadata.put("Proposal", report.proposalId());
        metadata.put("Project / Requirement", report.source().projectId() + " / " + report.source().requirementId());
        metadata.put("Source version", Long.toString(report.source().versionId()));
        metadata.put("Source SHA-256", report.source().originalTextHash());
        metadata.put("Analysis snapshot", report.source().analysisSnapshotId());
        metadata.put("Proposal revision", Long.toString(revision.number()));
        metadata.put("Saved by", revision.actor());
        metadata.put("Saved at", revision.createdAt().toString());
        metadata.put("Revision rationale", revision.rationale());
        String identity = "revision-" + revision.number();
        if (report.adoption() != null) {
            var adopted = report.adoption();
            metadata.put("Adoption command", adopted.commandId());
            metadata.put("Adopted by", adopted.actor());
            metadata.put("Adopted at", adopted.adoptedAt().toString());
            metadata.put("Adoption rationale", adopted.rationale());
            metadata.put("Target version", Long.toString(adopted.targetVersionId()));
            metadata.put("Warnings acknowledged", Boolean.toString(adopted.warningsAcknowledged()));
            metadata.put("Unresolved question IDs", adopted.unresolvedQuestionIds().toString());
            metadata.put("Preview SHA-256", report.preview().hash());
            identity = "adoption-" + adopted.commandId();
        }
        var input = new ReformulationReportRenderer.Input(report.source().language(), report.adoption() != null,
                metadata, report.source().originalText(), report.reviewedText(), revision.sections(), revision.statements(),
                revision.questions(), revision.answers(), revision.validation(), evidence);
        String body = switch (format) {
            case "json" -> evidence + "\n";
            case "md" -> ReformulationReportRenderer.markdown(input);
            case "html" -> ReformulationReportRenderer.html(input);
            default -> throw new IllegalStateException("Validated format changed");
        };
        // Identity comes only from the authorized persisted record; no user-supplied title in headers.
        String filename = "reformulation-" + report.proposalId() + "-" + identity + "." + format;
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'")
                .header("X-Content-SHA256", StableIdentityHash.sha256(body))
                .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
