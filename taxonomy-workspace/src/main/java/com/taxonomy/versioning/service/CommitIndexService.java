package com.taxonomy.versioning.service;

import com.taxonomy.versioning.model.ArchitectureCommitIndex;
import com.taxonomy.versioning.repository.ArchitectureCommitIndexRepository;
import com.taxonomy.dsl.parser.DslTokenizer;
import com.taxonomy.workspace.storage.DslCommit;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.dto.ElementHistoryAggregation;
import com.taxonomy.dto.RepositoryReadiness;
import com.taxonomy.error.SearchUnavailableException;
import com.taxonomy.workspace.service.RepositoryContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.search.engine.search.predicate.SearchPredicate;
import org.hibernate.search.engine.search.predicate.dsl.SearchPredicateFactory;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Populates and queries repository-scoped commit-history projections.
 *
 * <p>JGit remains authoritative. Every relational row and every Hibernate Search
 * query uses the exact same {@link RepositoryContext}; a null workspace means
 * central history only inside the selected repository, never a global index.</p>
 */
@Service
public class CommitIndexService {

    private static final Logger log = LoggerFactory.getLogger(CommitIndexService.class);

    private final DslGitRepositoryFactory repositoryFactory;
    private final ArchitectureCommitIndexRepository indexRepository;
    private final DslTokenizer tokenizer = new DslTokenizer();

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired(required = false)
    private CommitIndexSearchLifecycle searchLifecycle;

    public CommitIndexService(
            DslGitRepositoryFactory repositoryFactory,
            ArchitectureCommitIndexRepository indexRepository) {
        this.repositoryFactory = repositoryFactory;
        this.indexRepository = indexRepository;
    }

    /** Index every commit not yet projected for this exact tenant and branch. */
    @Transactional
    public int indexBranch(String branch, RepositoryContext context) {
        requireSearchMutationAvailable();
        TenantScope scope = TenantScope.from(context);
        String normalizedBranch = requireText(branch, "branch");
        try {
            DslGitRepository repository = repositoryFactory.resolveRepository(context);
            List<DslCommit> commits = repository.getDslHistory(normalizedBranch);
            return indexCommits(repository, commits, normalizedBranch, scope);
        } catch (IOException exception) {
            throw indexingFailure(scope, normalizedBranch, exception);
        }
    }

    /**
     * Rebuild one branch projection without touching another repository,
     * workspace or branch.
     *
     * <p>The authoritative history is opened before the old projection is
     * deleted. Any later JGit failure propagates and rolls back the surrounding
     * transaction, so a failed rebuild cannot commit an empty replacement.</p>
     */
    @Transactional
    public int rebuildBranch(String branch, RepositoryContext context) {
        requireSearchMutationAvailable();
        TenantScope scope = TenantScope.from(context);
        String normalizedBranch = requireText(branch, "branch");
        try {
            DslGitRepository repository = repositoryFactory.resolveRepository(context);
            List<DslCommit> commits = repository.getDslHistory(normalizedBranch);
            if (commits.isEmpty()) {
                throw new IllegalStateException("Cannot rebuild commit index for missing branch " + normalizedBranch);
            }
            String capturedHead = commits.getFirst().commitId();
            repository.verifyExpectedHead(normalizedBranch, capturedHead);
            List<ArchitectureCommitIndex> existing = indexRepository
                    .findByRepositoryIdAndWorkspaceScopeKeyAndBranchOrderByCommitTimestampDesc(
                            scope.repositoryId(),
                            scope.workspaceScopeKey(),
                            normalizedBranch);
            if (!existing.isEmpty()) {
                indexRepository.deleteAll(existing);
                entityManager.flush();
            }
            int indexed = indexCommits(repository, commits, normalizedBranch, scope);
            repository.verifyExpectedHead(normalizedBranch, capturedHead);
            return indexed;
        } catch (IOException exception) {
            throw indexingFailure(scope, normalizedBranch, exception);
        }
    }

    /** Rebuild only the admitted Git authority, validating it on both sides of the projection write. */
    @Transactional
    public int applyCapturedBranch(String branch, RepositoryContext context, String expected, boolean rebuild) {
        TenantScope scope = TenantScope.from(context);
        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        try {
            repository.verifyExpectedHead(branch, expected);
            int count = rebuild ? rebuildBranch(branch, context) : indexBranch(branch, context);
            repository.verifyExpectedHead(branch, expected);
            return count;
        } catch (IOException exception) {
            throw indexingFailure(scope, branch, exception);
        }
    }

