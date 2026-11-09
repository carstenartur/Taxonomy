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
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.UserWorkspaceState;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.signing.SigningTestKeys;
import com.taxonomy.workspace.signing.SignatureStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.taxonomy.workspace.service.WorkspaceResolver;

/**
 * Unit tests for {@link SelectiveTransferService}.
 *
 * <p>Uses an in-memory DslGitRepository — no Spring context required.
 */
class SelectiveTransferServiceTest {
    @TempDir Path signingKeys;

    private static final WorkspaceContext PERSONAL = new WorkspaceContext(
            "testuser", "personal-workspace", "target", "central-source");
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
        when(workspaceResolver.resolveCurrentContext()).thenReturn(PERSONAL);
        var state = new UserWorkspaceState(PERSONAL.username(), 10);
        state.setCurrentContext(editableContext("target"));
        when(navService.getEditableTransferTarget(PERSONAL, null)).thenReturn(
                new ContextNavigationService.TransferTarget(state, state.getNavigationSelection(), "target"));
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
    void applyCopiesOnlySelectedElementsAndRelationsToCurrentBranch() throws Exception {
        var workspace = factory.openWorkspaceRepository(PERSONAL.workspaceId());
        workspace.withSignatureService(SigningTestKeys.createService(signingKeys), "workspace:personal-workspace");
        String sourceCommit = workspace.commitDsl("source", DSL_SOURCE, "tester", "source");
        String targetCommit = workspace.commitDsl("target", DSL_TARGET, "tester", "target");
        TransferSelection selection = new TransferSelection(
                sourceCommit,
                targetCommit,
                Set.of("CP-1024"),
                Set.of("CP-1023 REALIZES CR-1047"),
                TransferSelection.TransferMode.COPY);

        String commit = transferService.applyTransfer(selection, null);

        assertEquals(commit, workspace.getHeadCommit("target"));
        assertEquals(SignatureStatus.VERIFIED, workspace.verifyCommit(commit).status());
        assertEquals(List.of(sourceCommit, targetCommit), workspace.getProvenance(commit).sources().stream()
                .map(com.taxonomy.workspace.storage.CommitProvenance.Source::commitId).toList());
        String committedDsl = workspace.getDslAtHead("target");
        assertTrue(committedDsl.contains("CP-1024"));
        assertTrue(committedDsl.contains("CP-1023 REALIZES CR-1047"));
        assertTrue(committedDsl.contains("Secure Voice"));
        ArchitectureElement unselectedElement = parseModel(committedDsl)
                .findElement("CP-1023")
                .orElseThrow();
        assertEquals("Secure Voice", unselectedElement.getTitle());
    }

    @Test
    void personalTransferRecordsWorkspaceIdentityForBothSourceProofs() throws Exception {
        String expectedRepositoryId = "workspace:personal-workspace";
        DslGitRepository repository = factory.openWorkspaceRepository(PERSONAL.workspaceId());
        repository.withSignatureService(SigningTestKeys.createService(signingKeys), expectedRepositoryId);
        String source = repository.commitDsl("source", DSL_SOURCE, "tester", "source");
        String target = repository.commitDsl("target", DSL_TARGET, "tester", "target");
        TransferSelection selection = new TransferSelection(
                source, target, Set.of("CP-1024"), Set.of(), TransferSelection.TransferMode.COPY);

        String result = transferService.applyTransfer(selection, null);

        assertEquals(result, repository.getHeadCommit("target"));
        assertEquals(SignatureStatus.VERIFIED, repository.verifyCommit(result).status());
        var evidence = repository.getProvenance(result);
        assertEquals(List.of(source, target), evidence.sources().stream()
                .map(com.taxonomy.workspace.storage.CommitProvenance.Source::commitId).toList());
        assertEquals(List.of(expectedRepositoryId, expectedRepositoryId), evidence.sources().stream()
                .map(com.taxonomy.workspace.storage.CommitProvenance.Source::repositoryId).toList());
        assertEquals("target", evidence.sources().getLast().branch());
        assertTrue(repository.getDslAtHead("target").contains("CP-1024"));
        assertNull(factory.getCentralRepository("central-source").getHeadCommit("target"));
    }

    @Test
    void centralReadScopeCannotWriteEvenWithMatchingGitCommits() throws IOException {
        var central = new WorkspaceContext("testuser", null, "target", "selected-central");
        when(workspaceResolver.resolveCurrentContext()).thenReturn(central);
        var repository = factory.getCentralRepository("selected-central");
        String source = repository.commitDsl("source", DSL_SOURCE, "tester", "source");
        String target = repository.commitDsl("target", DSL_TARGET, "tester", "target");
        var selection = new TransferSelection(source, target, Set.of("CP-1024"), Set.of(),
                TransferSelection.TransferMode.COPY);

        assertThrows(AccessDeniedException.class, () -> transferService.applyTransfer(selection, null));
        assertEquals(target, repository.getHeadCommit("target"));
        verify(navService, never()).getEditableTransferTarget(central, null);
    }

