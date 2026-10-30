package com.taxonomy.portfolio.service;

import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.model.WorkspaceOverlayScope;
import com.taxonomy.portfolio.dto.RequirementIntakeDtos.*;
import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.repository.*;
import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Bounded evidence capture. No projection materialization, workspace creation or model calls. */
@Service
public class RequirementIntakeCandidates {
    static final int MAXIMUM_DOCUMENT_CHARACTERS = 4_000_000;
    private static final int MAXIMUM_CANDIDATES = 32;
    private static final int MAXIMUM_EVIDENCE_CHARACTERS = 200_000;
    private final ArchitectureProjectRepository projects;
    private final ProjectRequirementRepository requirements;
    private final ProjectPortfolioService portfolio;
    private final SystemRepositoryRepository repositories;
    private final RepositoryMembershipService membership;
    private final UserWorkspaceRepository workspaces;
    private final WorkspacePortfolioDocumentPort documents;
    private final ObjectProvider<RequirementIntakePolicy> policies;

    public RequirementIntakeCandidates(ArchitectureProjectRepository projects, ProjectRequirementRepository requirements,
            ProjectPortfolioService portfolio, SystemRepositoryRepository repositories, RepositoryMembershipService membership,
            UserWorkspaceRepository workspaces, WorkspacePortfolioDocumentPort documents,
            ObjectProvider<RequirementIntakePolicy> policies) {
        this.projects = projects; this.requirements = requirements; this.portfolio = portfolio;
        this.repositories = repositories; this.membership = membership; this.workspaces = workspaces;
        this.documents = documents; this.policies = policies;
    }

    public record Repository(String id, String branch, String title, String description) { }
    public record Frozen(Scope scope, Long projectId, List<Candidate> candidates, List<Repository> repositories,
                         Coverage coverage, boolean providerAllowed) {
        public Frozen { candidates = List.copyOf(candidates); repositories = List.copyOf(repositories); }
    }

    @Transactional(readOnly = true)
    public Frozen freeze(Long projectId, String username, WorkspaceContext context) {
        authorize(username, context);
        var selected = projectId == null ? null : portfolio.requireProject(projectId, username, context);
        boolean allowed = selected == null || allowed(projectId, username, context);
        var budget = new Budget();
        String scopeKey = PortfolioScope.key(username, context);
        Map<Long, ArchitectureProject> projectMap = new LinkedHashMap<>();
        if (selected != null) projectMap.put(projectId, selected);
        else {
            var page = projects.findByScopeKeyOrderByUpdatedAtDesc(scopeKey, PageRequest.of(0, MAXIMUM_CANDIDATES));
            budget.partial |= page.hasNext();
            for (var project : page) {
                if (!allowed(project.getId(), username, context)) { budget.partial = true; continue; }
                projectMap.put(project.getId(), project);
            }
        }
        var rows = requirements.findIntakeRows(scopeKey, projectId, PageRequest.of(0, MAXIMUM_CANDIDATES));
        budget.partial |= rows.hasNext();
        for (var row : rows) {
            var project = projectMap.get(row.projectId());
            if (project == null || row.versionId() == null || row.text() == null) { budget.partial = true; continue; }
            budget.add(new Candidate("requirement:" + row.id(), "REQUIREMENT", context.repositoryId(), context.workspaceId(),
                    context.currentBranch(), row.projectId(), project.getProjectKey(), row.id(), row.versionId(), row.requirementKey(),
                    row.title(), row.text(), row.contentHash(), null, null, List.of(), List.of("NEW", "CHANGE", "LINK", "VIEW")));
        }
        List<Repository> sources = new ArrayList<>();
        if (projectId == null) {
            for (var project : projectMap.values()) budget.add(new Candidate("project:" + project.getId(), "PROJECT",
                    context.repositoryId(), context.workspaceId(), context.currentBranch(), project.getId(), project.getProjectKey(),
                    null, null, null, project.getTitle(), join(project.getTitle(), project.getDescription(), project.getTargetArchitecture()),
                    null, null, null, List.of(), List.of("NEW", "VIEW")));
            var page = repositories.findByLifecycleStateOrderByDisplayNameAsc(RepositoryLifecycleState.ACTIVE, PageRequest.of(0, 16));
            budget.partial |= page.hasNext();
            for (var repository : page) if (membership.canRead(repository, username))
                sources.add(new Repository(repository.getRepositoryId(), repository.getDefaultBranch(),
                        repository.getDisplayName(), repository.getDescription()));
        }
        return new Frozen(new Scope(context.repositoryId(), context.workspaceId(), WorkspaceOverlayScope.keyFor(context.workspaceId()),
                context.currentBranch()), projectId, budget.candidates, sources, budget.coverage(), allowed);
    }

