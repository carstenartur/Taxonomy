package com.taxonomy.versioning.service;

import com.taxonomy.architecture.repository.ArchitectureDslDocumentRepository;
import com.taxonomy.versioning.service.CommitIndexService;
import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryStateGuard;
import com.taxonomy.workspace.service.WorkspaceArchitectureVersionPort;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DslOperationsFacadeRationaleTest {
    @Test
    void summarizesJournalRationaleWithoutChangingTheGitCommitMessage() throws Exception {
        var context = RepositoryContext.workspace("source", "workspace", "draft", "alice");
        var resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(context);
        var repositories = mock(DslGitRepositoryFactory.class);
        var git = mock(DslGitRepository.class);
        when(repositories.resolveRepository(context)).thenReturn(git);
        var rationales = new ArrayList<String>();
        var versions = new WorkspaceArchitectureVersionPort() {
            @Override
            public <T> T version(RepositoryContext selected, String rationale, GitAction<T> action) throws IOException {
                assertThat(selected).isEqualTo(context);
                rationales.add(rationale);
                return action.run();
            }
        };
        var facade = new DslOperationsFacade(mock(TaxDslExportService.class), mock(DslMaterializeService.class),
                mock(ArchitectureDslDocumentRepository.class), repositories, mock(CommitIndexService.class),
                mock(ConflictDetectionService.class), mock(RepositoryStateGuard.class),
                mock(RepositoryStateService.class), resolver, versions);

        for (String message : Arrays.asList(null, "\n\t\u0085", "Summary\n\nLong details\t" + "x".repeat(1400),
                "x".repeat(999) + "\uD83D\uDE80 details")) {
            facade.commitDsl("draft", "canonical DSL", "alice", message);
            verify(git).commitDsl("draft", "canonical DSL", "alice", message);
            String rationale = rationales.getLast();
            assertThat(rationale).isNotBlank();
            assertThat(rationale.length()).isBetween(1, 1000);
            assertThat(rationale.codePoints().anyMatch(Character::isISOControl)).isFalse();
            assertThat(Character.isHighSurrogate(rationale.charAt(rationale.length() - 1))).isFalse();
        }
        assertThat(rationales.getFirst()).isEqualTo("Create architecture version");
        assertThat(rationales.get(2)).startsWith("Summary Long details ");
    }
}
