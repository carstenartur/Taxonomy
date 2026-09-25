package com.taxonomy.versioning.service;

import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureRelation;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.dto.TransferConflict;
import com.taxonomy.dto.TransferSelection;
import com.taxonomy.dto.ContextMode;
import com.taxonomy.dto.ContextRef;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.taxonomy.workspace.service.WorkspaceResolver;

/**
 * Unit tests for {@link SelectiveTransferService}.
 *
 * <p>Uses an in-memory DslGitRepository — no Spring context required.
 */
class SelectiveTransferServiceTest {

    private DslGitRepository gitRepo;
    private DslGitRepositoryFactory factory;
    private SelectiveTransferService transferService;
    private ContextNavigationService navService;
    private WorkspaceResolver workspaceResolver;

    private final TaxDslParser parser = new TaxDslParser();
    private final AstToModelMapper mapper = new AstToModelMapper();

    private static final String DSL_SOURCE = """
            meta {
              language: "taxdsl";
              version: "2.0";
              namespace: "test";
            }

            element CP-1023 type Capability {
              title: "Secure Voice Updated";
            }

            element CP-1024 type Capability {
              title: "Network Management";
            }

            relation CP-1023 REALIZES CR-1047 {
              status: "accepted";
            }
            """;

    private static final String DSL_TARGET = """
            meta {
              language: "taxdsl";
              version: "2.0";
              namespace: "test";
            }

            element CP-1023 type Capability {
              title: "Secure Voice";
            }
            """;