    /** Canonical central documents are immutable observations, never another workspace's projections. */
    public Frozen discover(Frozen frozen, String username) {
        var budget = new Budget(frozen);
        for (var source : frozen.repositories()) {
            // A second authorization precedes repository content access, after the initial DB snapshot.
            if (!canRead(source.id(), username)) throw PortfolioException.notFound("Architecture is unavailable");
            budget.add(new Candidate("architecture:" + source.id(), "ARCHITECTURE", source.id(), null, source.branch(),
                    null, null, null, null, null, source.title(), join(source.title(), source.description()),
                    null, null, null, List.of(), List.of("VIEW", "NEW")));
            try {
                var repository = documents.resolveRepository(new WorkspaceContext(username, null, source.branch(), source.id()));
                String head = repository.getHeadCommit(source.branch());
                String dsl = head == null ? null : repository.getDslAtCommit(head);
                if (dsl == null || dsl.isBlank()) continue;
                if (dsl.length() > MAXIMUM_DOCUMENT_CHARACTERS) { budget.partial = true; continue; }
                var document = new TaxDslParser().parse(dsl, "intake-central.taxdsl");
                for (var block : document.blocksOfKind("requirement")) {
                    String text = block.property("text");
                    if (text == null || text.isBlank() || block.getHeaderTokens().isEmpty()) continue;
                    String identity = block.getHeaderTokens().getFirst();
                    budget.add(new Candidate("architecture-requirement:" + source.id() + ":" + identity,
                            "ARCHITECTURE_REQUIREMENT", source.id(), null, source.branch(), null, block.property("x-project-key"),
                            null, null, Objects.toString(block.property("x-requirement-key"), identity),
                            Objects.toString(block.property("title"), identity), text, RequirementIntakeService.inputHash(text),
                            null, null, List.of(), List.of("VIEW", "NEW")));
                }
            } catch (java.io.IOException | IllegalArgumentException unavailable) { budget.partial = true; }
        }
        return new Frozen(frozen.scope(), frozen.projectId(), budget.candidates, frozen.repositories(), budget.coverage(), frozen.providerAllowed());
    }

    @Transactional(readOnly = true)
    public void recheck(Frozen frozen, String username, WorkspaceContext context) {
        if (!Objects.equals(frozen.scope().repositoryId(), context.repositoryId())
                || !Objects.equals(frozen.scope().workspaceId(), context.workspaceId())
                || !Objects.equals(frozen.scope().branch(), context.currentBranch()))
            throw PortfolioException.conflict("The review context changed");
        authorize(username, context);
        if (frozen.projectId() != null) portfolio.requireProject(frozen.projectId(), username, context);
        for (var repository : frozen.repositories()) if (!canRead(repository.id(), username))
            throw PortfolioException.notFound("Architecture is unavailable");
        for (var candidate : frozen.candidates()) if (candidate.requirementId() != null) {
            var row = requirements.findReadRowByIdAndProjectIdAndScopeKey(candidate.requirementId(), candidate.projectId(),
                    PortfolioScope.key(username, context)).orElseThrow(() -> PortfolioException.notFound("Requirement is unavailable"));
            if (!Objects.equals(row.versionId(), candidate.requirementVersionId()) || !Objects.equals(row.contentHash(), candidate.contentHash()))
                throw PortfolioException.conflict("Requirement changed during intake review; review again");
        }
    }

    private boolean allowed(Long projectId, String username, WorkspaceContext context) {
        return policies.orderedStream().allMatch(policy -> policy.allowsProviderReview(projectId, username, context));
    }
    private void authorize(String username, WorkspaceContext context) {
        if (context == null || !Objects.equals(username, context.username())) throw PortfolioException.notFound("Review context is unavailable");
        if (context.workspaceId() == null || context.workspaceId().isBlank()) {
            if (!canRead(context.repositoryId(), username)) throw PortfolioException.notFound("Architecture is unavailable");
        } else {
            var workspace = workspaces.findByWorkspaceId(context.workspaceId())
                    .orElseThrow(() -> PortfolioException.notFound("Workspace is unavailable"));
            if (!Objects.equals(workspace.getUsername(), username) || workspace.isArchived() || workspace.isShared()
                    || !Objects.equals(workspace.getSourceRepositoryId(), context.repositoryId())
                    || !Objects.equals(workspace.getCurrentBranch(), context.currentBranch())
                    || workspace.getProvisioningStatus() != WorkspaceProvisioningStatus.READY)
                throw PortfolioException.notFound("Workspace is unavailable");
        }
    }
    private boolean canRead(String repositoryId, String username) {
        return repositories.findByRepositoryId(repositoryId).filter(repository -> membership.canRead(repository, username)).isPresent();
    }
    private static String join(String... values) {
        return Arrays.stream(values).filter(Objects::nonNull).filter(value -> !value.isBlank()).reduce((left, right) -> left + "\n" + right).orElse("");
    }
    private static final class Budget {
        private final List<Candidate> candidates = new ArrayList<>();
        private int characters;
        private int scanned;
        private boolean partial;
        private Budget() { }
        private Budget(Frozen frozen) {
            candidates.addAll(frozen.candidates()); scanned = frozen.coverage().scanned(); partial = frozen.coverage().truncated();
            characters = candidates.stream().mapToInt(candidate -> candidate.text().length()).sum();
        }
        private void add(Candidate candidate) {
            scanned++;
            if (candidates.size() >= MAXIMUM_CANDIDATES || characters + candidate.text().length() > MAXIMUM_EVIDENCE_CHARACTERS) {
                partial = true; return;
            }
            candidates.add(candidate); characters += candidate.text().length();
        }
        private Coverage coverage() { return new Coverage(scanned, candidates.size(), partial); }
    }
}
