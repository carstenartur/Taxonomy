package com.taxonomy.architecture.service;

import com.taxonomy.dsl.parser.DslTokenizer;
import com.taxonomy.dsl.storage.DslCommit;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dto.ElementHistoryAggregation;
import com.taxonomy.architecture.model.ArchitectureCommitIndex;
import com.taxonomy.architecture.repository.ArchitectureCommitIndexRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
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
 * Populates and queries the {@link ArchitectureCommitIndex} from JGit history.
 *
 * <p>Each commit on a DSL branch is parsed and tokenized to enable
 * history search over architecture evolution.
 *
 * <p>Search methods use <b>Hibernate Search</b> (Lucene backend) with custom
 * analyzers ({@code "dsl"}, {@code "csv-keyword"}) for full-text queries,
 * replacing the previous JPQL {@code LIKE}/{@code LOWER()} approach.
 */
@Service
public class CommitIndexService {

    private static final Logger log = LoggerFactory.getLogger(CommitIndexService.class);

    private final DslGitRepository gitRepository;
    private final ArchitectureCommitIndexRepository indexRepository;
    private final WorkspaceContextResolver contextResolver;
    private final DslTokenizer tokenizer = new DslTokenizer();

    @PersistenceContext
    private EntityManager entityManager;

    public CommitIndexService(DslGitRepository gitRepository,
                              ArchitectureCommitIndexRepository indexRepository,
                              WorkspaceContextResolver contextResolver) {
        this.gitRepository = gitRepository;
        this.indexRepository = indexRepository;
        this.contextResolver = contextResolver;
    }

    /**
     * Index all unindexed commits on the given branch.
     *
     * @param branch the Git branch to index
     * @return number of newly indexed commits
     */
    @Transactional
    public int indexBranch(String branch) {
        try {
            List<DslCommit> commits = gitRepository.getDslHistory(branch);
            int indexed = 0;

            for (DslCommit commit : commits) {
                if (indexRepository.existsByCommitId(commit.commitId())) {
                    continue; // already indexed
                }

                String dslText = gitRepository.getDslAtCommit(commit.commitId());
                if (dslText == null) {
                    continue;
                }

                ArchitectureCommitIndex entry = new ArchitectureCommitIndex();
                entry.setCommitId(commit.commitId());
                entry.setAuthor(commit.author());
                entry.setCommitTimestamp(commit.timestamp());
                entry.setMessage(commit.message());
                entry.setBranch(branch);
                entry.setChangedFiles("architecture.taxdsl");

                // Tokenize DSL content
                String tokenized = tokenizer.tokenize(dslText);
                entry.setTokenizedChangeText(tokenized);

                // Extract affected IDs
                Set<String> elementIds = tokenizer.extractElementIds(dslText);
                entry.setAffectedElementIds(String.join(",", elementIds));

                Set<String> relationKeys = tokenizer.extractRelationKeys(dslText);
                entry.setAffectedRelationIds(String.join(";", relationKeys));

                indexRepository.save(entry);
                indexed++;
            }

            if (indexed > 0) {
                log.info("Indexed {} new commit(s) on branch '{}'", indexed, branch);
            }
            return indexed;
        } catch (IOException e) {
            log.error("Failed to index branch '{}'", branch, e);
            return 0;
        }
    }

