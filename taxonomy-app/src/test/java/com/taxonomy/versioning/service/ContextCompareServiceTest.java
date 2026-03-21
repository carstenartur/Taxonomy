package com.taxonomy.versioning.service;

import com.taxonomy.dsl.diff.ModelDiff;
import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureRelation;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dto.ContextComparison;
import com.taxonomy.dto.ContextComparison.DiffSummary;
import com.taxonomy.dto.ContextMode;
import com.taxonomy.dto.ContextRef;
import com.taxonomy.dto.SemanticChange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ContextCompareService}.
 *
 * <p>Uses an in-memory DslGitRepository — no Spring context required.
 */
class ContextCompareServiceTest {

    private DslGitRepository gitRepo;
    private ContextCompareService compareService;

    private static final String DSL_V1 = """
            meta {
              language: "taxdsl";
              version: "2.0";
              namespace: "test";
            }

            element CP-1023 type Capability {
              title: "Secure Voice";
            }
            """;

    private static final String DSL_V2 = """
            meta {
              language: "taxdsl";
              version: "2.0";
              namespace: "test";
            }

            element CP-1023 type Capability {
              title: "Secure Voice Communications";
            }

            element CP-1024 type Capability {
              title: "Network Management";
            }
            """;

    @BeforeEach
    void setUp() {
        var factory = new DslGitRepositoryFactory(null);
        gitRepo = factory.getSystemRepository();
        compareService = new ContextCompareService(factory);
    }

    @Test
    void compareIdenticalCommitsReturnsEmpty() throws IOException {
        String commit = gitRepo.commitDsl("draft", DSL_V1, "alice", "initial");

        ContextRef left = buildRef("draft", commit);
        ContextRef right = buildRef("draft", commit);

        ContextComparison result = compareService.compareContexts(left, right);

        assertNotNull(result);
        assertEquals(0, result.summary().totalChanges());
        assertTrue(result.changes().isEmpty());
    }

    @Test
    void compareDifferentCommitsShowsChanges() throws IOException {
        String commit1 = gitRepo.commitDsl("draft", DSL_V1, "alice", "v1");
        String commit2 = gitRepo.commitDsl("draft", DSL_V2, "bob", "v2");

        ContextRef left = buildRef("draft", commit1);
        ContextRef right = buildRef("draft", commit2);

        ContextComparison result = compareService.compareContexts(left, right);

        assertNotNull(result);
        assertTrue(result.summary().totalChanges() > 0);
        assertFalse(result.changes().isEmpty());
    }

    @Test
    void compareBranchesReturnsComparison() throws IOException {
        gitRepo.commitDsl("draft", DSL_V1, "alice", "v1");
        gitRepo.createBranch("feature", "draft");
        gitRepo.commitDsl("feature", DSL_V2, "bob", "v2");

        ContextRef left = buildRef("draft", null);
        ContextRef right = buildRef("feature", null);

        ContextComparison result = compareService.compareBranches(left, right);

        assertNotNull(result);
    }

    @Test
    void compareShowsAddedElement() throws IOException {
        String commit1 = gitRepo.commitDsl("draft", DSL_V1, "alice", "v1");
        String commit2 = gitRepo.commitDsl("draft", DSL_V2, "bob", "v2");

        ContextRef left = buildRef("draft", commit1);
        ContextRef right = buildRef("draft", commit2);

        ContextComparison result = compareService.compareContexts(left, right);

        boolean hasAddedElement = result.changes().stream()
                .anyMatch(c -> "ADD".equals(c.changeType()) && "ELEMENT".equals(c.category()));
        assertTrue(hasAddedElement, "Should show CP-1024 as added");
    }

    @Test
    void buildDiffSummaryCountsCorrectly() {
        ModelDiff diff = new ModelDiff(
                List.of(new ArchitectureElement("e1", "Capability", "Title", null, null)),
                List.of(new ArchitectureElement("e2", "Capability", "Removed", null, null)),
                List.of(),
                List.of(new ArchitectureRelation("e1", "REALIZES", "e2")),
                List.of(),
                List.of()
        );

        DiffSummary summary = compareService.buildDiffSummary(diff);

        assertEquals(1, summary.elementsAdded());
        assertEquals(1, summary.elementsRemoved());
        assertEquals(0, summary.elementsChanged());
        assertEquals(1, summary.relationsAdded());
        assertEquals(3, summary.totalChanges());
    }

    @Test
    void buildSemanticChangesGeneratesDescriptions() {
        ModelDiff diff = new ModelDiff(
                List.of(new ArchitectureElement("CP-1024", "Capability", "Network Mgmt", null, null)),
                List.of(),
                List.of(),
                List.of(new ArchitectureRelation("CP-1023", "REALIZES", "CR-1047")),
                List.of(),
                List.of()
        );

        List<SemanticChange> changes = compareService.buildSemanticChanges(diff);

        assertEquals(2, changes.size());

        SemanticChange elementAdd = changes.stream()
                .filter(c -> "ELEMENT".equals(c.category()))
                .findFirst().orElseThrow();
        assertEquals("ADD", elementAdd.changeType());
        assertEquals("CP-1024", elementAdd.id());
        assertTrue(elementAdd.description().contains("CP-1024"));

        SemanticChange relationAdd = changes.stream()
                .filter(c -> "RELATION".equals(c.category()))
                .findFirst().orElseThrow();
        assertEquals("ADD", relationAdd.changeType());
        assertTrue(relationAdd.id().contains("CP-1023"));
    }

    @Test
    void compareWithNullCommitsReturnsEmptySummary() throws IOException {
        ContextRef left = buildRef("nonexistent", null);
        ContextRef right = buildRef("alsoMissing", null);

        ContextComparison result = compareService.compareContexts(left, right);

        assertNotNull(result);
        assertEquals(0, result.summary().totalChanges());
    }

    private ContextRef buildRef(String branch, String commitId) {
        return new ContextRef(null, branch, commitId, Instant.now(),
                ContextMode.READ_ONLY, null, null, null, null, null, false);
    }
}
