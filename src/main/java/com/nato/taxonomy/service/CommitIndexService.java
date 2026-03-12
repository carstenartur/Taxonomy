package com.nato.taxonomy.service;

import com.nato.taxonomy.dsl.parser.DslTokenizer;
import com.nato.taxonomy.dsl.storage.DslCommit;
import com.nato.taxonomy.dsl.storage.DslGitRepository;
import com.nato.taxonomy.model.ArchitectureCommitIndex;
import com.nato.taxonomy.repository.ArchitectureCommitIndexRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Populates and queries the {@link ArchitectureCommitIndex} from JGit history.
 *
 * <p>Each commit on a DSL branch is parsed and tokenized to enable
 * history search over architecture evolution.
 */
@Service
public class CommitIndexService {

    private static final Logger log = LoggerFactory.getLogger(CommitIndexService.class);

    private final DslGitRepository gitRepository;
    private final ArchitectureCommitIndexRepository indexRepository;
    private final DslTokenizer tokenizer = new DslTokenizer();

    public CommitIndexService(DslGitRepository gitRepository,
                              ArchitectureCommitIndexRepository indexRepository) {
        this.gitRepository = gitRepository;
        this.indexRepository = indexRepository;
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
     * Search the commit index by a query string.
     */
    @Transactional(readOnly = true)
    public List<ArchitectureCommitIndex> search(String query) {
        return indexRepository.searchByTokenizedText(query);
    }

    /**
     * Find commits that affected a specific element.
     */
    @Transactional(readOnly = true)
    public List<ArchitectureCommitIndex> findByElement(String elementId) {
        return indexRepository.findByAffectedElement(elementId);
    }

    /**
     * Find commits that affected a specific relation.
     */
    @Transactional(readOnly = true)
    public List<ArchitectureCommitIndex> findByRelation(String relationKey) {
        return indexRepository.findByAffectedRelation(relationKey);
    }
}
