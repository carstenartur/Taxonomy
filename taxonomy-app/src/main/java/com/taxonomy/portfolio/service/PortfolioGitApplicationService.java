package com.taxonomy.portfolio.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectView;
import com.taxonomy.portfolio.dto.PortfolioGitDtos.ExportedPortfolioDsl;
import com.taxonomy.portfolio.dto.PortfolioGitDtos.MaterializationPreview;
import com.taxonomy.portfolio.dto.PortfolioGitDtos.MaterializePortfolioResult;
import com.taxonomy.portfolio.dto.PortfolioGitDtos.MergePortfolioResult;
import com.taxonomy.portfolio.dto.PortfolioGitDtos.PortfolioCommitResult;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * GUI-oriented application facade over the established portfolio Git services.
 *
 * <p>This class intentionally does not duplicate JGit or TaxDSL merge logic.
 * Projection and materialization are delegated to {@link PortfolioGitService};
 * branch merging is delegated to {@link SemanticGitMergeService}.</p>
 */
@Service
public class PortfolioGitApplicationService {

    private static final int PREVIEW_LIMIT = 20;

    private final PortfolioGitService portfolioGitService;
    private final ProjectPortfolioService projectService;
    private final SolutionPortfolioService solutionService;
    private final ProductCatalogService productService;
    private final ProjectConflictService conflictService;
    private final DslGitRepositoryFactory repositoryFactory;
    private final SemanticGitMergeService semanticMergeService;

    public PortfolioGitApplicationService(PortfolioGitService portfolioGitService,
                                          ProjectPortfolioService projectService,
                                          SolutionPortfolioService solutionService,
                                          ProductCatalogService productService,
                                          ProjectConflictService conflictService,
                                          DslGitRepositoryFactory repositoryFactory,
                                          SemanticGitMergeService semanticMergeService) {
        this.portfolioGitService = portfolioGitService;
        this.projectService = projectService;
        this.solutionService = solutionService;
        this.productService = productService;
        this.conflictService = conflictService;
        this.repositoryFactory = repositoryFactory;
        this.semanticMergeService = semanticMergeService;
    }

    public ExportedPortfolioDsl export(WorkspaceContext context) throws IOException {
        String username = username(context);
        String branch = activeBranch(context);
        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        Counts counts = counts(username, context);
        return new ExportedPortfolioDsl(
                context.workspaceId(),
                username,
                branch,
                repository.getHeadCommit(branch),
                portfolioGitService.exportPortfolio(username, context),
                counts.projects(),
                counts.requirements(),
                counts.solutions(),
                counts.products(),
                Instant.now());
    }

    public PortfolioCommitResult commit(String branch,
                                        String message,
                                        WorkspaceContext context) throws IOException {
        String normalizedBranch = requireBranch(branch);
        String username = username(context);
        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        String parent = repository.getHeadCommit(normalizedBranch);
        PortfolioGitService.CommitResult committed = portfolioGitService.commit(
                normalizedBranch, message, username, context);
        Counts counts = counts(username, context);
        return new PortfolioCommitResult(
                normalizedBranch,
                committed.commitId(),
                parent,
                counts.projects(),
                counts.requirements(),
                counts.solutions(),
                counts.products(),
                counts.conflicts(),
                Instant.now());
    }

    /**
     * Compares the reviewed database projection with one immutable branch HEAD.
     * Non-portfolio DSL blocks are preserved on both sides of the comparison.
     */
    public MaterializationPreview previewMaterialize(String branch,
                                                      WorkspaceContext context) throws IOException {
        String normalizedBranch = requireBranch(branch);
        String username = username(context);
        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        String targetHead = requireHead(repository, normalizedBranch);
        String targetDsl = repository.getDslAtHead(normalizedBranch);
        String currentProjection = portfolioGitService.contributeTo(
                targetDsl, username, context);

        List<String> currentLines = lines(currentProjection);
        List<String> targetLines = lines(targetDsl);
        Set<String> currentSet = new LinkedHashSet<>(currentLines);
        Set<String> targetSet = new LinkedHashSet<>(targetLines);
        List<String> added = targetSet.stream()
                .filter(line -> !currentSet.contains(line)).toList();
        List<String> removed = currentSet.stream()
                .filter(line -> !targetSet.contains(line)).toList();
        return new MaterializationPreview(
                normalizedBranch,
                targetHead,
                sha256(currentProjection),
                sha256(targetDsl),
                currentLines.size(),
                targetLines.size(),
                added.size(),
                removed.size(),
                !added.isEmpty() || !removed.isEmpty(),
                !removed.isEmpty(),
                added.stream().limit(PREVIEW_LIMIT).toList(),
                removed.stream().limit(PREVIEW_LIMIT).toList());
    }

