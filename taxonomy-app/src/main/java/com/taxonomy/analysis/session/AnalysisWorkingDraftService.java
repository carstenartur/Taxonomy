package com.taxonomy.analysis.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taxonomy.analysis.session.AnalysisDraftDtos.AnalysisDraftView;
import com.taxonomy.analysis.session.AnalysisDraftDtos.SaveAnalysisDraftRequest;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Persists and restores the ad-hoc analysis working draft in an exact workspace,
 * repository and branch scope.
 */
@Service
public class AnalysisWorkingDraftService {

    private final AnalysisWorkingDraftRepository repository;
    private final WorkspaceManager workspaceManager;
    private final SystemRepositoryService systemRepositoryService;
    private final ObjectMapper objectMapper;
    private final int maximumPayloadCharacters;

    public AnalysisWorkingDraftService(
            AnalysisWorkingDraftRepository repository,
            WorkspaceManager workspaceManager,
            SystemRepositoryService systemRepositoryService,
            ObjectMapper objectMapper,
            @Value("${taxonomy.analysis-draft.max-characters:2000000}")
            int maximumPayloadCharacters) {
        this.repository = repository;
        this.workspaceManager = workspaceManager;
        this.systemRepositoryService = systemRepositoryService;
        this.objectMapper = objectMapper;
        this.maximumPayloadCharacters = Math.max(10_000, maximumPayloadCharacters);
    }

    @Transactional(readOnly = true)
    public Optional<AnalysisDraftView> read(String username, String workspaceId) {
        DraftScope scope = resolveScope(username, workspaceId);
        return repository.findByScopeKeyAndUsername(scope.scopeKey(), scope.username())
                .map(draft -> toView(draft, scope.branch()));
    }

    @Transactional
    public AnalysisDraftView save(String username,
                                  String workspaceId,
                                  SaveAnalysisDraftRequest request) {
        if (request == null || request.payload() == null || request.payload().isNull()
                || !request.payload().isObject()) {
            throw new AnalysisDraftValidationException(
                    "Analysis draft payload must be a JSON object");
        }

        DraftScope scope = resolveScope(username, workspaceId);
        String payloadJson = serialize(request.payload());
        if (payloadJson.length() > maximumPayloadCharacters) {
            throw new AnalysisDraftValidationException(
                    "Analysis draft exceeds " + maximumPayloadCharacters + " characters");
        }

        AnalysisWorkingDraft draft = repository
                .findByScopeKeyAndUsername(scope.scopeKey(), scope.username())
                .orElse(null);
        Instant now = Instant.now();

        if (draft == null) {
            if (request.expectedVersion() != null) {
                throw conflict(request.expectedVersion(), null);
            }
            draft = new AnalysisWorkingDraft(
                    scope.scopeKey(), scope.workspaceId(), scope.username(), payloadJson, now);
        } else {
            requireExpectedVersion(draft, request.expectedVersion());
            draft.replacePayload(payloadJson, now);
        }

        try {
            return toView(repository.saveAndFlush(draft), scope.branch());
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
            throw new AnalysisDraftConflictException(
                    "The analysis draft was changed by another session", exception);
        }
    }

    @Transactional
    public void delete(String username, String workspaceId, Long expectedVersion) {
        DraftScope scope = resolveScope(username, workspaceId);
        AnalysisWorkingDraft draft = repository
                .findByScopeKeyAndUsername(scope.scopeKey(), scope.username())
                .orElse(null);
        if (draft == null) return;
        requireExpectedVersion(draft, expectedVersion);
        try {
            repository.delete(draft);
            repository.flush();
        } catch (OptimisticLockingFailureException exception) {
            throw new AnalysisDraftConflictException(
                    "The analysis draft was changed by another session", exception);
        }
    }

    private DraftScope resolveScope(String username, String workspaceId) {
        if (username == null || username.isBlank()) {
            throw new AccessDeniedException("An authenticated user is required");
        }
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new AnalysisDraftValidationException("workspaceId is required");
        }

        String requestedWorkspace = workspaceId.strip();
        UserWorkspace workspace = workspaceManager.getWorkspaceById(requestedWorkspace);
        if (workspace == null
                || workspace.getUsername() == null
                || !workspace.getUsername().equals(username)
                || workspace.isArchived()
                || workspace.isShared()) {
            throw new AccessDeniedException("Workspace is not available to the current user");
        }

        SystemRepository primary = systemRepositoryService.getPrimaryRepository();
        String repositoryId = hasText(workspace.getSourceRepositoryId())
                ? workspace.getSourceRepositoryId().strip()
                : requireText(primary != null ? primary.getRepositoryId() : null,
                        "primary repository ID");
        String branch = hasText(workspace.getCurrentBranch())
                ? workspace.getCurrentBranch().strip()
                : requireText(primary != null ? primary.getDefaultBranch() : null,
                        "workspace branch");

        WorkspaceContext context = new WorkspaceContext(
                username, requestedWorkspace, branch, repositoryId);
        return new DraftScope(
                PortfolioScope.key(username, context),
                PortfolioScope.username(username, context),
                requestedWorkspace,
                branch);
    }

    private void requireExpectedVersion(AnalysisWorkingDraft draft, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion.longValue() != draft.getRowVersion()) {
            throw conflict(expectedVersion, draft.getRowVersion());
        }
    }

    private AnalysisDraftConflictException conflict(Long expected, Long actual) {
        return new AnalysisDraftConflictException(
                "Analysis draft version conflict: expected "
                        + (expected != null ? expected : "none")
                        + ", current " + (actual != null ? actual : "none"));
    }

    private String serialize(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new AnalysisDraftValidationException(
                    "Analysis draft payload is not valid JSON", exception);
        }
    }

    private AnalysisDraftView toView(AnalysisWorkingDraft draft, String branch) {
        try {
            return new AnalysisDraftView(
                    draft.getWorkspaceId(),
                    branch,
                    objectMapper.readTree(draft.getPayloadJson()),
                    draft.getRowVersion(),
                    draft.getUpdatedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Persisted analysis draft contains invalid JSON", exception);
        }
    }

    private static String requireText(String value, String label) {
        if (!hasText(value)) {
            throw new IllegalStateException(label + " is not configured");
        }
        return value.strip();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record DraftScope(
            String scopeKey,
            String username,
            String workspaceId,
            String branch) {
    }
}