    @BeforeEach
    void setUp() {
        factory = new DslGitRepositoryFactory(null);
        gitRepo = factory.getSystemRepository();
        navService = mock(ContextNavigationService.class);
        workspaceResolver = mock(WorkspaceResolver.class);
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("testuser");
        when(workspaceResolver.resolveCurrentContext()).thenReturn(WorkspaceContext.SHARED);
        when(navService.getCurrentContext("testuser")).thenReturn(editableContext("target"));
        transferService = new SelectiveTransferService(factory, navService, workspaceResolver);
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    @Test
    void detectConflictsFindsModifiedElement() {
        CanonicalArchitectureModel sourceModel = parseModel(DSL_SOURCE);
        CanonicalArchitectureModel targetModel = parseModel(DSL_TARGET);

        TransferSelection selection = new TransferSelection(
                "src", "tgt",
                Set.of("CP-1023"),
                Set.of(),
                TransferSelection.TransferMode.COPY
        );

        List<TransferConflict> conflicts = transferService.detectConflicts(
                sourceModel, targetModel, selection);

        assertEquals(1, conflicts.size());
        assertEquals("CP-1023", conflicts.get(0).elementOrRelationId());
        assertEquals("Secure Voice", conflicts.get(0).originValue());
        assertEquals("Secure Voice Updated", conflicts.get(0).incomingValue());
    }

    @Test
    void detectConflictsNoConflictForNewElement() {
        CanonicalArchitectureModel sourceModel = parseModel(DSL_SOURCE);
        CanonicalArchitectureModel targetModel = parseModel(DSL_TARGET);

        TransferSelection selection = new TransferSelection(
                "src", "tgt",
                Set.of("CP-1024"),
                Set.of(),
                TransferSelection.TransferMode.COPY
        );

        List<TransferConflict> conflicts = transferService.detectConflicts(
                sourceModel, targetModel, selection);

        assertTrue(conflicts.isEmpty(), "New element should not cause conflicts");
    }

    @Test
    void detectConflictsEmptySelectionNoConflicts() {
        CanonicalArchitectureModel sourceModel = parseModel(DSL_SOURCE);
        CanonicalArchitectureModel targetModel = parseModel(DSL_TARGET);

        TransferSelection selection = new TransferSelection(
                "src", "tgt",
                Set.of(),
                Set.of(),
                TransferSelection.TransferMode.COPY
        );

        List<TransferConflict> conflicts = transferService.detectConflicts(
                sourceModel, targetModel, selection);

        assertTrue(conflicts.isEmpty());
    }

    @Test
    void detectConflictsRelationConflict() {
        // Build a source with a relation
        CanonicalArchitectureModel sourceModel = new CanonicalArchitectureModel();
        ArchitectureRelation srcRel = new ArchitectureRelation("CP-1023", "REALIZES", "CR-1047");
        srcRel.setStatus("accepted");
        sourceModel.getRelations().add(srcRel);

        // Build a target with same relation but different status
        CanonicalArchitectureModel targetModel = new CanonicalArchitectureModel();
        ArchitectureRelation tgtRel = new ArchitectureRelation("CP-1023", "REALIZES", "CR-1047");
        tgtRel.setStatus("proposed");
        targetModel.getRelations().add(tgtRel);

        TransferSelection selection = new TransferSelection(
                "src", "tgt",
                Set.of(),
                Set.of("CP-1023 REALIZES CR-1047"),
                TransferSelection.TransferMode.COPY
        );

        List<TransferConflict> conflicts = transferService.detectConflicts(
                sourceModel, targetModel, selection);

        assertEquals(1, conflicts.size());
        assertEquals("CP-1023 REALIZES CR-1047", conflicts.get(0).elementOrRelationId());
    }

    @Test
    void previewLoadsImmutableCommitsAndFindsSelectedConflict() throws IOException {
        String sourceCommit = gitRepo.commitDsl("source", DSL_SOURCE, "tester", "source");
        String targetCommit = gitRepo.commitDsl("target", DSL_TARGET, "tester", "target");
        TransferSelection selection = new TransferSelection(
                sourceCommit, targetCommit, Set.of("CP-1023"), Set.of(),
                TransferSelection.TransferMode.COPY);

        List<TransferConflict> conflicts = transferService.previewTransfer(
                selection, WorkspaceContext.SHARED);

        assertEquals(1, conflicts.size());
        assertEquals("Secure Voice Updated", conflicts.getFirst().incomingValue());
        assertEquals(DSL_SOURCE, gitRepo.getDslAtCommit(sourceCommit));
        assertEquals(DSL_TARGET, gitRepo.getDslAtCommit(targetCommit));
    }

    @Test
    void previewRejectsMissingImmutableSourceWithoutMutation() throws IOException {
        DslGitRepositoryFactory boundary = mock(DslGitRepositoryFactory.class);
        DslGitRepository repository = mock(DslGitRepository.class);
        when(boundary.resolveRepository(WorkspaceContext.SHARED)).thenReturn(repository);
        when(repository.getDslAtCommit("missing-commit")).thenReturn(null);
        SelectiveTransferService service = new SelectiveTransferService(
                boundary, navService, workspaceResolver);
        TransferSelection selection = new TransferSelection(
                "missing-commit", "target-commit",
                Set.of("CP-1023"), Set.of(), TransferSelection.TransferMode.COPY);

        IOException failure = assertThrows(IOException.class,
                () -> service.previewTransfer(selection));

        assertTrue(failure.getMessage().contains("No DSL content at commit"));
        verify(repository, never()).getDslAtCommit("target-commit");
    }

    @Test
    void applyCopiesOnlySelectedElementsAndRelationsToCurrentBranch() throws IOException {
        String sourceCommit = gitRepo.commitDsl("source", DSL_SOURCE, "tester", "source");
        String targetCommit = gitRepo.commitDsl("target", DSL_TARGET, "tester", "target");
        TransferSelection selection = new TransferSelection(
                sourceCommit,
                targetCommit,
                Set.of("CP-1024"),
                Set.of("CP-1023 REALIZES CR-1047"),
                TransferSelection.TransferMode.COPY);

        String commit = transferService.applyTransfer(selection);

        assertEquals(commit, gitRepo.getHeadCommit("target"));
        String committedDsl = gitRepo.getDslAtHead("target");
        assertTrue(committedDsl.contains("CP-1024"));
        assertTrue(committedDsl.contains("CP-1023 REALIZES CR-1047"));
        assertTrue(committedDsl.contains("Secure Voice"));
        ArchitectureElement unselectedElement = parseModel(committedDsl)
                .findElement("CP-1023")
                .orElseThrow();
        assertEquals("Secure Voice", unselectedElement.getTitle());
    }

    @Test
    void applyFallsBackToSharedContextWhenRequestResolutionFails() throws IOException {
        String sourceCommit = gitRepo.commitDsl("source", DSL_SOURCE, "tester", "source");
        String targetCommit = gitRepo.commitDsl("target", DSL_TARGET, "tester", "target");
        when(workspaceResolver.resolveCurrentContext())
                .thenThrow(new IllegalStateException("no request context"));
        TransferSelection selection = new TransferSelection(
                sourceCommit, targetCommit, Set.of(), Set.of(),
                TransferSelection.TransferMode.COPY);

        String commit = transferService.applyTransfer(selection);

        assertEquals(commit, gitRepo.getHeadCommit("target"));
        assertFalse(gitRepo.getDslAtHead("target").contains("CP-1024"));
    }

    @Test
    void conflictDetailsIncludeOnlyViewsThatReferenceTheElement() {
        String targetWithViews = DSL_TARGET + """

                view INCLUDED {
                  title: "Relevant View";
                  include: "CP-1023";
                }

                view OTHER {
                  title: "Other View";
                  include: "CP-9999";
                }
                """;
        TransferSelection selection = new TransferSelection(
                "src", "tgt", Set.of("CP-1023"), Set.of(),
                TransferSelection.TransferMode.COPY);

        List<TransferConflict> conflicts = transferService.detectConflicts(
                parseModel(DSL_SOURCE), parseModel(targetWithViews), selection);

        assertEquals(List.of("Relevant View"), conflicts.getFirst().affectedViews());
    }

    private CanonicalArchitectureModel parseModel(String dsl) {
        var doc = parser.parse(dsl);
        return mapper.map(doc);
    }

    private static ContextRef editableContext(String branch) {
        return new ContextRef(
                "ctx", branch, null, Instant.now(), ContextMode.EDITABLE,
                null, null, null, null, null, false);
    }
}
