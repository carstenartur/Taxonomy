package com.taxonomy.portfolio.service;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.BlockIdentity;
import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.command.ArchitectureCommand.RelationKey;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.validation.DslValidator;
import com.taxonomy.model.WorkspaceOverlayScope;
import com.taxonomy.portfolio.dto.RequirementIntakeDtos.*;
import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.repository.*;
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
    private final WorkspaceSourceDiscoveryService sources;
    private final WorkspacePortfolioDocumentPort documents;
    private final ObjectProvider<RequirementIntakePolicy> policies;

    public RequirementIntakeCandidates(ArchitectureProjectRepository projects, ProjectRequirementRepository requirements,
            ProjectPortfolioService portfolio, WorkspaceSourceDiscoveryService sources, WorkspacePortfolioDocumentPort documents,
            ObjectProvider<RequirementIntakePolicy> policies) {
        this.projects = projects; this.requirements = requirements; this.portfolio = portfolio;
        this.sources = sources;
        this.documents = documents; this.policies = policies;
    }

    public record Repository(String id, String branch, String title, String description,
                             boolean observed, String sourceCommit) {
        public Repository(String id, String branch, String title, String description) {
            this(id, branch, title, description, false, null);
        }
        private Repository observedAt(String commit) { return new Repository(id, branch, title, description, true, commit); }
    }
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
        List<Repository> sourceRepositories = new ArrayList<>();
        if (projectId == null) {
            for (var project : projectMap.values()) budget.add(new Candidate("project:" + project.getId(), "PROJECT",
                    context.repositoryId(), context.workspaceId(), context.currentBranch(), project.getId(), project.getProjectKey(),
                    null, null, null, project.getTitle(), join(project.getTitle(), project.getDescription(), project.getTargetArchitecture()),
                    null, null, null, List.of(), List.of("NEW", "VIEW")));
            var discovery = sources.readableSources(username);
            budget.partial |= discovery.truncated();
            for (var source : discovery.sources())
                sourceRepositories.add(new Repository(source.repositoryId(), source.branch(), source.title(), source.description()));
        }
        return new Frozen(new Scope(context.repositoryId(), context.workspaceId(), WorkspaceOverlayScope.keyFor(context.workspaceId()),
                context.currentBranch()), projectId, budget.candidates, sourceRepositories, budget.coverage(), allowed);
    }

    /** Canonical central documents are immutable observations, never another workspace's projections. */
    public Frozen discover(Frozen frozen, String username) {
        var budget = new Budget(frozen);
        var elements = new ArrayList<Candidate>();
        var relations = new ArrayList<Candidate>();
        List<Repository> observations = new ArrayList<>();
        for (var source : frozen.repositories()) {
            // A second authorization precedes repository content access, after the initial DB snapshot.
            if (!canRead(source.id(), username)) throw PortfolioException.notFound("Architecture is unavailable");
            Repository observed = source;
            try {
                var repository = documents.resolveRepository(new WorkspaceContext(username, null, source.branch(), source.id()));
                String head = repository.getHeadCommit(source.branch());
                observed = source.observedAt(head);
                String dsl = head == null ? null : repository.getDslAtCommit(head);
                if (head != null && dsl == null) budget.partial = true;
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
                            null, null, List.of(), List.of("VIEW", "NEW"), head, block.property("x-version")));
                }
                captureArchitecture(document, observed, budget, elements, relations);
            } catch (java.io.IOException | IllegalArgumentException unavailable) { budget.partial = true; }
            finally {
                if (!canRead(source.id(), username)) throw PortfolioException.notFound("Architecture is unavailable");
                observations.add(observed);
                budget.add(new Candidate("architecture:" + source.id(), "ARCHITECTURE", source.id(), null, source.branch(),
                        null, null, null, null, null, source.title(), join(source.title(), source.description()),
                        null, null, null, List.of(), List.of("VIEW", "NEW"), observed.sourceCommit(), null));
            }
        }
        // Preserve the existing requirement/destination pool. Share the remaining budget between
        // the two architecture kinds so a document with many elements can still surface relations.
        for (int i = 0; i < Math.max(elements.size(), relations.size()); i++) {
            if (i < elements.size()) budget.admit(elements.get(i));
            if (i < relations.size()) budget.admit(relations.get(i));
        }
        return new Frozen(frozen.scope(), frozen.projectId(), budget.candidates, observations, budget.coverage(), frozen.providerAllowed());
    }

    private static void captureArchitecture(DocumentAst document, Repository source, Budget budget,
                                            List<Candidate> elements, List<Candidate> relations) {
        Map<String, BlockAst> elementBlocks = unambiguousBlocks(document, "element", budget);
        for (var block : elementBlocks.values()) {
            String identity = block.getHeaderTokens().getFirst();
            String title = block.property("title");
            String text = join(title, block.property("description"));
            if (!text.isBlank()) budget.defer(elements,
                    architectureObject(source, "ARCHITECTURE_ELEMENT", identity, title, text));
        }
        for (var block : unambiguousBlocks(document, "relation", budget).values()) {
            if (!"accepted".equals(block.property("status"))) continue;
            var tokens = block.getHeaderTokens();
            var from = elementBlocks.get("element:" + tokens.get(0));
            var to = elementBlocks.get("element:" + tokens.get(2));
            if (from == null || to == null || !DslValidator.relationTypes().contains(tokens.get(1))) {
                budget.partial = true;
                continue;
            }
            var key = new RelationKey(tokens.get(0), tokens.get(1), tokens.get(2));
            String text = join(key.id(), from.property("title"), to.property("title"), block.property("provenance"));
            budget.defer(relations, architectureObject(source, "ARCHITECTURE_RELATION", key.id(), key.id(), text));
        }
    }

    private static Map<String, BlockAst> unambiguousBlocks(DocumentAst document, String kind, Budget budget) {
        Map<String, BlockAst> blocks = new LinkedHashMap<>();
        Set<String> duplicates = new HashSet<>();
        for (var block : document.blocksOfKind(kind)) {
            var tokens = block.getHeaderTokens();
            if (tokens.isEmpty() || "relation".equals(kind) && tokens.size() != 3) {
                budget.partial = true;
                continue;
            }
            String key = BlockIdentity.key(block);
            if (blocks.putIfAbsent(key, block) != null) duplicates.add(key);
        }
        if (!duplicates.isEmpty()) {
            budget.partial = true;
            duplicates.forEach(blocks::remove);
        }
        return blocks;
    }

    private static Candidate architectureObject(Repository source, String kind, String identity, String title, String text) {
        String prefix = kind.toLowerCase(Locale.ROOT).replace('_', '-');
        return new Candidate(prefix + ":" + source.id() + ":" + identity, kind, source.id(), null, source.branch(),
                null, null, null, null, null, title, text, RequirementIntakeService.inputHash(text), null, null,
                List.of(), List.of("VIEW"), source.sourceCommit(), null, identity);
    }

    /** Mutable branch heads are checked outside DB transactions; response links retain their immutable commit. */
    public void recheckSources(Frozen frozen, String username) {
        for (var source : frozen.repositories()) {
            if (!canRead(source.id(), username)) throw PortfolioException.notFound("Architecture is unavailable");
            if (!source.observed()) continue; // Discovery failed, so coverage is already explicitly incomplete.
            try {
                var repository = documents.resolveRepository(new WorkspaceContext(username, null, source.branch(), source.id()));
                String current = repository.getHeadCommit(source.branch());
                if (!Objects.equals(source.sourceCommit(), current))
                    throw new PortfolioException(PortfolioException.Kind.CONFLICT, "INTAKE_SOURCE_CHANGED",
                            "An architecture changed during intake review; review again", null);
            } catch (java.io.IOException | IllegalArgumentException unavailable) {
                throw new PortfolioException(PortfolioException.Kind.UNAVAILABLE, "INTAKE_SOURCE_UNAVAILABLE",
                        "An architecture source could not be checked; review again", unavailable);
            } finally {
                if (!canRead(source.id(), username)) throw PortfolioException.notFound("Architecture is unavailable");
            }
        }
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
        if (!sources.canReadContext(username, context)) throw PortfolioException.notFound("Review context is unavailable");
    }
    private boolean canRead(String repositoryId, String username) {
        return sources.canReadRepository(repositoryId, username);
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
            admit(candidate);
        }
        private void defer(List<Candidate> queue, Candidate candidate) {
            scanned++;
            if (queue.size() < MAXIMUM_CANDIDATES) queue.add(candidate);
            else partial = true;
        }
        private void admit(Candidate candidate) {
            if (candidates.size() >= MAXIMUM_CANDIDATES || characters + candidate.text().length() > MAXIMUM_EVIDENCE_CHARACTERS) {
                partial = true; return;
            }
            candidates.add(candidate); characters += candidate.text().length();
        }
        private Coverage coverage() { return new Coverage(scanned, candidates.size(), partial); }
    }
}
