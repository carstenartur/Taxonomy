package com.taxonomy.dsl.export;

import com.taxonomy.architecture.model.ArchitectureDslDocument;
import com.taxonomy.architecture.repository.ArchitectureDslDocumentRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DslMaterializeWriteScopeTest {

    private static final String BASE_DSL = """
            meta {
              language: "taxdsl";
              version: "2.0";
              namespace: "scope-test";
            }

            element APP-1 type Application {
              title: "Application";
            }
            """;

    @Test
    void completeMaterializationFailsBeforeProjectionOrDocumentWrites() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service().materialize(
                BASE_DSL, "architecture.taxdsl", "main", "a".repeat(40)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit central write context");

        verifyNoInteractions(
                fixture.relations(), fixture.hypotheses(), fixture.documents(), fixture.projections());
    }

    @Test
    void completeMaterializationPersistsNormalizedSelectedBranch() {
        Fixture fixture = fixture(RepositoryContext.workspace("repo-a", "w", "review", "alice"));
        when(fixture.documents().save(any(ArchitectureDslDocument.class))).thenAnswer(invocation -> {
            ArchitectureDslDocument document = invocation.getArgument(0);
            document.setId(42L);
            return document;
        });

        var result = fixture.service().materialize(
                BASE_DSL, "architecture.taxdsl", " review ", "a".repeat(40));

        assertThat(result.valid()).isTrue();
        assertThat(result.documentId()).isEqualTo(42L);
        var saved = ArgumentCaptor.forClass(ArchitectureDslDocument.class);
        verify(fixture.documents()).save(saved.capture());
        assertThat(saved.getValue().getBranch()).isEqualTo("review");
        verify(fixture.projections()).recordProjection("alice", "a".repeat(40), "review");
    }

    @Test
    void incrementalMaterializationUsesTheSameWritableContextGuard() {
        Fixture fixture = fixture();
        ArchitectureDslDocument before = document(1L, BASE_DSL);
        ArchitectureDslDocument after = document(2L, BASE_DSL + """

                element SVC-1 type Service {
                  title: "Service";
                }
                """);
        when(fixture.documents().findById(1L)).thenReturn(Optional.of(before));
        when(fixture.documents().findById(2L)).thenReturn(Optional.of(after));

        assertThatThrownBy(() -> fixture.service().materializeIncremental(1L, 2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit central write context");

        verifyNoInteractions(fixture.relations(), fixture.hypotheses());
    }

    @Test
    void contradictoryCompleteAndStoredBranchesFailBeforeWrites() {
        Fixture f=fixture(RepositoryContext.workspace("repo-a","w","main","alice"));
        assertThatThrownBy(()->f.service().materialize(BASE_DSL,"a.taxdsl","draft","commit"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("branch");
        var after=document(2L,BASE_DSL); after.setBranch("draft");
        when(f.documents().findById(2L)).thenReturn(Optional.of(after));
        assertThatThrownBy(()->f.service().materializeIncremental(null,2L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("branch");
        verifyNoInteractions(f.relations(),f.hypotheses(),f.projections());
        verify(f.documents(),never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void incrementalMaterializationRejectsUnknownStoredBranch(boolean missingBeforeBranch) {
        Fixture fixture = fixture(RepositoryContext.workspace("repo-a", "w", "review", "alice"));
        var before = document(1L, BASE_DSL);
        before.setBranch(missingBeforeBranch ? null : "review");
        var after = document(2L, BASE_DSL);
        after.setBranch(missingBeforeBranch ? "review" : null);
        when(fixture.documents().findById(1L)).thenReturn(Optional.of(before));
        when(fixture.documents().findById(2L)).thenReturn(Optional.of(after));

        assertThatThrownBy(() -> fixture.service().materializeIncremental(1L, 2L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("branch");

        verifyNoInteractions(fixture.relations(), fixture.hypotheses(), fixture.projections());
        verify(fixture.documents(), never()).save(any());
    }

    private static Fixture fixture() {
        return fixture(RepositoryContext.centralRead("repo-a","main","alice"));
    }
    private static Fixture fixture(RepositoryContext context) {
        TaxonomyRelationService relations = mock(TaxonomyRelationService.class);
        RelationHypothesisRepository hypotheses =
                mock(RelationHypothesisRepository.class);
        ArchitectureDslDocumentRepository documents =
                mock(ArchitectureDslDocumentRepository.class);
        WorkspaceResolver resolver = mock(WorkspaceResolver.class);
        RepositoryStateService projections = mock(RepositoryStateService.class);
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(
                context);
        DslMaterializeService service = new DslMaterializeService(
                relations, hypotheses, documents, projections, resolver, null);
        return new Fixture(service, relations, hypotheses, documents, projections);
    }

    private static ArchitectureDslDocument document(Long id, String content) {
        ArchitectureDslDocument document = new ArchitectureDslDocument();
        document.setId(id);
        document.setPath("architecture.taxdsl");
        document.setRawContent(content);
        return document;
    }

    private record Fixture(
            DslMaterializeService service,
            TaxonomyRelationService relations,
            RelationHypothesisRepository hypotheses,
            ArchitectureDslDocumentRepository documents,
            RepositoryStateService projections) {
    }
}
