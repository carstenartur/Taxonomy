package com.taxonomy.composition.dsl.service;

import com.taxonomy.architecture.model.ArchitectureDslDocument;
import com.taxonomy.architecture.repository.ArchitectureDslDocumentRepository;
import com.taxonomy.dsl.diff.ModelDiff;
import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.versioning.service.*;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DslDocumentOperationsFacadeTest {
    private final TaxDslExportService export = mock(TaxDslExportService.class);
    private final DslMaterializeService materialize = mock(DslMaterializeService.class);
    private final ArchitectureDslDocumentRepository archive = mock(ArchitectureDslDocumentRepository.class);
    private final DslOperationsFacade git = mock(DslOperationsFacade.class);
    private final DslDocumentOperationsFacade facade = new DslDocumentOperationsFacade(export, materialize, archive, git);
    private final ModelDiff diff = new ModelDiff(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    private static final String BEFORE = "a".repeat(40), AFTER = "b".repeat(40);

    @Test
    void lowercaseFullShasUseWorkspaceGitOnly() throws Exception {
        when(git.diffBetween(BEFORE, AFTER)).thenReturn(diff);
        assertThat(facade.diffBetween(BEFORE, AFTER)).isSameAs(diff);
        verifyNoInteractions(materialize, archive, export);
    }

    @Test
    void numericIdsUseArchiveWithoutResolvingGit() throws Exception {
        when(materialize.diffDocuments(1L, 2L)).thenReturn(diff);
        assertThat(facade.diffBetween("1", "2")).isSameAs(diff);
        verifyNoInteractions(git, archive, export);
    }

    @Test
    void mixedMalformedUppercaseShortNullAndOverflowIdsKeepNumericParsingFailure() {
        for (String[] ids : List.of(new String[]{BEFORE,"2"}, new String[]{"1",AFTER},
                new String[]{"A".repeat(40),AFTER}, new String[]{"abc","def"},
                new String[]{"no-id","2"}, new String[]{"9223372036854775808","2"},
                new String[]{"1","9223372036854775808"}, new String[]{"g".repeat(40),AFTER})) {
            assertThatThrownBy(() -> facade.diffBetween(ids[0], ids[1])).isInstanceOf(NumberFormatException.class);
        }
        for (String[] ids : Arrays.asList(new String[]{null,AFTER}, new String[]{BEFORE,null}, new String[]{null,null})) {
            assertThatThrownBy(() -> facade.diffBetween(ids[0],ids[1])).isInstanceOf(NumberFormatException.class);
        }
        verifyNoInteractions(git, materialize, archive, export);
    }

    @Test
    void bothBackendFailuresPropagateUnchanged() throws Exception {
        IOException gitFailure = new IOException("git unavailable");
        IllegalArgumentException archiveFailure = new IllegalArgumentException("missing document");
        when(git.diffBetween(BEFORE, AFTER)).thenThrow(gitFailure);
        when(materialize.diffDocuments(1L, 2L)).thenThrow(archiveFailure);
        assertThatThrownBy(() -> facade.diffBetween(BEFORE, AFTER)).isSameAs(gitFailure);
        assertThatThrownBy(() -> facade.diffBetween("1", "2")).isSameAs(archiveFailure);
    }

    @Test
    void realWorkspaceFacadeSelectsRequestedGitRepositoryAndFailsClosed() throws Exception {
        var resolver = mock(WorkspaceResolver.class);
        var state = mock(RepositoryStateService.class);
        var repositories = mock(DslGitRepositoryFactory.class);
        var selected = RepositoryContext.workspace("selected-repository","workspace","review","alice");
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(selected);
        try (var repository = new DslGitRepository()) {
            String before = repository.commitDsl("review", "", "alice", "before");
            String after = repository.commitDsl("review", "element BP type Capability {\n  title: \"Business Processes\";\n}\n", "alice", "after");
            when(repositories.resolveRepository(selected)).thenReturn(repository);
            var workspace = new DslOperationsFacade(repositories,mock(CommitIndexService.class),
                    mock(ConflictDetectionService.class),mock(RepositoryStateGuard.class),state,resolver,
                    mock(WorkspaceArchitectureVersionPort.class));
            var documents = new DslDocumentOperationsFacade(export,materialize,archive,workspace);
            assertThat(documents.diffBetween(before,after).addedElements()).extracting("id").containsExactly("BP");
            verify(repositories).resolveRepository(selected);
            verifyNoInteractions(materialize,archive);
            clearInvocations(repositories);
            doThrow(new IllegalStateException("workspace unavailable")).when(state).ensureWorkspaceState("alice");
            assertThatThrownBy(() -> documents.diffBetween(before,after)).hasMessage("workspace unavailable");
            verifyNoInteractions(repositories);
            reset(state);
            when(resolver.resolveCurrentRepositoryContext()).thenReturn(null);
            assertThatThrownBy(() -> documents.diffBetween(before,after)).hasMessage("Repository context resolver returned null");
            verifyNoInteractions(repositories);
        }
    }

    @Test
    void documentOperationsPreserveArgumentsAndResults() {
        var model = new CanonicalArchitectureModel();
        var document = new ArchitectureDslDocument(); document.setId(42L);
        var result = new DslMaterializeService.MaterializeResult(true,List.of(),List.of(),1,2,42L);
        when(export.exportAll("namespace")).thenReturn("dsl");
        when(export.buildCanonicalModel()).thenReturn(model);
        when(materialize.materialize("dsl","path","branch","commit")).thenReturn(result);
        when(materialize.materializeIncremental(null,42L)).thenReturn(result);
        when(archive.findById(42L)).thenReturn(Optional.of(document));
        when(archive.findByCommitId("commit")).thenReturn(Optional.of(document));
        when(archive.findAll()).thenReturn(List.of(document));
        assertThat(facade.exportAll("namespace")).isEqualTo("dsl");
        assertThat(facade.buildCanonicalModel()).isSameAs(model);
        assertThat(facade.materialize("dsl","path","branch","commit")).isSameAs(result);
        assertThat(facade.materializeIncremental(null,42L)).isSameAs(result);
        assertThat(facade.findDocumentById(42L)).contains(document);
        assertThat(facade.findDocumentById(99L)).isEmpty();
        assertThat(facade.findDocumentIdByCommitId("commit")).contains(42L);
        assertThat(facade.findDocumentIdByCommitId("absent")).isEmpty();
        assertThat(facade.listDocuments()).containsExactly(document);
        verifyNoInteractions(git);
    }
}