    /**
     * Resolves the current branch filter for workspace-scoped search.
     * Returns {@code null} if no branch filter should be applied (SHARED context).
     */
    private String resolveBranchFilter() {
        try {
            WorkspaceContext ctx = contextResolver.resolveCurrentContext();
            if (!WorkspaceContext.SHARED.equals(ctx)) {
                return ctx.currentBranch();
            }
        } catch (Exception e) {
            log.debug("Could not resolve workspace branch filter: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Full-text search across commit history using Hibernate Search.
     *
     * <p>Searches tokenized DSL text (with boost 1.0), commit messages (boost 0.5),
     * and affected element/relation IDs (boost 3.0) for relevance-ranked results.
     *
     * @param query the search query
     * @return matching commits ranked by relevance
     */
    @Transactional(readOnly = true)
    public List<ArchitectureCommitIndex> search(String query) {
        return search(query, 50);
    }

    /**
     * Full-text search across commit history using Hibernate Search.
     *
     * @param query      the search query
     * @param maxResults maximum number of results to return
     * @return matching commits ranked by relevance
     */
    @Transactional(readOnly = true)
    public List<ArchitectureCommitIndex> search(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        String activeBranch = resolveBranchFilter();
        try {
            SearchSession session = Search.session(entityManager);
            String lower = query.toLowerCase(Locale.ROOT);

            return session.search(ArchitectureCommitIndex.class)
                    .where(f -> {
                        var bool = f.bool();
                        if (activeBranch != null) {
                            bool.must(f.match().field("branch").matching(activeBranch));
                        }
                        bool.should(f.match().field("tokenizedChangeText").matching(lower).boost(1.0f))
                            .should(f.match().field("message").matching(query).boost(0.5f))
                            .should(f.match().field("affectedElementIds").matching(lower).boost(3.0f))
                            .should(f.match().field("affectedRelationIds").matching(lower).boost(2.0f));
                        return bool;
                    })
                    .sort(f -> f.score())
                    .fetchHits(maxResults);
        } catch (Exception e) {
            log.error("Hibernate Search commit search failed for '{}': {}", query, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Find commits that affected a specific element using Hibernate Search.
     *
     * @param elementId the element ID (e.g., "CP-1023")
     * @return matching commits sorted by relevance
     */
    @Transactional(readOnly = true)
    public List<ArchitectureCommitIndex> findByElement(String elementId) {
        if (elementId == null || elementId.isBlank()) {
            return Collections.emptyList();
        }
        String activeBranch = resolveBranchFilter();
        try {
            SearchSession session = Search.session(entityManager);
            return session.search(ArchitectureCommitIndex.class)
                    .where(f -> {
                        var bool = f.bool();
                        if (activeBranch != null) {
                            bool.must(f.match().field("branch").matching(activeBranch));
                        }
                        bool.should(f.match().field("affectedElementIds")
                                    .matching(elementId.toLowerCase(Locale.ROOT)).boost(3.0f))
                            .should(f.match().field("tokenizedChangeText")
                                    .matching(elementId.toLowerCase(Locale.ROOT)).boost(1.0f));
                        return bool;
                    })
                    .sort(f -> f.score())
                    .fetchHits(50);
        } catch (Exception e) {
            log.error("Hibernate Search element search failed for '{}': {}", elementId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Find commits that affected a specific relation using Hibernate Search.
     *
     * @param relationKey the relation key (e.g., "CP-1023 REALIZES CR-1047")
     * @return matching commits sorted by relevance
     */
    @Transactional(readOnly = true)
    public List<ArchitectureCommitIndex> findByRelation(String relationKey) {
        if (relationKey == null || relationKey.isBlank()) {
            return Collections.emptyList();
        }
        String activeBranch = resolveBranchFilter();
        try {
            SearchSession session = Search.session(entityManager);
            return session.search(ArchitectureCommitIndex.class)
                    .where(f -> {
                        var bool = f.bool();
                        if (activeBranch != null) {
                            bool.must(f.match().field("branch").matching(activeBranch));
                        }
                        bool.should(f.match().field("affectedRelationIds")
                                    .matching(relationKey.toLowerCase(Locale.ROOT)).boost(3.0f))
                            .should(f.match().field("tokenizedChangeText")
                                    .matching(relationKey.toLowerCase(Locale.ROOT)).boost(1.0f));
                        return bool;
                    })
                    .sort(f -> f.score())
                    .fetchHits(50);
        } catch (Exception e) {
            log.error("Hibernate Search relation search failed for '{}': {}", relationKey, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Build an aggregated history view for a specific element.
     *
     * <p>Computes firstSeen, lastSeen, occurrence count, volatility,
     * and recent commit messages — useful for understanding how
     * an element has evolved over time.
     *
     * @param elementId the element ID (e.g., "CP-1023")
     * @return the aggregation, or {@code null} if no history found
     */
    @Transactional(readOnly = true)
    public ElementHistoryAggregation aggregateElementHistory(String elementId) {
        List<ArchitectureCommitIndex> commits = findByElement(elementId);
        if (commits.isEmpty()) {
            return null;
        }

        long totalCommits = indexRepository.count();

        Instant firstSeen = commits.stream()
                .map(ArchitectureCommitIndex::getCommitTimestamp)
                .min(Comparator.naturalOrder())
                .orElse(null);
        Instant lastSeen = commits.stream()
                .map(ArchitectureCommitIndex::getCommitTimestamp)
                .max(Comparator.naturalOrder())
                .orElse(null);

        List<String> recentMessages = commits.stream()
                .sorted(Comparator.comparing(ArchitectureCommitIndex::getCommitTimestamp).reversed())
                .limit(5)
                .map(ArchitectureCommitIndex::getMessage)
                .toList();

        double volatility = ElementHistoryAggregation.computeVolatility(commits.size(), (int) totalCommits);

        return new ElementHistoryAggregation(
                elementId, firstSeen, lastSeen,
                commits.size(), volatility, recentMessages);
    }
}