    @Test
    void targetMovementDuringSnapshotReadStillFailsTheCommitPrecondition() throws IOException {
        var workspace = factory.openWorkspaceRepository(PERSONAL.workspaceId());
        String source = workspace.commitDsl("source", DSL_SOURCE, "tester", "source");
        String target = workspace.commitDsl("target", DSL_TARGET, "tester", "target");
        String changed = DSL_TARGET.replace("Secure Voice", "Concurrent edit");
        var movedHead = new AtomicReference<String>();
        var reader = spy(workspace);
        doAnswer(call -> {
            if (movedHead.get() == null) {
                movedHead.set(workspace.commitDsl("target", changed, "tester", "Concurrent update"));
            }
            return call.callRealMethod();
        }).when(reader).getDslAtCommit(source);
        var boundary = mock(DslGitRepositoryFactory.class);
        when(boundary.resolveRepository(PERSONAL)).thenReturn(reader);
        var service = new SelectiveTransferService(boundary, navService, workspaceResolver);
        var selection = new TransferSelection(source, target, Set.of("CP-1024"), Set.of(),
                TransferSelection.TransferMode.COPY);

        var conflict = assertThrows(BranchHeadConflictException.class, () -> service.applyTransfer(selection, null));
        assertNotNull(movedHead.get());
        assertEquals(movedHead.get(), conflict.getActualHeadCommit());
        assertEquals(movedHead.get(), workspace.getHeadCommit("target"));
        assertEquals(changed, workspace.getDslAtHead("target"));
    }

    @Test
    void navigationChangeDuringSnapshotReadPreventsPublication() throws IOException {
        var workspace = factory.openWorkspaceRepository(PERSONAL.workspaceId());
        String source = workspace.commitDsl("source", DSL_SOURCE, "tester", "source");
        String target = workspace.commitDsl("target", DSL_TARGET, "tester", "target");
        workspace.createBranchAtCommit("other", target);
        var manager = new WorkspaceManager(mock(UserWorkspaceRepository.class), 50,
                mock(SystemRepositoryService.class), workspace);
        var navigation = new ContextNavigationService(factory, mock(RepositoryStateService.class), manager, 50);
        var original = navigation.switchContext(PERSONAL.username(), "target", target, PERSONAL);
        var reader = spy(workspace);
        doAnswer(call -> {
            navigation.switchContext(PERSONAL.username(), "other", target, PERSONAL);
            return call.callRealMethod();
        }).when(reader).getDslAtCommit(source);
        var boundary = mock(DslGitRepositoryFactory.class);
        when(boundary.resolveRepository(PERSONAL)).thenReturn(reader);
        var service = new SelectiveTransferService(boundary, navigation, workspaceResolver);
        var selection = new TransferSelection(source, target, Set.of("CP-1024"), Set.of(),
                TransferSelection.TransferMode.COPY);

        var conflict = assertThrows(ResponseStatusException.class,
                () -> service.applyTransfer(selection, original.contextId()));

        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("other", navigation.getCurrentContextForScope(PERSONAL).branch());
        assertEquals(target, workspace.getHeadCommit("target"));
        assertEquals(target, workspace.getHeadCommit("other"));
    }

    @Test
    void cannotLaunderARejectedIncomingObjectThroughSelectiveTransfer() throws Exception {
        var workspace = factory.openWorkspaceRepository(PERSONAL.workspaceId());
        workspace.withSignatureService(SigningTestKeys.createService(signingKeys), "workspace:personal-workspace");
        String source = workspace.commitDsl("source", DSL_SOURCE, "tester", "source");
        String target = workspace.commitDsl("target", DSL_TARGET, "tester", "target");
        byte[] raw = workspace.getGitRepository().open(ObjectId.fromString(source), Constants.OBJ_COMMIT).getBytes();
        String rejected;
        try (var inserter = workspace.getGitRepository().newObjectInserter()) {
            byte[] tampered = new String(raw, StandardCharsets.UTF_8).replace("\n\nsource", "\n\ntampered")
                    .getBytes(StandardCharsets.UTF_8);
            rejected = inserter.insert(Constants.OBJ_COMMIT, tampered).name();
            inserter.flush();
        }
        var selection = new TransferSelection(rejected, target, Set.of("CP-1024"), Set.of(), TransferSelection.TransferMode.COPY);
        assertThrows(IOException.class, () -> transferService.applyTransfer(selection, null));
        assertEquals(target, workspace.getHeadCommit("target"));
        assertEquals(1, workspace.getCommitCount("target"));
    }

    @Test
    void failedWorkspaceResolutionCannotWriteToShared() throws IOException {
        String sourceCommit = gitRepo.commitDsl("source", DSL_SOURCE, "tester", "source");
        String targetCommit = gitRepo.commitDsl("target", DSL_TARGET, "tester", "target");
        when(workspaceResolver.resolveCurrentContext())
                .thenThrow(new IllegalStateException("no request context"));
        TransferSelection selection = new TransferSelection(
                sourceCommit, targetCommit, Set.of(), Set.of(),
                TransferSelection.TransferMode.COPY);

        assertThrows(IllegalStateException.class, () -> transferService.applyTransfer(selection, null));
        assertEquals(targetCommit, gitRepo.getHeadCommit("target"));
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