    /** A retry can remove only rows that already existed in this scope when the user requested the purge. */
    @Transactional
    public int purgeCaptured(RepositoryContext context, String operationId) {
        requireSearchMutationAvailable();
        TenantScope scope = TenantScope.from(context);
        List<ArchitectureCommitIndex> rows = entityManager.createQuery("""
                select projection from ArchitectureCommitIndex projection
                where projection.repositoryId=:repository and projection.workspaceScopeKey=:workspace
                and exists (select target.projectionId from HistoryIndexPurgeTarget target
                            where target.operationId=:operation and target.projectionId=projection.id)
                """, ArchitectureCommitIndex.class)
                .setParameter("operation", operationId)
                .setParameter("repository", scope.repositoryId())
                .setParameter("workspace", scope.workspaceScopeKey())
                .getResultList();
        rows.forEach(entityManager::remove);
        entityManager.flush();
        return rows.size();
    }

    /** Delete only the selected central/workspace history projection. */
    @Transactional
    public int purge(RepositoryContext context) {
        requireSearchMutationAvailable();
        TenantScope scope = TenantScope.from(context);
        List<ArchitectureCommitIndex> existing = indexRepository
                .findByRepositoryIdAndWorkspaceScopeKey(
                        scope.repositoryId(), scope.workspaceScopeKey());
        if (existing.isEmpty()) {
            return 0;
        }
        indexRepository.deleteAll(existing);
        entityManager.flush();
        log.info(
                "Purged {} commit-index row(s) for repository={} workspace={}",
                existing.size(),
                scope.repositoryId(),
                scope.workspaceScopeKey());
        return existing.size();
    }

    @Transactional(readOnly = true)
    public List<ArchitectureCommitIndex> search(
            String query,
            RepositoryContext context) {
        return search(query, 50, context);
    }

    /** Full-text search restricted to the exact selected repository/workspace. */
    @Transactional(readOnly = true)
    public List<ArchitectureCommitIndex> search(
            String query,
            int maxResults,
            RepositoryContext context) {
        requireSearchReady();
        if (query == null || query.isBlank() || maxResults <= 0) {
            return Collections.emptyList();
        }
        TenantScope scope = TenantScope.from(context);
        try {
            return textMatches(Search.session(entityManager), scope, query, maxResults);
        } catch (RuntimeException exception) {
            throw searchUnavailable(scope, exception);
        }
    }

    /** Search across indexed branches only after the selected branch head is searchable. */
    @Transactional(readOnly = true)
    public VersionedMatches searchVersioned(String query, int maxResults, RepositoryContext context, String head) {
        requireSearchReady();
        TenantScope scope = TenantScope.from(context);
        String branch = context.branch();
        try {
            SearchSession session = Search.session(entityManager);
            Long projectionId = headProjectionId(scope, branch, head);
            if (projectionId == null) {
                boolean indexed = indexRepository.existsByRepositoryIdAndWorkspaceScopeKeyAndBranch(
                        scope.repositoryId(), scope.workspaceScopeKey(), branch);
                return new VersionedMatches(indexed ? RepositoryReadiness.STALE : RepositoryReadiness.MISSING, List.of());
            }
            if (!isHeadSearchable(session, scope, branch, head, projectionId)) {
                return new VersionedMatches(RepositoryReadiness.STALE, List.of());
            }
            var hits = textMatches(session, scope, query, maxResults);
            // Maintenance can replace the database row or invalidate the search index independently.
            if (!projectionId.equals(headProjectionId(scope, branch, head))
                    || !isHeadSearchable(session, scope, branch, head, projectionId)) {
                return new VersionedMatches(RepositoryReadiness.STALE, List.of());
            }
            return new VersionedMatches(RepositoryReadiness.FRESH, hits);
        } catch (RuntimeException exception) {
            throw searchUnavailable(scope, exception);
        }
    }

    public record VersionedMatches(RepositoryReadiness readiness, List<ArchitectureCommitIndex> hits) { }

    private Long headProjectionId(TenantScope scope, String branch, String head) {
        return indexRepository.findByRepositoryIdAndWorkspaceScopeKeyAndBranchAndCommitId(
                scope.repositoryId(), scope.workspaceScopeKey(), branch, head)
                .map(ArchitectureCommitIndex::getId).orElse(null);
    }

    private boolean isHeadSearchable(SearchSession session, TenantScope scope, String branch, String head, Long projectionId) {
        return session.search(ArchitectureCommitIndex.class)
                .where(f -> f.bool().filter(inScope(f, scope)).filter(f.id().matching(projectionId))
                        .filter(f.match().field("branch").matching(branch))
                        .filter(f.match().field("commitId").matching(head)))
                .fetchTotalHitCount() == 1;
    }

