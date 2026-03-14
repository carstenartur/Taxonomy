package com.taxonomy.dsl;

import com.taxonomy.model.ArchitectureCommitIndex;
import com.taxonomy.repository.ArchitectureCommitIndexRepository;
import com.taxonomy.service.CommitIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * Integration tests for Hibernate Search–backed commit history search.
 *
 * <p>Verifies that the {@link CommitIndexService} correctly searches
 * {@link ArchitectureCommitIndex} entities using the custom
 * {@code "dsl"} and {@code "csv-keyword"} Lucene analyzers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class CommitIndexHibernateSearchTest {

    @Autowired
    private CommitIndexService commitIndexService;

    @Autowired
    private ArchitectureCommitIndexRepository indexRepository;

    @BeforeEach
    void setUp() {
        indexRepository.deleteAll();
    }

    @Test
    void searchByTokenizedDslText() {
        // Insert a commit with DSL tokens
        ArchitectureCommitIndex entry = createEntry(
                "abc123", "author1", "Initial architecture commit",
                "CP-1023 STRUCT:element REL:REALIZES DOM:Capability CR-1047",
                "CP-1023,CR-1047", "CP-1023 REALIZES CR-1047");
        indexRepository.save(entry);

        // Search for a DSL element ID in tokenized text
        List<ArchitectureCommitIndex> results = commitIndexService.search("cp-1023");
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getCommitId()).isEqualTo("abc123");
    }

    @Test
    void searchByCommitMessage() {
        ArchitectureCommitIndex entry = createEntry(
                "def456", "author2", "Added secure voice architecture",
                "CP-5001 STRUCT:element DOM:Capability",
                "CP-5001", "");
        indexRepository.save(entry);

        List<ArchitectureCommitIndex> results = commitIndexService.search("secure voice");
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getCommitId()).isEqualTo("def456");
    }

    @Test
    void findByElementIdUsesHibernateSearch() {
        ArchitectureCommitIndex entry = createEntry(
                "ghi789", "author3", "Element addition",
                "BP-1327 STRUCT:element DOM:Process",
                "BP-1327,CP-1023", "");
        indexRepository.save(entry);

        List<ArchitectureCommitIndex> results = commitIndexService.findByElement("BP-1327");
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getCommitId()).isEqualTo("ghi789");
    }

    @Test
    void findByRelationKeyUsesHibernateSearch() {
        ArchitectureCommitIndex entry = createEntry(
                "jkl012", "author4", "Relation creation",
                "CP-1023 CR-1047 REL:REALIZES STRUCT:relation",
                "CP-1023,CR-1047", "CP-1023 REALIZES CR-1047");
        indexRepository.save(entry);

        List<ArchitectureCommitIndex> results = commitIndexService.findByRelation("CP-1023 REALIZES CR-1047");
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getCommitId()).isEqualTo("jkl012");
    }

    @Test
    void searchReturnsEmptyForNoMatch() {
        ArchitectureCommitIndex entry = createEntry(
                "mno345", "author5", "Some commit",
                "CP-9999 STRUCT:element DOM:Capability",
                "CP-9999", "");
        indexRepository.save(entry);

        List<ArchitectureCommitIndex> results = commitIndexService.search("NONEXISTENT-ID-999");
        assertThat(results).isEmpty();
    }

    @Test
    void searchWithNullOrBlankReturnsEmpty() {
        assertThat(commitIndexService.search(null)).isEmpty();
        assertThat(commitIndexService.search("")).isEmpty();
        assertThat(commitIndexService.search("   ")).isEmpty();
    }

    @Test
    void findByElementWithNullOrBlankReturnsEmpty() {
        assertThat(commitIndexService.findByElement(null)).isEmpty();
        assertThat(commitIndexService.findByElement("")).isEmpty();
    }

    @Test
    void findByRelationWithNullOrBlankReturnsEmpty() {
        assertThat(commitIndexService.findByRelation(null)).isEmpty();
        assertThat(commitIndexService.findByRelation("")).isEmpty();
    }

    @Test
    void searchWithMaxResultsLimitsOutput() {
        // Create multiple entries with valid 40-char hex commit IDs
        for (int i = 0; i < 5; i++) {
            ArchitectureCommitIndex entry = createEntry(
                    String.format("a%039d", i),
                    "author", "commit " + i,
                    "CP-7777 STRUCT:element DOM:Capability",
                    "CP-7777", "");
            indexRepository.save(entry);
        }

        List<ArchitectureCommitIndex> results = commitIndexService.search("CP-7777", 2);
        assertThat(results).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void searchByRelationTypeToken() {
        ArchitectureCommitIndex entry = createEntry(
                "pqr678pqr678pqr678pqr678pqr678pqr67890",
                "author6", "Added REALIZES relations",
                "CP-1023 CR-1047 STRUCT:relation REL:REALIZES DOM:Capability",
                "CP-1023,CR-1047", "CP-1023 REALIZES CR-1047");
        indexRepository.save(entry);

        // Search for the prefixed relation type token
        List<ArchitectureCommitIndex> results = commitIndexService.search("rel:realizes");
        assertThat(results).isNotEmpty();
    }

    @Test
    void searchByStructureToken() {
        ArchitectureCommitIndex entry = createEntry(
                "stu901stu901stu901stu901stu901stu90123",
                "author7", "Added elements",
                "STRUCT:element CP-8888 DOM:Process",
                "CP-8888", "");
        indexRepository.save(entry);

        List<ArchitectureCommitIndex> results = commitIndexService.search("struct:element");
        assertThat(results).isNotEmpty();
    }

    // ── Helper ──────────────────────────────────────────────────────

    private ArchitectureCommitIndex createEntry(String commitId, String author,
                                                 String message, String tokenizedText,
                                                 String elementIds, String relationIds) {
        ArchitectureCommitIndex entry = new ArchitectureCommitIndex();
        entry.setCommitId(commitId);
        entry.setAuthor(author);
        entry.setCommitTimestamp(Instant.now());
        entry.setMessage(message);
        entry.setBranch("test");
        entry.setChangedFiles("architecture.taxdsl");
        entry.setTokenizedChangeText(tokenizedText);
        entry.setAffectedElementIds(elementIds);
        entry.setAffectedRelationIds(relationIds);
        return entry;
    }
}
