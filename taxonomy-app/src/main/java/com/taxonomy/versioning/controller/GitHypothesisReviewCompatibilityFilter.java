package com.taxonomy.versioning.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter.BranchHeadConflictException;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.controller.GitHttpPrecondition;
import com.taxonomy.relations.controller.RelationApiController;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.Readiness;
import com.taxonomy.versioning.service.GitAuthoritativeHypothesisReviewService.HypothesisReviewPendingException;
import com.taxonomy.versioning.service.GitAuthoritativeHypothesisReviewService.ReviewAction;
import com.taxonomy.versioning.service.GitAuthoritativeHypothesisReviewService.ReviewResult;
import com.taxonomy.versioning.service.GitAuthoritativeHypothesisService;
import com.taxonomy.versioning.service.HypothesisReviewStateStore.HypothesisReviewConflictException;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Preserves the established {@code /api/dsl/hypotheses/...} browser contract
 * while moving it in front of the legacy MVC methods to the Git-authoritative
 * command path. The filter belongs to the versioning boundary because it adapts
 * an existing DSL/versioning route; relation commands remain a dependency of
 * versioning rather than creating a reverse package cycle.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class GitHypothesisReviewCompatibilityFilter extends OncePerRequestFilter {

    private static final Pattern REVIEW_PATH = Pattern.compile(
            "^/api/dsl/hypotheses/(\\d+)/(accept|reject|revert)$");
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final GitAuthoritativeHypothesisService hypothesisService;
    private final RelationBranchProjectionReadinessService readinessService;
    private final WorkspaceResolver workspaceResolver;
    private final SystemRepositoryService repositoryService;
    private final RepositoryMembershipService membershipService;
    private final ObjectMapper objectMapper;

    public GitHypothesisReviewCompatibilityFilter(
            GitAuthoritativeHypothesisService hypothesisService,
            RelationBranchProjectionReadinessService readinessService,
            WorkspaceResolver workspaceResolver,
            SystemRepositoryService repositoryService,
            RepositoryMembershipService membershipService) {
        this.hypothesisService = Objects.requireNonNull(
                hypothesisService, "hypothesisService");
        this.readinessService = Objects.requireNonNull(
                readinessService, "readinessService");
        this.workspaceResolver = Objects.requireNonNull(
                workspaceResolver, "workspaceResolver");
        this.repositoryService = Objects.requireNonNull(
                repositoryService, "repositoryService");
        this.membershipService = Objects.requireNonNull(
                membershipService, "membershipService");
        this.objectMapper = new ObjectMapper();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !REVIEW_PATH.matcher(applicationPath(request)).matches();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Matcher matcher = REVIEW_PATH.matcher(applicationPath(request));
        if (!matcher.matches()) {
            filterChain.doFilter(request, response);
            return;
        }

        long hypothesisId = Long.parseLong(matcher.group(1));
        ReviewAction action = ReviewAction.valueOf(
                matcher.group(2).toUpperCase(Locale.ROOT));
        try {
            RepositoryContext selected = workspaceResolver
                    .resolveCurrentRepositoryContext();
            RepositoryContext context = writableContext(selected);
            if (context == null) {
                write(response, HttpServletResponse.SC_FORBIDDEN, Map.of(
                        "status", "FORBIDDEN"));
                return;
            }

            // Fail closed on tenant visibility and terminal lifecycle state
            // before exposing whether the selected branch has a Git head.
            hypothesisService.requireReviewable(
                    hypothesisId, context, action);

            Readiness readiness = readinessService.inspect(context);
            String expectedHead = expectedHead(
                    request.getHeader(HttpHeaders.IF_MATCH),
                    readiness.currentHeadCommit());
            if (expectedHead == null) {
                response.setHeader(
                        RelationApiController.PROJECTION_STATE_HEADER,
                        RelationBranchProjectionReadinessService
                                .ReadinessState.BRANCH_MISSING.name());
                write(response, HttpServletResponse.SC_NOT_FOUND, Map.of(
                        "status", "BRANCH_MISSING"));
                return;
            }

            String causationId = idempotencyKey(
                    request.getHeader(IDEMPOTENCY_KEY),
                    hypothesisId,
                    action,
                    expectedHead);
            ReviewResult result = hypothesisService.review(
                    hypothesisId,
                    context,
                    expectedHead,
                    new CommandMetadata(
                            causationId,
                            "Git-first hypothesis review through the productive DSL API"),
                    action);
            CommandResult authority = result.mutation().authority();
            response.setHeader(
                    HttpHeaders.ETAG,
                    GitHttpPrecondition.etag(
                            authority.authoritativeCommitId()));
            write(response, HttpServletResponse.SC_OK, successPayload(result));
        } catch (BranchHeadConflictException error) {
            if (error.getActualHeadCommit() != null) {
                response.setHeader(
                        HttpHeaders.ETAG,
                        GitHttpPrecondition.etag(error.getActualHeadCommit()));
            }
            write(response, HttpServletResponse.SC_PRECONDITION_FAILED,
                    conflictPayload(hypothesisId, action, error));
        } catch (HypothesisReviewPendingException error) {
            response.setHeader(
                    HttpHeaders.ETAG,
                    GitHttpPrecondition.etag(
                            error.getAuthority().authoritativeCommitId()));
            write(response, HttpServletResponse.SC_ACCEPTED,
                    pendingPayload(error));
        } catch (HypothesisReviewConflictException error) {
            write(response, HttpServletResponse.SC_CONFLICT, Map.of(
                    "hypothesisId", error.getHypothesisId(),
                    "expectedStatus", error.getExpectedStatus().name(),
                    "actualStatus", error.getActualStatus().name(),
                    "projectionStatus", "BOOKKEEPING_CONFLICT"));
        } catch (IllegalArgumentException error) {
            int status = error.getMessage() != null
                    && error.getMessage().startsWith("Hypothesis not found:")
                    ? HttpServletResponse.SC_NOT_FOUND
                    : HttpServletResponse.SC_BAD_REQUEST;
            write(response, status, errorPayload(error));
        } catch (IllegalStateException error) {
            write(response, HttpServletResponse.SC_CONFLICT,
                    errorPayload(error));
        } catch (IOException error) {
            write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    Map.of("status", "GIT_UNAVAILABLE"));
        }
    }

    private RepositoryContext writableContext(RepositoryContext context) {
        if (context.scope() == RepositoryScope.WORKSPACE
                || context.scope() == RepositoryScope.FORK) {
            return context;
        }
        SystemRepository repository = repositoryService.getRepository(
                context.repositoryId());
        if (!isApplicationAdmin()
                && !membershipService.canMaintain(
                        repository, context.username())) {
            return null;
        }
        return new RepositoryContext(
                context.repositoryId(),
                null,
                context.branch(),
                context.username(),
                RepositoryScope.CENTRAL_WRITE);
    }

    private static String expectedHead(String ifMatch, String currentHead) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return currentHead;
        }
        return GitHttpPrecondition.expectedHead(ifMatch, null);
    }

    private static String idempotencyKey(
            String supplied,
            long hypothesisId,
            ReviewAction action,
            String expectedHead) {
        if (supplied != null && !supplied.isBlank()) {
            String normalized = supplied.strip();
            if (normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
                throw new IllegalArgumentException(
                        "Idempotency-Key must be one line");
            }
            return normalized;
        }
        return "legacy-hypothesis-"
                + action.name().toLowerCase(Locale.ROOT)
                + "-" + hypothesisId + "-" + expectedHead;
    }

    private static Map<String, Object> successPayload(ReviewResult result) {
        CommandResult authority = result.mutation().authority();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", result.hypothesisId());
        payload.put("action", result.action().name());
        payload.put("status", result.hypothesis().getStatus().name());
        payload.put("sourceNodeId", result.hypothesis().getSourceNodeId());
        payload.put("targetNodeId", result.hypothesis().getTargetNodeId());
        payload.put("relationType", result.hypothesis().getRelationType().name());
        payload.put("authoritativeCommitId",
                authority.authoritativeCommitId());
        payload.put("previousHeadCommit", authority.previousHeadCommit());
        payload.put("changeKind", authority.changeKind().name());
        payload.put("commitCreated", authority.commitCreated());
        payload.put("projectionStatus", "PROJECTED");
        payload.put("projectionOutcome",
                result.mutation().projection().outcome().name());
        payload.put("relationPresent",
                result.mutation().projection().relationPresent());
        return payload;
    }

    private static Map<String, Object> pendingPayload(
            HypothesisReviewPendingException error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", error.getHypothesisId());
        payload.put("status", error.getIntendedStatus().name());
        payload.put("authoritativeCommitId",
                error.getAuthority().authoritativeCommitId());
        payload.put("changeKind", error.getAuthority().changeKind().name());
        payload.put("commitCreated", error.getAuthority().commitCreated());
        payload.put("projectionStatus", "PENDING_RECOVERY");
        payload.put("pendingPhase", error.getPhase().name());
        return payload;
    }

    private static Map<String, Object> conflictPayload(
            long hypothesisId,
            ReviewAction action,
            BranchHeadConflictException error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", hypothesisId);
        payload.put("action", action.name());
        payload.put("projectionStatus", "PRECONDITION_FAILED");
        payload.put("expectedHeadCommit", error.getExpectedHeadCommit());
        payload.put("actualHeadCommit", error.getActualHeadCommit());
        return payload;
    }

    private static Map<String, Object> errorPayload(RuntimeException error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "REVIEW_REJECTED");
        if (error.getMessage() != null && !error.getMessage().isBlank()) {
            payload.put("detail", error.getMessage());
        }
        return payload;
    }

    private void write(
            HttpServletResponse response,
            int status,
            Map<String, ?> payload) throws IOException {
        response.setStatus(status);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), payload);
    }

    private static String applicationPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath == null || contextPath.isEmpty()
                ? uri
                : uri.substring(contextPath.length());
    }

    private static boolean isApplicationAdmin() {
        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> "ROLE_ADMIN".equals(
                                authority.getAuthority()));
    }
}
