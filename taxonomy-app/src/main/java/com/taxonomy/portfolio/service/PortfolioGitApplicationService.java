package com.taxonomy.portfolio.service;

import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dsl.storage.SemanticMergeResult;
import com.taxonomy.portfolio.dto.PortfolioGitDtos.ExportedPortfolioDsl;
import com.taxonomy.portfolio.dto.PortfolioGitDtos.MaterializationPreview;
import com.taxonomy.portfolio.dto.PortfolioGitDtos.MaterializePortfolioResult;
import com.taxonomy.portfolio.dto.PortfolioGitDtos.MergePortfolioResult;
import com.taxonomy.versioning.service.SemanticDslOperationsFacade;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Git-backed collaboration facade for the project portfolio. */
@Service
public class PortfolioGitApplicationService {

    private static final int PREVIEW_LIMIT = 20;

    private final PortfolioGitProjectionService projectionService;
    private final PortfolioDslMaterializationService materializationService;
    private final DslGitRepositoryFactory repositoryFactory;
    private final SemanticDslOperationsFacade dslOperations;

    public PortfolioGitApplicationService(
            PortfolioGitProjectionService projectionService,
            PortfolioDslMaterializationService materializationService,
            DslGitRepositoryFactory repositoryFactory,
            SemanticDslOperationsFacade dslOperations) {
        this.projectionService = projectionService;
        this.materializationService = materializationService;
        this.repositoryFactory = repositoryFactory;
        this.dslOperations = dslOperations;
    }

    public ExportedPortfolioDsl export(WorkspaceContext context) {
        return projectionService.exportDsl(context);
    }

    public com.taxonomy.portfolio.dto.PortfolioGitDtos.PortfolioCommitResult commit(
            String branch,
            String message,
            WorkspaceContext context) {
        return projectionService.commit(branch, message, context);
    }