    private List<ArchitectureCommitIndex> textMatches(SearchSession session, TenantScope scope, String query, int maxResults) {
        if (query == null || query.isBlank() || maxResults <= 0) {
            return Collections.emptyList();
        }
        String lower = query.toLowerCase(Locale.ROOT);
        return session.search(ArchitectureCommitIndex.class)
                .where(f -> f.bool().must(inScope(f, scope))
                        .must(f.bool()
                                .should(f.match().field("tokenizedChangeText").matching(lower).boost(1.0f))
                                .should(f.match().field("message").matching(query).boost(0.5f))
                                .should(f.match().field("affectedElementIds").matching(lower).boost(3.0f))
                                .should(f.match().field("affectedRelationIds").matching(lower).boost(2.0f))))
                .sort(f -> f.score())
                .fetchHits(maxResults);
    }

    private static SearchPredicate inScope(SearchPredicateFactory factory, TenantScope scope) {
        return factory.bool()
                .must(factory.match().field("repositoryId").matching(scope.repositoryId()))
                .must(scope.workspaceId() == null
                        ? factory.not(factory.exists().field("workspaceId"))
                        : factory.match().field("workspaceId").matching(scope.workspaceId()))
                .toPredicate();
    }

    private SearchUnavailableException searchUnavailable(TenantScope scope, RuntimeException exception) {
        log.error("Commit history search failed for repository={} workspace={}: {}",
                scope.repositoryId(), scope.workspaceScopeKey(), exception.getClass().getSimpleName());
        return new SearchUnavailableException();
    }

    @Transactional(readOnly = true)
    public List<ArchitectureCommitIndex> findByElement(
            String elementId,
            RepositoryContext context) {
        requireSearchReady();
        if (elementId == null || elementId.isBlank()) {
            return Collections.emptyList();
        }
        TenantScope scope = TenantScope.from(context);
        try {
            SearchSession session = Search.session(entityManager);
            String normalizedElementId = elementId.toLowerCase(Locale.ROOT);
            return session.search(ArchitectureCommitIndex.class)
                    .where(f -> f.bool()
                            .must(f.match()
                                    .field("repositoryId")
                                    .matching(scope.repositoryId()))
                            .must(scope.workspaceId() == null
                                    ? f.not(f.exists().field("workspaceId"))
                                    : f.match().field("workspaceId").matching(scope.workspaceId()))
                            .must(f.bool()
                                    .should(f.match()
                                            .field("affectedElementIds")
                                            .matching(normalizedElementId)
                                            .boost(3.0f))
                                    .should(f.match()
                                            .field("tokenizedChangeText")
                                            .matching(normalizedElementId)
                                            .boost(1.0f))))
                    .sort(f -> f.score())
                    .fetchHits(50);
        } catch (RuntimeException exception) {
            log.error(
                    "Hibernate Search element history failed for repository={} workspace={}: {}",
                    scope.repositoryId(),
                    scope.workspaceScopeKey(),
                    exception.getClass().getSimpleName());
            throw new SearchUnavailableException();
        }
    }

    @Transactional(readOnly = true)
    public List<ArchitectureCommitIndex> findByRelation(
            String relationKey,
            RepositoryContext context) {
        requireSearchReady();
        if (relationKey == null || relationKey.isBlank()) {
            return Collections.emptyList();
        }
        TenantScope scope = TenantScope.from(context);
        try {
            SearchSession session = Search.session(entityManager);
            String normalizedRelationKey = relationKey.toLowerCase(Locale.ROOT);
            return session.search(ArchitectureCommitIndex.class)
                    .where(f -> f.bool()
                            .must(f.match()
                                    .field("repositoryId")
                                    .matching(scope.repositoryId()))
                            .must(scope.workspaceId() == null
                                    ? f.not(f.exists().field("workspaceId"))
                                    : f.match().field("workspaceId").matching(scope.workspaceId()))
                            .must(f.bool()
                                    .should(f.match()
                                            .field("affectedRelationIds")
                                            .matching(normalizedRelationKey)
                                            .boost(3.0f))
                                    .should(f.match()
                                            .field("tokenizedChangeText")
                                            .matching(normalizedRelationKey)
                                            .boost(1.0f))))
                    .sort(f -> f.score())
                    .fetchHits(50);
        } catch (RuntimeException exception) {
            log.error(
                    "Hibernate Search relation history failed for repository={} workspace={}: {}",
                    scope.repositoryId(),
                    scope.workspaceScopeKey(),
                    exception.getClass().getSimpleName());
            throw new SearchUnavailableException();
        }
    }

