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
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dto.ElementHistoryAggregation;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryStateGuard;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * High-level facade that aggregates the DSL operation services.
 *
 * <p>Provides coarse-grained operations for Git versioning, DSL export,
 * materialization, commit indexing, and conflict detection, so that
 * the {@code DslApiController} does not need direct access to
 * repositories or low-level services.</p>
 */
@Service
public class DslOperationsFacade {

    private static final Logger log = LoggerFactory.getLogger(DslOperationsFacade.class);

    private final TaxDslExportService exportService;
    private final DslMaterializeService materializeService;
    private final ArchitectureDslDocumentRepository documentRepository;
    private final DslGitRepositoryFactory repositoryFactory;
    private final CommitIndexService commitIndexService;
    private final ConflictDetectionService conflictDetectionService;
    private final RepositoryStateGuard stateGuard;
    private final RepositoryStateService repositoryStateService;
    private final WorkspaceResolver workspaceResolver;

    public DslOperationsFacade(TaxDslExportService exportService,
                               DslMaterializeService materializeService,
                               ArchitectureDslDocumentRepository documentRepository,
                               DslGitRepositoryFactory repositoryFactory,
                               CommitIndexService commitIndexService,
                               ConflictDetectionService conflictDetectionService,
                               RepositoryStateGuard stateGuard,
                               RepositoryStateService repositoryStateService,
                               WorkspaceResolver workspaceResolver) {
        this.exportService = exportService;
        this.materializeService = materializeService;
        this.documentRepository = documentRepository;
        this.repositoryFactory = repositoryFactory;
        this.commitIndexService = commitIndexService;
        this.conflictDetectionService = conflictDetectionService;
        this.stateGuard = stateGuard;
        this.repositoryStateService = repositoryStateService;
        this.workspaceResolver = workspaceResolver;
    }

    private DslGitRepository resolveRepository() {
        return repositoryFactory.resolveRepository(resolveRepositoryContext());
    }

    private DslGitRepository resolveRepository(WorkspaceContext workspaceContext) {
        return repositoryFactory.resolveRepository(workspaceContext);
    }

    /**
     * Resolves the request-bound workspace after eager provisioning.
     *
     * <p>This deliberately fails closed. Falling back to the shared repository
     * after a resolver, persistence or provisioning failure could make a read,
     * commit, merge or index operation cross the authenticated user's workspace
     * boundary.</p>
     */
    private WorkspaceContext resolveContext() {
        String username = workspaceResolver.resolveCurrentUsername();
        repositoryStateService.ensureWorkspaceState(username);
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        if (context == null) {
            throw new IllegalStateException("Workspace context resolver returned null");
        }
        return context;
    }

    /** Resolve the mandatory logical repository identity for this request. */
    private RepositoryContext resolveRepositoryContext() {
        String username = workspaceResolver.resolveCurrentUsername();
        repositoryStateService.ensureWorkspaceState(username);
        RepositoryContext context = workspaceResolver.resolveCurrentRepositoryContext();
        if (context == null) {
            throw new IllegalStateException("Repository context resolver returned null");
        }
        return context;
    }

    // ── Export ───────────────────────────────────────────────────────

    public String exportAll(String namespace) {
        return exportService.exportAll(namespace);
    }

    public CanonicalArchitectureModel buildCanonicalModel() {
        return exportService.buildCanonicalModel();
    }

    // ── Materialization ─────────────────────────────────────────────

    public DslMaterializeService.MaterializeResult materialize(
            String dslText, String path, String branch, String commitId) {
        return materializeService.materialize(dslText, path, branch, commitId);
    }

    public DslMaterializeService.MaterializeResult materializeIncremental(
            Long beforeDocId, Long afterDocId) {
        return materializeService.materializeIncremental(beforeDocId, afterDocId);
    }

    public Optional<ArchitectureDslDocument> findDocumentById(Long id) {
        return documentRepository.findById(id);
    }

    // ── Git operations ──────────────────────────────────────────────

    public String commitDsl(String branch, String dslText, String author, String message)
            throws IOException {
        return resolveRepository().commitDsl(branch, dslText, author, message);
    }

    public boolean isDatabaseBacked() {
        return resolveRepository().isDatabaseBacked();
    }

    public List<DslCommit> getDslHistory(String branch) throws IOException {
        return resolveRepository().getDslHistory(branch);
    }

    public List<DslCommit> getDslHistory(
            String branch, WorkspaceContext workspaceContext) throws IOException {
        return resolveRepository(workspaceContext).getDslHistory(branch);
    }

    public Optional<Long> findDocumentIdByCommitId(String commitId) {
        return documentRepository.findByCommitId(commitId)
                .map(ArchitectureDslDocument::getId);
    }

    public ModelDiff diffBetween(String beforeId, String afterId) throws Exception {
        if (looksLikeGitSha(beforeId) && looksLikeGitSha(afterId)) {
            return resolveRepository().diffBetween(beforeId, afterId);
        }
        return materializeService.diffDocuments(
                Long.valueOf(beforeId), Long.valueOf(afterId));
    }

