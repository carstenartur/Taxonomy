package com.taxonomy.versioning.service;

import com.taxonomy.architecture.model.ArchitectureCommitIndex;
import com.taxonomy.architecture.model.ArchitectureDslDocument;
import com.taxonomy.architecture.repository.ArchitectureDslDocumentRepository;
import com.taxonomy.architecture.service.CommitIndexService;
import com.taxonomy.dsl.diff.ModelDiff;
import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.storage.DslBranch;
import com.taxonomy.dsl.storage.DslCommit;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dto.ElementHistoryAggregation;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.dto.VersionedSearchResult;
import com.taxonomy.workspace.service.RepositoryStateGuard;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

/**
 * High-level facade that aggregates the DSL operation services.
 *
 * <p>Provides coarse-grained operations for Git versioning, DSL export,
 * materialization, commit indexing, and conflict detection, so that
 * the {@code DslApiController} does not need direct access to
 * repositories or low-level services.
 */
@Service
public class DslOperationsFacade {

    private static final Logger log = LoggerFactory.getLogger(DslOperationsFacade.class);

    private final TaxDslExportService exportService;
    private final DslMaterializeService materializeService;
    private final ArchitectureDslDocumentRepository documentRepository;
    private final DslGitRepository gitRepository;
    private final CommitIndexService commitIndexService;
    private final ConflictDetectionService conflictDetectionService;
    private final RepositoryStateGuard stateGuard;
    private final RepositoryStateService repositoryStateService;
    private final WorkspaceResolver workspaceResolver;

    public DslOperationsFacade(TaxDslExportService exportService,
                               DslMaterializeService materializeService,
                               ArchitectureDslDocumentRepository documentRepository,
                               DslGitRepository gitRepository,
                               CommitIndexService commitIndexService,
                               ConflictDetectionService conflictDetectionService,
                               RepositoryStateGuard stateGuard,
                               RepositoryStateService repositoryStateService,
                               WorkspaceResolver workspaceResolver) {
        this.exportService = exportService;
        this.materializeService = materializeService;
        this.documentRepository = documentRepository;
        this.gitRepository = gitRepository;
        this.commitIndexService = commitIndexService;
        this.conflictDetectionService = conflictDetectionService;
        this.stateGuard = stateGuard;
        this.repositoryStateService = repositoryStateService;
        this.workspaceResolver = workspaceResolver;
    }

    // ── Export ───────────────────────────────────────────────────────

    /**
     * Export all architecture elements as DSL text.
     */
    public String exportAll(String namespace) {
        return exportService.exportAll(namespace);
    }

    /**
     * Build the canonical architecture model.
     */
    public CanonicalArchitectureModel buildCanonicalModel() {
        return exportService.buildCanonicalModel();
    }

    // ── Materialization ─────────────────────────────────────────────

    /**
     * Materialize DSL text into the database.
     */
    public DslMaterializeService.MaterializeResult materialize(String dslText, String path,
                                                                String branch, String commitId) {
        return materializeService.materialize(dslText, path, branch, commitId);
    }

    /**
     * Incrementally materialize the delta between two DSL versions.
     */
    public DslMaterializeService.MaterializeResult materializeIncremental(Long beforeDocId, Long afterDocId) {
        return materializeService.materializeIncremental(beforeDocId, afterDocId);
    }

    /**
     * Look up a document by ID to resolve the branch for incremental materialization.
     */
    public Optional<ArchitectureDslDocument> findDocumentById(Long id) {
        return documentRepository.findById(id);
    }

    // ── Git operations ──────────────────────────────────────────────

    /**
     * Commit DSL text to a branch.
     */
    public String commitDsl(String branch, String dslText, String author, String message) throws IOException {
        return gitRepository.commitDsl(branch, dslText, author, message);
    }

    /**
     * Whether the Git repository is database-backed.
     */
    public boolean isDatabaseBacked() {
        return gitRepository.isDatabaseBacked();
    }

    /**
     * Get DSL commit history for a branch.
     */
    public List<DslCommit> getDslHistory(String branch) throws IOException {
        return gitRepository.getDslHistory(branch);
    }

    /**
     * Find the document ID for a given commit ID (from materialized documents).
     */
    public Optional<Long> findDocumentIdByCommitId(String commitId) {
        return documentRepository.findByCommitId(commitId)
                .map(ArchitectureDslDocument::getId);
    }

    /**
     * Compute a diff between two DSL versions (by Git SHA or document ID).
     */
    public ModelDiff diffBetween(String beforeId, String afterId) throws Exception {
        if (looksLikeGitSha(beforeId) && looksLikeGitSha(afterId)) {
            return gitRepository.diffBetween(beforeId, afterId);
        } else {
            return materializeService.diffDocuments(Long.valueOf(beforeId), Long.valueOf(afterId));
        }
    }

    /**
     * Compute a JGit text diff between two commits.
     */
    public String textDiff(String beforeId, String afterId) throws Exception {
        return gitRepository.textDiff(beforeId, afterId);
    }

