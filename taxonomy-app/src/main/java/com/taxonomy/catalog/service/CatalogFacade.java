package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.dto.ArchiMateImportResult;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;

/** High-level catalog use-case facade with explicit workspace scope. */
@Service
public class CatalogFacade {

    private static final Logger log = LoggerFactory.getLogger(CatalogFacade.class);

    private final TaxonomyService taxonomyService;
    private final TaxonomyRelationService relationService;
    private final ArchiMateXmlImporter archiMateXmlImporter;
    private final SearchService searchService;

    public CatalogFacade(TaxonomyService taxonomyService,
                         TaxonomyRelationService relationService,
                         ArchiMateXmlImporter archiMateXmlImporter,
                         SearchService searchService) {
        this.taxonomyService = taxonomyService;
        this.relationService = relationService;
        this.archiMateXmlImporter = archiMateXmlImporter;
        this.searchService = searchService;
    }

    @Transactional(readOnly = true)
    public TaxonomyNodeDto getTreeWithRelations(String rootCode, WorkspaceContext workspaceContext) {
        TaxonomyNode node = taxonomyService.getNodeByCode(rootCode);
        if (node == null) {
            log.debug("No taxonomy node found for code '{}'", rootCode);
            return null;
        }

        TaxonomyNodeDto dto = taxonomyService.toDto(node);
        attachRelations(dto, workspaceContext);
        return dto;
    }

    public ArchiMateImportResult importAndValidate(InputStream xmlStream) {
        log.info("Starting ArchiMate XML import via facade");
        ArchiMateImportResult result = archiMateXmlImporter.importXml(xmlStream);
        log.info("ArchiMate import complete – {} elements matched, {} relations created",
                result.getElementsMatched(), result.getRelationsImported());
        return result;
    }

    @Transactional(readOnly = true)
    public List<TaxonomyNodeDto> searchWithContext(String query,
                                                    int maxResults,
                                                    WorkspaceContext workspaceContext) {
        List<TaxonomyNodeDto> results = searchService.search(query, maxResults);
        results.forEach(dto -> attachRelations(dto, workspaceContext));
        return results;
    }

    private void attachRelations(TaxonomyNodeDto dto, WorkspaceContext workspaceContext) {
        WorkspaceContext context = workspaceContext != null ? workspaceContext : WorkspaceContext.SHARED;
        List<TaxonomyRelationDto> relations =
                relationService.getRelationsForNode(dto.getCode(), context.workspaceId());
        dto.setOutgoingRelations(relations.stream()
                .filter(relation -> dto.getCode().equals(relation.getSourceCode()))
                .toList());
        dto.setIncomingRelations(relations.stream()
                .filter(relation -> dto.getCode().equals(relation.getTargetCode()))
                .toList());
    }
}
