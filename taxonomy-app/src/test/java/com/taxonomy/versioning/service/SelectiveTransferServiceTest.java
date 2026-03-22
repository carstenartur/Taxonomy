package com.taxonomy.versioning.service;

import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureRelation;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dto.TransferConflict;
import com.taxonomy.dto.TransferSelection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.service.WorkspaceResolver;

/**
 * Unit tests for {@link SelectiveTransferService}.
 *
 * <p>Uses an in-memory DslGitRepository — no Spring context required.
 */
class SelectiveTransferServiceTest {

    private DslGitRepository gitRepo;
    private SelectiveTransferService transferService;
    private ContextNavigationService navService;

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

            relation CP-1023 realizes CR-1047 {
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
        var factory = new DslGitRepositoryFactory(null);
        gitRepo = factory.getSystemRepository();
        var wsRepo = mock(com.taxonomy.workspace.repository.UserWorkspaceRepository.class);
        var workspaceManager = new WorkspaceManager(wsRepo, 50,
                mock(com.taxonomy.workspace.service.SystemRepositoryService.class), gitRepo);
        var stateService = new RepositoryStateService(factory, workspaceManager,
                mock(com.taxonomy.workspace.service.SystemRepositoryService.class));
        navService = new ContextNavigationService(factory, stateService, workspaceManager, 50);
        var workspaceResolver = mock(WorkspaceResolver.class);
        org.mockito.Mockito.when(workspaceResolver.resolveCurrentUsername()).thenReturn("testuser");
        transferService = new SelectiveTransferService(factory, navService, workspaceResolver);
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

    private CanonicalArchitectureModel parseModel(String dsl) {
        var doc = parser.parse(dsl);
        return mapper.map(doc);
    }
}