    /**
     * List all branches in the Git repository.
     */
    public List<DslBranch> listBranches() throws IOException {
        return gitRepository.listBranches();
    }

    /**
     * Create a new branch by forking from an existing branch.
     */
    public String createBranch(String name, String fromBranch) throws IOException {
        return gitRepository.createBranch(name, fromBranch);
    }

    /**
     * Cherry-pick a commit onto a target branch.
     */
    public String cherryPick(String commitId, String targetBranch) throws IOException {
        return gitRepository.cherryPick(commitId, targetBranch);
    }

    /**
     * Merge one branch into another.
     */
    public String merge(String fromBranch, String intoBranch) throws IOException {
        return gitRepository.merge(fromBranch, intoBranch);
    }

    /**
     * Revert a specific commit on a branch.
     */
    public String revert(String commitId, String branch) throws IOException {
        return gitRepository.revert(commitId, branch);
    }

    /**
     * Undo the last commit on a branch.
     */
    public String undoLast(String branch) throws IOException {
        return gitRepository.undoLast(branch);
    }

    /**
     * Restore DSL content from a specific commit.
     */
    public String restore(String commitId, String branch) throws Exception {
        return gitRepository.restore(commitId, branch);
    }

    /**
     * Delete a branch.
     */
    public boolean deleteBranch(String name) throws IOException {
        return gitRepository.deleteBranch(name);
    }

    /**
     * Read DSL text from the HEAD of a branch.
     */
    public String getDslAtHead(String branch) throws IOException {
        return gitRepository.getDslAtHead(branch);
    }

    /**
     * Read DSL text from a specific commit.
     */
    public String getDslAtCommit(String commitId) throws Exception {
        return gitRepository.getDslAtCommit(commitId);
    }

    /**
     * Get the HEAD commit SHA for a branch.
     */
    public String getHeadCommit(String branch) throws IOException {
        return gitRepository.getHeadCommit(branch);
    }

    // ── Conflict detection ──────────────────────────────────────────

    /**
     * Preview a merge (dry run).
     */
    public ConflictDetectionService.MergePreview previewMerge(String from, String into) {
        return conflictDetectionService.previewMerge(from, into);
    }

    /**
     * Preview a cherry-pick (dry run).
     */
    public ConflictDetectionService.CherryPickPreview previewCherryPick(String commitId, String targetBranch) {
        return conflictDetectionService.previewCherryPick(commitId, targetBranch);
    }

    /**
     * Get merge conflict details.
     */
    public ConflictDetectionService.ConflictDetails getMergeConflictDetails(String from, String into) {
        return conflictDetectionService.getMergeConflictDetails(from, into);
    }

    /**
     * Get cherry-pick conflict details.
     */
    public ConflictDetectionService.ConflictDetails getCherryPickConflictDetails(String commitId, String targetBranch) {
        return conflictDetectionService.getCherryPickConflictDetails(commitId, targetBranch);
    }

    // ── View context & state guard ──────────────────────────────────

    /**
     * Get the view context for the current user on a branch.
     */
    public ViewContext getViewContext(String branch) {
        return repositoryStateService.getViewContext(
                workspaceResolver.resolveCurrentUsername(), branch);
    }

    /**
     * Check whether a write operation is safe.
     */
    public RepositoryStateGuard.OperationCheck checkWriteOperation(String branch, String operationType) {
        return stateGuard.checkWriteOperation(
                workspaceResolver.resolveCurrentUsername(), branch, operationType);
    }

    /**
     * Resolve the current username.
     */
    public String resolveCurrentUsername() {
        return workspaceResolver.resolveCurrentUsername();
    }

    // ── Document listing ────────────────────────────────────────────

    /**
     * List all stored DSL documents.
     */
    public List<ArchitectureDslDocument> listDocuments() {
        return documentRepository.findAll();
    }

    // ── History search ──────────────────────────────────────────────

    /**
     * Index commits on a branch for history search.
     */
    public int indexBranch(String branch) {
        return commitIndexService.indexBranch(branch);
    }

    /**
     * Search architecture commit history.
     */
    public List<ArchitectureCommitIndex> searchHistory(String query, int maxResults) {
        return commitIndexService.search(query, maxResults);
    }

    /**
     * Find commits that affected a specific element.
     */
    public List<ArchitectureCommitIndex> findByElement(String elementId) {
        return commitIndexService.findByElement(elementId);
    }

    /**
     * Find commits that affected a specific relation.
     */
    public List<ArchitectureCommitIndex> findByRelation(String key) {
        return commitIndexService.findByRelation(key);
    }

    /**
     * Get aggregated history for an element.
     */
    public ElementHistoryAggregation aggregateElementHistory(String elementId) {
        return commitIndexService.aggregateElementHistory(elementId);
    }

    // ── Internal helpers ────────────────────────────────────────────

    private static boolean looksLikeGitSha(String s) {
        return s != null && s.length() == 40 && s.matches("[0-9a-f]+");
    }
}
