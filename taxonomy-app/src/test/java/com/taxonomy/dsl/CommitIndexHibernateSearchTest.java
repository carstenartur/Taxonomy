package com.taxonomy.dsl;

import com.taxonomy.architecture.model.ArchitectureCommitIndex;
import com.taxonomy.architecture.repository.ArchitectureCommitIndexRepository;
import com.taxonomy.architecture.service.CommitIndexService;
import com.taxonomy.workspace.model.RepositoryOwnerType;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.SystemRepositoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Integration evidence for repository-scoped Hibernate Search commit history. */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class CommitIndexHibernateSearchTest {

    private static final String SECONDARY_REPOSITORY_ID = "commit-search-repo-b";

    @Autowired
    private CommitIndexService commitIndexService;

    @Autowired
    private ArchitectureCommitIndexRepository indexRepository;

    @Autowired
    private SystemRepositoryService systemRepositoryService;

    @Autowired
    private SystemRepositoryRepository systemRepositoryRepository;

    private RepositoryContext centralA;
    private RepositoryContext centralB;
    private RepositoryContext workspaceA1;
    private RepositoryContext workspaceA2;

    @BeforeEach
    void setUp() {
        indexRepository.deleteAll();
        String primaryRepositoryId = systemRepositoryService
                .getPrimaryRepository()
                .getRepositoryId();
        ensureSecondaryRepository();
        centralA = RepositoryContext.centralRead(primaryRepositoryId, "test", "admin");
        centralB = RepositoryContext.centralRead(
                SECONDARY_REPOSITORY_ID, "test", "admin");
        workspaceA1 = RepositoryContext.workspace(
                primaryRepositoryId, "workspace-a1", "test", "admin");
        workspaceA2 = RepositoryContext.workspace(
                primaryRepositoryId, "workspace-a2", "test", "admin");
    }

    @Test
    void searchesTokenizedDslMessageElementsAndRelationsInsideOneTenant() {
        indexRepository.save(createEntry(
                centralA,
                "abc123",
                "test",
                "Initial secure voice architecture",
                "CP-1023 STRUCT:element REL:REALIZES DOM:Capability CR-1047",
                "CP-1023,CR-1047",
                "CP-1023 REALIZES CR-1047"));

        assertThat(commitIndexService.search("cp-1023", centralA))
                .extracting(ArchitectureCommitIndex::getCommitId)
                .containsExactly("abc123");
        assertThat(commitIndexService.search("secure voice", centralA))
                .extracting(ArchitectureCommitIndex::getCommitId)
                .containsExactly("abc123");
        assertThat(commitIndexService.findByElement("CP-1023", centralA))
                .extracting(ArchitectureCommitIndex::getCommitId)
                .containsExactly("abc123");
        assertThat(commitIndexService.findByRelation(
                "CP-1023 REALIZES CR-1047", centralA))
                .extracting(ArchitectureCommitIndex::getCommitId)
                .containsExactly("abc123");
    }

    @Test
    void identicalCommitAndBranchCanExistInIndependentTenantScopes() {
        String sharedCommit = "a".repeat(40);
        indexRepository.save(createEntry(
                centralA, sharedCommit, "draft", "Central A", "CP-7777", "CP-7777", ""));
        indexRepository.save(createEntry(
                centralB, sharedCommit, "draft", "Central B", "CP-7777", "CP-7777", ""));
        indexRepository.save(createEntry(
                workspaceA1, sharedCommit, "draft", "Workspace A1", "CP-7777", "CP-7777", ""));
        indexRepository.save(createEntry(
                workspaceA2, sharedCommit, "draft", "Workspace A2", "CP-7777", "CP-7777", ""));

        assertOnlyMessage(centralA, "Central A");
        assertOnlyMessage(centralB, "Central B");
        assertOnlyMessage(workspaceA1, "Workspace A1");
        assertOnlyMessage(workspaceA2, "Workspace A2");
    }

    @Test
    void sameTenantCommitCanBeIndexedOnDifferentBranchesButNotDuplicatedOnOneBranch() {
        String commit = "b".repeat(40);
        indexRepository.saveAndFlush(createEntry(
                centralA, commit, "draft", "Draft", "CP-8000", "CP-8000", ""));
        indexRepository.saveAndFlush(createEntry(
                centralA, commit, "review", "Review", "CP-8000", "CP-8000", ""));

        assertThat(commitIndexService.search("CP-8000", centralA)).hasSize(2);

        ArchitectureCommitIndex duplicate = createEntry(
                centralA, commit, "draft", "Duplicate", "CP-8000", "CP-8000", "");
        assertThatThrownBy(() -> indexRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void purgeRemovesOnlyTheExactWorkspaceProjection() {
        indexRepository.save(createEntry(
                centralA, "c".repeat(40), "draft", "Central", "CP-9000", "CP-9000", ""));
        indexRepository.save(createEntry(
                workspaceA1, "d".repeat(40), "draft", "A1", "CP-9000", "CP-9000", ""));
        indexRepository.save(createEntry(
                workspaceA2, "e".repeat(40), "draft", "A2", "CP-9000", "CP-9000", ""));

        assertThat(commitIndexService.purge(workspaceA1)).isEqualTo(1);

        assertThat(commitIndexService.search("CP-9000", workspaceA1)).isEmpty();
        assertThat(commitIndexService.search("CP-9000", centralA)).hasSize(1);
        assertThat(commitIndexService.search("CP-9000", workspaceA2)).hasSize(1);
    }

    @Test
    void blankQueriesFailClosedAndMaximumResultCountIsApplied() {
        assertThat(commitIndexService.search(null, centralA)).isEmpty();
        assertThat(commitIndexService.search("", centralA)).isEmpty();
        assertThat(commitIndexService.findByElement(" ", centralA)).isEmpty();
        assertThat(commitIndexService.findByRelation(null, centralA)).isEmpty();

        for (int index = 0; index < 5; index++) {
            indexRepository.save(createEntry(
                    centralA,
                    String.format("f%039d", index),
                    "test",
                    "Commit " + index,
                    "CP-9999 STRUCT:element",
                    "CP-9999",
                    ""));
        }
        assertThat(commitIndexService.search("CP-9999", 2, centralA))
                .hasSizeLessThanOrEqualTo(2);
        assertThat(commitIndexService.search("CP-9999", 0, centralA)).isEmpty();
    }

    @Test
    void aggregateVolatilityUsesOnlyTheSelectedTenantDenominator() {
        indexRepository.save(createEntry(
                centralA, "1".repeat(40), "draft", "A one", "CP-1000", "CP-1000", ""));
        indexRepository.save(createEntry(
                centralA, "2".repeat(40), "draft", "A other", "CP-2000", "CP-2000", ""));
        indexRepository.save(createEntry(
                centralB, "3".repeat(40), "draft", "B one", "CP-1000", "CP-1000", ""));
        indexRepository.save(createEntry(
                centralB, "4".repeat(40), "draft", "B two", "CP-1000", "CP-1000", ""));

        var aggregation = commitIndexService.aggregateElementHistory("CP-1000", centralA);

        assertThat(aggregation).isNotNull();
        assertThat(aggregation.occurrenceCount()).isEqualTo(1);
        assertThat(aggregation.recentCommitMessages()).containsExactly("A one");
    }

    private void assertOnlyMessage(RepositoryContext context, String expectedMessage) {
        assertThat(commitIndexService.search("CP-7777", context))
                .extracting(ArchitectureCommitIndex::getMessage)
                .containsExactly(expectedMessage);
    }

    private ArchitectureCommitIndex createEntry(
            RepositoryContext context,
            String commitId,
            String branch,
            String message,
            String tokenizedText,
            String elementIds,
            String relationIds) {
        ArchitectureCommitIndex entry = new ArchitectureCommitIndex();
        entry.setRepositoryId(context.repositoryId());
        entry.setWorkspaceId(context.workspaceId());
        entry.setCommitId(commitId);
        entry.setAuthor("author");
        entry.setCommitTimestamp(Instant.now());
        entry.setMessage(message);
        entry.setBranch(branch);
        entry.setChangedFiles("architecture.taxdsl");
        entry.setTokenizedChangeText(tokenizedText);
        entry.setAffectedElementIds(elementIds);
        entry.setAffectedRelationIds(relationIds);
        return entry;
    }

    private void ensureSecondaryRepository() {
        if (systemRepositoryRepository.findByRepositoryId(SECONDARY_REPOSITORY_ID)
                .isPresent()) {
            return;
        }
        SystemRepository repository = new SystemRepository();
        repository.setRepositoryId(SECONDARY_REPOSITORY_ID);
        repository.setStorageRepositoryName("commit-search-storage-b");
        repository.setSlug("commit-search-repo-b");
        repository.setDisplayName("Commit search repository B");
        repository.setVisibility(RepositoryVisibility.PRIVATE);
        repository.setOwnerType(RepositoryOwnerType.SYSTEM);
        repository.setOwnerId("system");
        repository.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        repository.setDefaultBranch("draft");
        repository.setCreatedBy("test");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        systemRepositoryRepository.saveAndFlush(repository);
    }
}