    /** Apply only when the branch still points at the reviewed HEAD. */
    public MaterializePortfolioResult materialize(String branch,
                                                   String expectedHead,
                                                   WorkspaceContext context) throws IOException {
        String normalizedBranch = requireBranch(branch);
        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        String actualHead = requireHead(repository, normalizedBranch);
        if (expectedHead != null && !expectedHead.isBlank()
                && !Objects.equals(expectedHead.strip(), actualHead)) {
            throw PortfolioException.conflict(
                    "Branch changed after materialization preview; expected "
                            + expectedHead.strip() + " but found " + actualHead);
        }
        PortfolioGitService.MaterializeResult materialized =
                portfolioGitService.materializeHead(
                        normalizedBranch, username(context), context);
        return new MaterializePortfolioResult(
                normalizedBranch,
                actualHead,
                materialized.projectsCreated(),
                materialized.requirementsCreated(),
                materialized.versionsCreated(),
                0,
                0,
                0,
                0,
                materialized.warnings().size(),
                Instant.now());
    }

    public MergePortfolioResult merge(String sourceBranch,
                                      String targetBranch,
                                      String message,
                                      WorkspaceContext context) throws IOException {
        String source = requireBranch(sourceBranch);
        String target = requireBranch(targetBranch);
        if (source.equals(target)) {
            throw PortfolioException.validation("Source and target branch must differ");
        }
        if (message != null) {
            ProjectPortfolioService.limited(message, 1000, "message");
        }

        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        String sourceHead = requireHead(repository, source);
        String targetHead = requireHead(repository, target);
        SemanticGitMergeService.MergeOutcome outcome = semanticMergeService.mergeBranches(
                repository, source, target, username(context));
        if (!outcome.success()) {
            throw PortfolioException.conflict(
                    "Portfolio merge conflict: " + String.join(", ", outcome.conflicts()));
        }

        portfolioGitService.materializeHead(target, username(context), context);
        Counts counts = counts(username(context), context);
        return new MergePortfolioResult(
                source,
                target,
                sourceHead,
                targetHead,
                outcome.commitId(),
                outcome.semanticFallback() ? "SEMANTIC_FALLBACK" : "GIT",
                0,
                counts.projects(),
                counts.requirements(),
                counts.solutions(),
                counts.products(),
                counts.conflicts(),
                Instant.now());
    }

    private Counts counts(String username, WorkspaceContext context) {
        List<ProjectView> projects = projectService.listProjects(username, context);
        int requirements = projects.stream()
                .mapToInt(project -> projectService
                        .listRequirements(project.id(), username, context).size())
                .sum();
        int conflicts = projects.stream()
                .mapToInt(project -> conflictService
                        .list(project.id(), username, context).size())
                .sum();
        return new Counts(
                projects.size(),
                requirements,
                solutionService.listSolutions(username, context).size(),
                productService.listProducts(username, context).size(),
                conflicts);
    }

    private static List<String> lines(String value) {
        return Arrays.asList((value == null ? "" : value).split("\\R", -1));
    }

    private static String username(WorkspaceContext context) {
        return PortfolioScope.username(context.username(), context);
    }

    private static String activeBranch(WorkspaceContext context) {
        return context.currentBranch() == null || context.currentBranch().isBlank()
                ? "draft" : requireBranch(context.currentBranch());
    }

    private static String requireHead(DslGitRepository repository,
                                      String branch) throws IOException {
        String head = repository.getHeadCommit(branch);
        if (head == null || head.isBlank()) {
            throw PortfolioException.notFound("Branch has no commits: " + branch);
        }
        return head;
    }

    private static String requireBranch(String branch) {
        String normalized = ProjectPortfolioService.requireText(branch, "branch", 200);
        if (normalized.startsWith("-")
                || normalized.endsWith("/")
                || normalized.contains("..")
                || normalized.contains("\\")
                || normalized.contains(" ")
                || normalized.contains("~")
                || normalized.contains("^")
                || normalized.contains(":")) {
            throw PortfolioException.validation("Invalid Git branch name");
        }
        return normalized;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value)
                            .getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to fingerprint portfolio DSL", error);
        }
    }

    private record Counts(int projects,
                          int requirements,
                          int solutions,
                          int products,
                          int conflicts) {
    }
}
