package com.taxonomy.catalog.service;

import com.taxonomy.dto.ArchiMateImportResult;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;

/**
 * High-level facade that aggregates the catalog domain services into a
 * single, coarse-grained API for use by controllers and other consumers.
 *
 * <p>Each method orchestrates one or more fine-grained service calls to
 * fulfil a complete use-case, keeping controllers thin and business logic
 * in the service layer.
 */
@Service
public class CatalogFacade {

    private static final Logger log = LoggerFactory.getLogger(CatalogFacade.class);

    private final TaxonomyService taxonomyService;
    private final TaxonomyRelationService relationService;
    private final ArchiMateXmlImporter archiMateXmlImporter;
    private final SearchService searchService;
    private final WorkspaceContextResolver contextResolver;

    public CatalogFacade(TaxonomyService taxonomyService,
                         TaxonomyRelationService relationService,
                         ArchiMateXmlImporter archiMateXmlImporter,
                         SearchService searchService,
                         WorkspaceContextResolver contextResolver) {
        this.taxonomyService = taxonomyService;
        this.relationService = relationService;
        this.archiMateXmlImporter = archiMateXmlImporter;
        this.searchService = searchService;
        this.contextResolver = contextResolver;
    }

    /**
     * Returns a taxonomy node identified by {@code rootCode} together with its
     * full relation context (both outgoing and incoming relations).
     *
     * @param rootCode the taxonomy code (e.g. {@code "CP-1023"})
     * @return the node DTO enriched with relations, or {@code null} if the code
     *         does not match any node
     */
    @Transactional(readOnly = true)
    public TaxonomyNodeDto getTreeWithRelations(String rootCode) {
        TaxonomyNode node = taxonomyService.getNodeByCode(rootCode);
        if (node == null) {
            log.debug("No taxonomy node found for code '{}'", rootCode);
            return null;
        }

        TaxonomyNodeDto dto = taxonomyService.toDto(node);

        WorkspaceContext ctx = contextResolver.resolveCurrentContext();
        List<TaxonomyRelationDto> relations = relationService.getRelationsForNode(rootCode, ctx.workspaceId());
        List<TaxonomyRelationDto> outgoing = relations.stream()
                .filter(r -> rootCode.equals(r.getSourceCode()))
                .toList();
        List<TaxonomyRelationDto> incoming = relations.stream()
                .filter(r -> rootCode.equals(r.getTargetCode()))
                .toList();
        dto.setOutgoingRelations(outgoing);
        dto.setIncomingRelations(incoming);

        return dto;
    }

    /**
     * Imports an ArchiMate 3.x XML file, mapping elements and relationships to
     * taxonomy nodes and relations.
     *
     * @param xmlStream the XML input stream
     * @return the import result containing match/create statistics and notes
     */
    public ArchiMateImportResult importAndValidate(InputStream xmlStream) {
        log.info("Starting ArchiMate XML import via facade");
        ArchiMateImportResult result = archiMateXmlImporter.importXml(xmlStream);
        log.info("ArchiMate import complete – {} elements matched, {} relations created",
                result.getElementsMatched(), result.getRelationsImported());
        return result;
    }

    /**
     * Performs a full-text search and enriches each result with its relation
     * context (outgoing and incoming relations).
     *
     * @param query      the search query string
     * @param maxResults the maximum number of results to return
     * @return search results with relation context attached
     */
    @Transactional(readOnly = true)
    public List<TaxonomyNodeDto> searchWithContext(String query, int maxResults) {
        List<TaxonomyNodeDto> results = searchService.search(query, maxResults);

        WorkspaceContext ctx = contextResolver.resolveCurrentContext();
        for (TaxonomyNodeDto dto : results) {
            List<TaxonomyRelationDto> relations = relationService.getRelationsForNode(dto.getCode(), ctx.workspaceId());
            List<TaxonomyRelationDto> outgoing = relations.stream()
                    .filter(r -> dto.getCode().equals(r.getSourceCode()))
                    .toList();
            List<TaxonomyRelationDto> incoming = relations.stream()
                    .filter(r -> dto.getCode().equals(r.getTargetCode()))
                    .toList();
            dto.setOutgoingRelations(outgoing);
            dto.setIncomingRelations(incoming);
        }

        return results;
    }
}