    public MaterializationPreview previewMaterialize(String branch,
                                                      WorkspaceContext context) {
        String normalizedBranch = requireBranch(branch);
        ExportedPortfolioDsl current = projectionService.exportDsl(context);
        String targetDsl = dslOperations.getHead(normalizedBranch, context);
        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        String targetHead = repository.getHeadCommit(normalizedBranch);
        List<String> currentLines = Arrays.asList(current.dsl().split("\\R", -1));
        List<String> targetLines = Arrays.asList(targetDsl.split("\\R", -1));
        Set<String> currentSet = new LinkedHashSet<>(currentLines);
        Set<String> targetSet = new LinkedHashSet<>(targetLines);
        List<String> added = targetSet.stream().filter(line -> !currentSet.contains(line)).toList();
        List<String> removed = currentSet.stream().filter(line -> !targetSet.contains(line)).toList();
        return new MaterializationPreview(
                normalizedBranch,
                targetHead,
                sha256(current.dsl()),
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

    public MaterializePortfolioResult materialize(String branch,
                                                  WorkspaceContext context) {
        String normalizedBranch = requireBranch(branch);
        String dsl = dslOperations.getHead(normalizedBranch, context);
        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        String commitId = repository.getHeadCommit(normalizedBranch);
        return materializationService.materialize(
                normalizedBranch,
                commitId,
                dsl,
                context);
    }

    /**
     * Merges {@code sourceBranch} into {@code targetBranch}. Ordinary Git merge
     * runs first. If it conflicts, TaxDSL is parsed, semantically merged and
     * committed as a true two-parent merge commit.
     */
    public MergePortfolioResult merge(String sourceBranch,
                                      String targetBranch,
                                      String message,
                                      WorkspaceContext context) {
        String source = requireBranch(sourceBranch);
        String target = requireBranch(targetBranch);
        if (source.equals(target)) {
            throw PortfolioException.validation("Source and target branch must differ");
        }
        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        String sourceHead = requireHead(repository, source);
        String targetHead = requireHead(repository, target);
        PersonIdent actor = actor(context);
        String commitMessage = ProjectPortfolioService.limited(
                message != null ? message : "Merge portfolio " + source + " into " + target,
                1000,
                "message");

        try {
            MergeResult mergeResult = repository.mergeBranches(
                    source, target, actor, commitMessage);
            if (mergeResult.getMergeStatus().isSuccessful()) {
                String mergeCommit = requireHead(repository, target);
                CanonicalArchitectureModel model = projectionService.parseModel(
                        repository.getDslAtCommit(mergeCommit));
                return result(
                        source,
                        target,
                        sourceHead,
                        targetHead,
                        mergeCommit,
                        mergeResult.getMergeStatus().name(),
                        0,
                        model);
            }
            repository.abortMerge();
        } catch (Exception ordinaryFailure) {
            repository.abortMerge();
        }

        String baseCommit = mergeBase(repository.getGitRepository(), targetHead, sourceHead);
        String baseDsl = baseCommit != null
                ? repository.getDslAtCommit(baseCommit)
                : minimalEmptyDsl();
        String targetDsl = repository.getDslAtCommit(targetHead);
        String sourceDsl = repository.getDslAtCommit(sourceHead);
        SemanticMergeResult semantic = repository.semanticMerge(
                baseDsl,
                targetDsl,
                sourceDsl);
        if (semantic.hasConflicts()) {
            throw PortfolioException.conflict(
                    "Portfolio merge has " + semantic.conflicts().size()
                            + " semantic conflict(s): " + summarizeConflicts(semantic));
        }
        String mergeCommit = createTwoParentMergeCommit(
                repository,
                target,
                targetHead,
                sourceHead,
                semantic.mergedDsl(),
                actor,
                commitMessage);
        CanonicalArchitectureModel model = projectionService.parseModel(semantic.mergedDsl());
        return result(
                source,
                target,
                sourceHead,
                targetHead,
                mergeCommit,
                "SEMANTIC_FALLBACK",
                semantic.mergedAncestors(),
                model);
    }

    private MergePortfolioResult result(String source,
                                        String target,
                                        String sourceHead,
                                        String targetHead,
                                        String mergeCommit,
                                        String strategy,
                                        int ancestorCount,
                                        CanonicalArchitectureModel model) {
        var counts = projectionService.counts(model);
        return new MergePortfolioResult(
                source,
                target,
                sourceHead,
                targetHead,
                mergeCommit,
                strategy,
                ancestorCount,
                counts.projectCount(),
                counts.requirementCount(),
                counts.solutionCount(),
                counts.productCount(),
                counts.conflictCount(),
                Instant.now());
    }

    private String createTwoParentMergeCommit(
            DslGitRepository repository,
            String targetBranch,
            String targetHead,
            String sourceHead,
            String mergedDsl,
            PersonIdent actor,
            String message) {
        try (Git git = new Git(repository.getGitRepository())) {
            git.checkout().setName(targetBranch).call();
            ObjectId blob = repository.getGitRepository().newObjectInserter().insert(
                    org.eclipse.jgit.lib.Constants.OBJ_BLOB,
                    mergedDsl.getBytes(StandardCharsets.UTF_8));
            try (var inserter = repository.getGitRepository().newObjectInserter()) {
                var formatter = new org.eclipse.jgit.lib.TreeFormatter();
                formatter.append(
                        "architecture.taxdsl",
                        org.eclipse.jgit.lib.FileMode.REGULAR_FILE,
                        blob);
                ObjectId treeId = inserter.insert(formatter);
                var builder = new org.eclipse.jgit.lib.CommitBuilder();
                builder.setTreeId(treeId);
                builder.setParentIds(
                        ObjectId.fromString(targetHead),
                        ObjectId.fromString(sourceHead));
                builder.setAuthor(actor);
                builder.setCommitter(actor);
                builder.setMessage(message);
                ObjectId commitId = inserter.insert(builder);
                inserter.flush();
                RefUpdate update = repository.getGitRepository()
                        .updateRef("refs/heads/" + targetBranch);
                update.setExpectedOldObjectId(ObjectId.fromString(targetHead));
                update.setNewObjectId(commitId);
                update.setRefLogIdent(actor);
                update.setRefLogMessage("merge: " + message, true);
                RefUpdate.Result updated = update.update();
                if (updated != RefUpdate.Result.FAST_FORWARD
                        && updated != RefUpdate.Result.NEW) {
                    throw PortfolioException.conflict(
                            "Target branch changed while creating semantic merge commit: "
                                    + updated);
                }
                return commitId.name();
            }
        } catch (PortfolioException exception) {
            throw exception;
        } catch (Exception exception) {
            throw PortfolioException.conflict(
                    "Unable to create semantic portfolio merge commit: "
                            + exception.getMessage());
        }
    }

    private static String mergeBase(Repository repository,
                                    String leftCommit,
                                    String rightCommit) {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit left = walk.parseCommit(ObjectId.fromString(leftCommit));
            RevCommit right = walk.parseCommit(ObjectId.fromString(rightCommit));
            walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE);
            walk.markStart(left);
            walk.markStart(right);
            RevCommit base = walk.next();
            return base != null ? base.getId().name() : null;
        } catch (IOException exception) {
            throw PortfolioException.conflict(
                    "Unable to determine portfolio merge base: " + exception.getMessage());
        }
    }

    private static String requireHead(DslGitRepository repository, String branch) {
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

    private static PersonIdent actor(WorkspaceContext context) {
        String username = PortfolioScope.username(context.username(), context);
        return new PersonIdent(username, username + "@taxonomy.local");
    }

    private static String summarizeConflicts(SemanticMergeResult result) {
        List<String> summaries = new ArrayList<>();
        result.conflicts().stream().limit(8).forEach(conflict -> summaries.add(
                conflict.path() + " (" + conflict.type() + ")"));
        return String.join(", ", summaries);
    }

    private static String minimalEmptyDsl() {
        return """
                meta {
                  language: "taxdsl";
                  version: "2.0";
                  namespace: "portfolio-empty-base";
                }
                """;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to fingerprint portfolio DSL", error);
        }
    }
}
