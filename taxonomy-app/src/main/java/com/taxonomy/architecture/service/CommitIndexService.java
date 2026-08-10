package com.taxonomy.architecture.service;

import com.taxonomy.architecture.model.ArchitectureCommitIndex;
import com.taxonomy.architecture.repository.ArchitectureCommitIndexRepository;
import com.taxonomy.dsl.parser.DslTokenizer;
import com.taxonomy.dsl.storage.DslCommit;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dto.ElementHistoryAggregation;
import com.taxonomy.workspace.service.RepositoryContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public CommitIndexService(
            DslGitRepositoryFactory repositoryFactory,
            ArchitectureCommitIndexRepository indexRepository) {
        this.repositoryFactory = repositoryFactory;
        this.indexRepository = indexRepository;
    }

    /** Index every commit not yet projected for this exact tenant and branch. */
    @Transactional
    public int indexBranch(String branch, RepositoryContext context) {
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
        TenantScope scope = TenantScope.from(context);
        String normalizedBranch = requireText(branch, "branch");
        try {
            DslGitRepository repository = repositoryFactory.resolveRepository(context);
            List<DslCommit> commits = repository.getDslHistory(normalizedBranch);
            List<ArchitectureCommitIndex> existing = indexRepository
                    .findByRepositoryIdAndWorkspaceScopeKeyAndBranchOrderByCommitTimestampDesc(
                            scope.repositoryId(),
                            scope.workspaceScopeKey(),
                            normalizedBranch);
            if (!existing.isEmpty()) {
                indexRepository.deleteAll(existing);
                entityManager.flush();
            }
            return indexCommits(repository, commits, normalizedBranch, scope);
        } catch (IOException exception) {
            throw indexingFailure(scope, normalizedBranch, exception);
        }
    }

    /** Delete only the selected central/workspace history projection. */
    @Transactional
    public int purge(RepositoryContext context) {
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
        if (query == null || query.isBlank() || maxResults <= 0) {
            return Collections.emptyList();
        }
        TenantScope scope = TenantScope.from(context);
        try {
            SearchSession session = Search.session(entityManager);
            String lower = query.toLowerCase(Locale.ROOT);

            return session.search(ArchitectureCommitIndex.class)
                    .where(f -> f.bool()
                            .must(f.match()
                                    .field("repositoryId")
                                    .matching(scope.repositoryId()))
                            .must(f.match()
                                    .field("workspaceScopeKey")
                                    .matching(scope.workspaceScopeKey()))
                            .must(f.bool()
                                    .should(f.match()
                                            .field("tokenizedChangeText")
                                            .matching(lower)
                                            .boost(1.0f))
                                    .should(f.match()
                                            .field("message")
                                            .matching(query)
                                            .boost(0.5f))
                                    .should(f.match()
                                            .field("affectedElementIds")
                                            .matching(lower)
                                            .boost(3.0f))
                                    .should(f.match()
                                            .field("affectedRelationIds")
                                            .matching(lower)
                                            .boost(2.0f))))
                    .sort(f -> f.score())
                    .fetchHits(maxResults);
        } catch (RuntimeException exception) {
            log.error(
                    "Hibernate Search commit search failed for repository={} workspace={}: {}",
                    scope.repositoryId(),
                    scope.workspaceScopeKey(),
                    exception.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    @Transactional(readOnly = true)
    public List<ArchitectureCommitIndex> findByElement(
            String elementId,
            RepositoryContext context) {
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
                            .must(f.match()
                                    .field("workspaceScopeKey")
                                    .matching(scope.workspaceScopeKey()))
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
            return Collections.emptyList();
        }
    }

    @Transactional(readOnly = true)
    public List<ArchitectureCommitIndex> findByRelation(
            String relationKey,
            RepositoryContext context) {
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
                            .must(f.match()
                                    .field("workspaceScopeKey")
                                    .matching(scope.workspaceScopeKey()))
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
            return Collections.emptyList();
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