    public String textDiff(String beforeId, String afterId) throws Exception {
        return resolveRepository().textDiff(beforeId, afterId);
    }

    public List<DslBranch> listBranches() throws IOException {
        return resolveRepository().listBranches();
    }

    public String createBranch(String name, String fromBranch) throws IOException {
        return resolveRepository().createBranch(name, fromBranch);
    }

    public String cherryPick(String commitId, String targetBranch) throws IOException {
        return resolveRepository().cherryPick(commitId, targetBranch);
    }

    public String merge(String fromBranch, String intoBranch) throws IOException {
        return resolveRepository().merge(fromBranch, intoBranch);
    }

    public String revert(String commitId, String branch) throws IOException {
        return resolveRepository().revert(commitId, branch);
    }

    public String undoLast(String branch) throws IOException {
        return resolveRepository().undoLast(branch);
    }

    public String restore(String commitId, String branch) throws Exception {
        return resolveRepository().restore(commitId, branch);
    }

    public boolean deleteBranch(String name) throws IOException {
        return resolveRepository().deleteBranch(name);
    }

    public String getDslAtHead(String branch) throws IOException {
        return resolveRepository().getDslAtHead(branch);
    }

    public String getDslAtHead(
            String branch, WorkspaceContext workspaceContext) throws IOException {
        return resolveRepository(workspaceContext).getDslAtHead(branch);
    }

    public String getDslAtCommit(String commitId) throws Exception {
        return resolveRepository().getDslAtCommit(commitId);
    }

    public String getHeadCommit(String branch) throws IOException {
        return resolveRepository().getHeadCommit(branch);
    }

    // ── Conflict detection ──────────────────────────────────────────

    public ConflictDetectionService.MergePreview previewMerge(String from, String into) {
        return conflictDetectionService.previewMerge(from, into, resolveContext());
    }

    public ConflictDetectionService.CherryPickPreview previewCherryPick(
            String commitId, String targetBranch) {
        return conflictDetectionService.previewCherryPick(
                commitId, targetBranch, resolveContext());
    }

    public ConflictDetectionService.ConflictDetails getMergeConflictDetails(
            String from, String into) {
        return conflictDetectionService.getMergeConflictDetails(
                from, into, resolveContext());
    }

    public ConflictDetectionService.ConflictDetails getCherryPickConflictDetails(
            String commitId, String targetBranch) {
        return conflictDetectionService.getCherryPickConflictDetails(
                commitId, targetBranch, resolveContext());
    }

    // ── View context & state guard ──────────────────────────────────

    public ViewContext getViewContext(String branch) {
        return repositoryStateService.getViewContext(
                workspaceResolver.resolveCurrentUsername(), branch, resolveContext());
    }

    public ViewContext getViewContext(
            String username,
            String branch,
            WorkspaceContext workspaceContext) {
        return repositoryStateService.getViewContext(
                username, branch, workspaceContext);
    }

    public RepositoryStateGuard.OperationCheck checkWriteOperation(
            String branch, String operationType) {
        return stateGuard.checkWriteOperation(
                workspaceResolver.resolveCurrentUsername(), branch, operationType);
    }

    public String resolveCurrentUsername() {
        return workspaceResolver.resolveCurrentUsername();
    }

    public String resolveWorkspaceBranch(String username) {
        return repositoryStateService.resolveWorkspaceBranch(username);
    }

    // ── Document listing ────────────────────────────────────────────

    public List<ArchitectureDslDocument> listDocuments() {
        return documentRepository.findAll();
    }

    // ── History search ──────────────────────────────────────────────

    public int indexBranch(String branch) {
        return commitIndexService.indexBranch(branch, resolveRepositoryContext());
    }

    public int rebuildHistoryBranch(String branch) {
        return commitIndexService.rebuildBranch(branch, resolveRepositoryContext());
    }

    public int purgeHistoryIndex() {
        return commitIndexService.purge(resolveRepositoryContext());
    }

    public List<ArchitectureCommitIndex> searchHistory(
            String query, int maxResults) {
        return commitIndexService.search(
                query, maxResults, resolveRepositoryContext());
    }

    public List<ArchitectureCommitIndex> findByElement(String elementId) {
        return commitIndexService.findByElement(
                elementId, resolveRepositoryContext());
    }

    public List<ArchitectureCommitIndex> findByRelation(String key) {
        return commitIndexService.findByRelation(
                key, resolveRepositoryContext());
    }

    public ElementHistoryAggregation aggregateElementHistory(String elementId) {
        return commitIndexService.aggregateElementHistory(
                elementId, resolveRepositoryContext());
    }

    private static boolean looksLikeGitSha(String value) {
        return value != null
                && value.length() == 40
                && value.matches("[0-9a-f]+");
    }
}
