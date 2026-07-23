package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.dto.ArchiMateImportResult;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogFacadeTest {

    @Mock private TaxonomyService taxonomyService;
    @Mock private TaxonomyRelationService relationService;
    @Mock private ArchiMateXmlImporter archiMateXmlImporter;
    @Mock private SearchService searchService;

    private CatalogFacade facade;

    @BeforeEach
    void setUp() {
        facade = new CatalogFacade(taxonomyService, relationService, archiMateXmlImporter, searchService);
    }

    @Test
    void missingTreeRootReturnsNullWithoutRelationLookup() {
        when(taxonomyService.getNodeByCode("missing")).thenReturn(null);

        assertThat(facade.getTreeWithRelations("missing", WorkspaceContext.SHARED)).isNull();
        verify(relationService, never()).getRelationsForNode("missing", null);
    }

    @Test
    void treeMapsOutgoingAndIncomingRelationsInWorkspaceScope() {
        WorkspaceContext context = new WorkspaceContext("alice", "ws-1", "draft");
        TaxonomyNode node = new TaxonomyNode();
        node.setCode("A");
        TaxonomyNodeDto dto = dto("A");
        TaxonomyRelationDto outgoing = relation("A", "B");
        TaxonomyRelationDto incoming = relation("C", "A");
        TaxonomyRelationDto unrelated = relation("X", "Y");
        when(taxonomyService.getNodeByCode("A")).thenReturn(node);
        when(taxonomyService.toDto(node)).thenReturn(dto);
        when(relationService.getRelationsForNode("A", "ws-1"))
                .thenReturn(List.of(outgoing, incoming, unrelated));

        TaxonomyNodeDto result = facade.getTreeWithRelations("A", context);

        assertThat(result).isSameAs(dto);
        assertThat(result.getOutgoingRelations()).containsExactly(outgoing);
        assertThat(result.getIncomingRelations()).containsExactly(incoming);
    }

    @Test
    void nullContextUsesSharedScopeAndSearchEnrichesEveryResult() {
        TaxonomyNodeDto first = dto("A");
        TaxonomyNodeDto second = dto("B");
        TaxonomyRelationDto firstOutgoing = relation("A", "B");
        TaxonomyRelationDto secondIncoming = relation("A", "B");
        when(searchService.search("term", 10)).thenReturn(List.of(first, second));
        when(relationService.getRelationsForNode("A", null)).thenReturn(List.of(firstOutgoing));
        when(relationService.getRelationsForNode("B", null)).thenReturn(List.of(secondIncoming));

        List<TaxonomyNodeDto> results = facade.searchWithContext("term", 10, null);

        assertThat(results).containsExactly(first, second);
        assertThat(first.getOutgoingRelations()).containsExactly(firstOutgoing);
        assertThat(first.getIncomingRelations()).isEmpty();
        assertThat(second.getIncomingRelations()).containsExactly(secondIncoming);
        assertThat(second.getOutgoingRelations()).isEmpty();
    }

    @Test
    void importDelegatesWithExplicitWorkspaceAndReturnsResult() {
        ByteArrayInputStream stream = new ByteArrayInputStream("<model/>".getBytes());
        WorkspaceContext context = new WorkspaceContext("alice", "ws-1", "draft");
        ArchiMateImportResult expected = new ArchiMateImportResult();
        when(archiMateXmlImporter.importXml(stream, context)).thenReturn(expected);

        assertThat(facade.importAndValidate(stream, context)).isSameAs(expected);
    }

    @Test
    void importRejectsMissingWorkspaceContext() {
        ByteArrayInputStream stream = new ByteArrayInputStream("<model/>".getBytes());

        assertThatThrownBy(() -> facade.importAndValidate(stream, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Workspace context");
        verify(archiMateXmlImporter, never()).importXml(stream, WorkspaceContext.SHARED);
    }

    private static TaxonomyNodeDto dto(String code) {
        TaxonomyNodeDto dto = new TaxonomyNodeDto();
        dto.setCode(code);
        return dto;
    }

    private static TaxonomyRelationDto relation(String source, String target) {
        TaxonomyRelationDto relation = new TaxonomyRelationDto();
        relation.setSourceCode(source);
        relation.setTargetCode(target);
        return relation;
    }
}