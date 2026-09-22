package com.taxonomy.portfolio;

import com.taxonomy.TaxonomyApplication;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Real transactional persistence checks; input is a stored-result fixture, not an LLM run. */
public final class PortfolioPartialDiagnosticsChecks {
    private PortfolioPartialDiagnosticsChecks() { }

    public static void main(String[] args) {
        try (var app = new SpringApplicationBuilder(TaxonomyApplication.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--embedding.enabled=false",
                "--embedding.allow-download=false", "--taxonomy.admin-password=Diagnostic-Local-2026!",
                "--spring.datasource.url=jdbc:hsqldb:mem:partialdiagnostic", "--logging.level.root=WARN")) {
            var projects = app.getBean(ProjectPortfolioService.class);
            var persistence = app.getBean(PortfolioAnalysisPersistenceService.class);
            int failures = 0;
            for (String name : List.of("error", "warning", "bounded", "bounded-warning", "unicode", "boundary", "success", "unknown")) {
                try { check(projects, persistence, name); System.out.println("PASS " + name); }
                catch (AssertionError | RuntimeException failure) { failures++; System.err.println("FAIL " + name + ": " + failure.getMessage()); }
            }
            if (failures != 0) throw new AssertionError(failures + " diagnostic checks failed");
            System.out.println("PORTFOLIO_PARTIAL_DIAGNOSTICS_OK");
        }
    }

    public static void check(ProjectPortfolioService projects,
                             PortfolioAnalysisPersistenceService persistence, String name) {
        String error = switch (name) {
            case "error" -> "MEMORY_PRESSURE: Analysis stopped cooperatively";
            case "bounded" -> "Incomplete analysis for IP: " + "detail ".repeat(500);
            case "unicode" -> "x".repeat(1987) + "😀" + "y".repeat(100);
            case "boundary" -> "x".repeat(2000);
            case "success" -> "stale message must not appear as job failure";
            default -> null;
        };
        List<String> warnings = name.equals("warning")
                ? Arrays.asList(null, " ", "Incomplete analysis for IP: Expected a JSON object", "Second warning")
                : List.of("other warning");
        if (name.equals("unknown")) warnings = null;
        if (name.equals("bounded-warning")) warnings = List.of("w".repeat(3000));
        AnalysisStatus status = name.equals("success") ? AnalysisStatus.SUCCESS : AnalysisStatus.PARTIAL;
        String expected = switch (name) {
            case "error", "boundary" -> error;
            case "unicode" -> "x".repeat(1987) + "\n[truncated]";
            case "bounded-warning" -> "w".repeat(1988) + "\n[truncated]";
            case "bounded" -> error.substring(0, 1988) + "\n[truncated]";
            case "warning" -> warnings.get(2);
            default -> null;
        };
        String actor = "diagnostic-test";
        var scope = new WorkspaceContext(actor, "partial-" + UUID.randomUUID(), "draft");
        var project = projects.createProject(new CreateProjectRequest("DIAG", "Diagnostic test", null,
                null, null, null, null, null), actor, scope);
        String original = "Provide readable explanations for incomplete civilian information-product analyses.";
        var requirement = projects.createRequirement(project.id(), new CreateRequirementRequest("CIV-1",
                "Diagnostic requirement", original, null, null, null, null, null, null, null, null), actor, scope);
        var job = persistence.createOrReuseJob(project.id(), List.of(requirement.id()), null, 25,
                "diagnostic", actor, scope);
        String key = PortfolioScope.key(actor, scope);
        persistence.markJobRunning(job.id(), project.id(), key);
        long itemId = job.items().getFirst().id();
        persistence.markItemRunning(itemId, job.id(), project.id(), key);
        var analysis = new AnalysisResult(Map.of("IP", 40), List.of());
        analysis.setStatus(status.name()); analysis.setErrorMessage(error); analysis.setWarnings(warnings);
        String snapshotId = UUID.randomUUID().toString();
        persistence.persistSnapshot(itemId, job.id(), project.id(), key, snapshotId, "diagnostic:" + snapshotId,
                analysis, null, null, null, "fixture", null, "prompt", "taxonomy", actor, scope, 1L);
        // Separate calls open separate transactions. The snapshot remains the full evidence.
        persistence.completeJob(job.id(), project.id(), key);
        var saved = persistence.getJob(job.id(), project.id(), actor, scope);
        var snapshot = persistence.getSnapshot(project.id(), snapshotId, actor, scope);
        require(saved.status() == status, "The original job outcome changed");
        require(saved.items().getFirst().status() == status, "The original item outcome changed");
        require(Objects.equals(expected, saved.items().getFirst().errorMessage()),
                "Lost or changed item reason: expected=" + expected + ", actual=" + saved.items().getFirst().errorMessage());
        require(expected == null ? saved.errorSummary() == null
                : saved.errorSummary() != null && saved.errorSummary().startsWith("CIV-1: " + expected.substring(0, Math.min(100, expected.length()))),
                "Aggregate job loses the partial reason");
        require(saved.items().getFirst().errorMessage() == null
                || saved.items().getFirst().errorMessage().length() <= 2000, "Item summary exceeds column bound");
        require(saved.errorSummary() == null || saved.errorSummary().length() <= 2000, "Job summary exceeds column bound");
        require(snapshot.summary().errorMessage() == null || snapshot.summary().errorMessage().length() <= 2000,
                "Snapshot summary exceeds column bound");
        require(Objects.equals(error, snapshot.analysis().getErrorMessage()), "Snapshot error was modified");
        require(Objects.equals(analysis.getWarnings(), snapshot.analysis().getWarnings()), "Snapshot warnings were modified");
        require(snapshot.analysis().getScores().equals(analysis.getScores()), "Partial scores were modified");
        require(saved.items().getFirst().requirementVersionId().equals(requirement.currentVersionId()), "Original version changed");
        try {
            persistence.getJob(job.id(), project.id(), actor, new WorkspaceContext(actor, "other-workspace", "draft"));
            throw new AssertionError("Foreign workspace must not see diagnostic text");
        } catch (PortfolioException denied) {
            require(denied.getKind() == PortfolioException.Kind.NOT_FOUND, "Unexpected scope denial");
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