    /** Aggregate element history and volatility inside one tenant only. */
    @Transactional(readOnly = true)
    public ElementHistoryAggregation aggregateElementHistory(
            String elementId,
            RepositoryContext context) {
        TenantScope scope = TenantScope.from(context);
        List<ArchitectureCommitIndex> commits = findByElement(elementId, context);
        if (commits.isEmpty()) {
            return null;
        }

        long totalCommits = indexRepository.countByRepositoryIdAndWorkspaceScopeKey(
                scope.repositoryId(), scope.workspaceScopeKey());

        Instant firstSeen = commits.stream()
                .map(ArchitectureCommitIndex::getCommitTimestamp)
                .min(Comparator.naturalOrder())
                .orElse(null);
        Instant lastSeen = commits.stream()
                .map(ArchitectureCommitIndex::getCommitTimestamp)
                .max(Comparator.naturalOrder())
                .orElse(null);

        List<String> recentMessages = commits.stream()
                .sorted(Comparator.comparing(
                        ArchitectureCommitIndex::getCommitTimestamp).reversed())
                .limit(5)
                .map(ArchitectureCommitIndex::getMessage)
                .toList();

        double volatility = ElementHistoryAggregation.computeVolatility(
                commits.size(), Math.toIntExact(totalCommits));

        return new ElementHistoryAggregation(
                elementId,
                firstSeen,
                lastSeen,
                commits.size(),
                volatility,
                recentMessages);
    }

    private int indexCommits(
            DslGitRepository repository,
            List<DslCommit> commits,
            String branch,
            TenantScope scope) throws IOException {
        int indexed = 0;
        for (DslCommit commit : commits) {
            if (indexRepository
                    .existsByRepositoryIdAndWorkspaceScopeKeyAndBranchAndCommitId(
                            scope.repositoryId(),
                            scope.workspaceScopeKey(),
                            branch,
                            commit.commitId())) {
                continue;
            }

            String dslText = repository.getDslAtCommit(commit.commitId());
            if (dslText == null) {
                continue;
            }

            ArchitectureCommitIndex entry = new ArchitectureCommitIndex();
            entry.setRepositoryId(scope.repositoryId());
            entry.setWorkspaceId(scope.workspaceId());
            entry.setCommitId(commit.commitId());
            entry.setAuthor(commit.author());
            entry.setCommitTimestamp(commit.timestamp());
            entry.setMessage(commit.message());
            entry.setBranch(branch);
            entry.setChangedFiles("architecture.taxdsl");
            entry.setTokenizedChangeText(tokenizer.tokenize(dslText));

            Set<String> elementIds = tokenizer.extractElementIds(dslText);
            entry.setAffectedElementIds(String.join(",", elementIds));

            Set<String> relationKeys = tokenizer.extractRelationKeys(dslText);
            entry.setAffectedRelationIds(String.join(";", relationKeys));

            indexRepository.save(entry);
            indexed++;
        }

        if (indexed > 0) {
            log.info(
                    "Indexed {} new commit(s) for repository={} workspace={} branch={}",
                    indexed,
                    scope.repositoryId(),
                    scope.workspaceScopeKey(),
                    branch);
        }
        return indexed;
    }

    private IllegalStateException indexingFailure(
            TenantScope scope,
            String branch,
            IOException exception) {
        log.error(
                "Failed to index repository={} workspace={} branch={}",
                scope.repositoryId(),
                scope.workspaceScopeKey(),
                branch,
                exception);
        return new IllegalStateException(
                "Unable to read authoritative JGit history for repository="
                        + scope.repositoryId()
                        + ", workspace=" + scope.workspaceScopeKey()
                        + ", branch=" + branch,
                exception);
    }

    boolean canRecoverHistory() {
        return searchLifecycle == null || switch (searchLifecycle.getState()) {
            case READY, FAILED -> true;
            default -> false;
        };
    }

    private void requireSearchMutationAvailable() {
        // Startup verification runs only once. A mutation admitted afterward
        // can finish its transaction without overlapping the startup mass indexer.
        if (searchLifecycle != null && searchLifecycle.isInitializing()) {
            throw new TransientDataAccessResourceException("Commit history search index is initializing");
        }
    }

    private void requireSearchReady() {
        if (searchLifecycle != null && !searchLifecycle.isReady()) throw new SearchUnavailableException();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    private record TenantScope(
            String repositoryId,
            String workspaceId,
            String workspaceScopeKey) {

        private static TenantScope from(RepositoryContext context) {
            if (context == null) {
                throw new IllegalArgumentException("RepositoryContext must not be null");
            }
            String repositoryId = requireText(context.repositoryId(), "repositoryId");
            String workspaceId = context.workspaceId() == null
                    || context.workspaceId().isBlank()
                    ? null
                    : context.workspaceId().strip();
            return new TenantScope(
                    repositoryId,
                    workspaceId,
                    ArchitectureCommitIndex.scopeKeyFor(workspaceId));
        }
    }
}
