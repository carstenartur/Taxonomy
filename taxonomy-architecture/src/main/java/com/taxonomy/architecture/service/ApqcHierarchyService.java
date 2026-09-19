package com.taxonomy.architecture.service;

import com.taxonomy.dto.ApqcHierarchyNode;
import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.model.TaxonomyRootTypes;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.architecture.model.ArchitectureDslDocument;
import com.taxonomy.architecture.repository.ArchitectureDslDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service that extracts the APQC process hierarchy from imported DSL documents.
 *
 * <p>Scans all stored DSL documents for elements with the
 * {@code x-source-framework: "apqc"} extension and reconstructs the
 * parent–child hierarchy using {@code x-apqc-parent} chains.
 */
@Service
public class ApqcHierarchyService {

    private static final Logger log = LoggerFactory.getLogger(ApqcHierarchyService.class);

    private final ArchitectureDslDocumentRepository documentRepository;

    public ApqcHierarchyService(ArchitectureDslDocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    /**
     * Build the APQC process hierarchy from all stored DSL documents.
     *
     * @return list of root APQC hierarchy nodes with children linked
     */
    public List<ApqcHierarchyNode> buildHierarchy() {
        TaxDslParser parser = new TaxDslParser();
        AstToModelMapper mapper = new AstToModelMapper();
        List<ArchitectureElement> apqcElements = new ArrayList<>();

        for (ArchitectureDslDocument doc : documentRepository.findAll()) {
            if (doc.getRawContent() == null || doc.getRawContent().isBlank()) continue;
            try {
                var ast = parser.parse(doc.getRawContent(), doc.getPath());
                CanonicalArchitectureModel model = mapper.map(ast);
                for (ArchitectureElement el : model.getElements()) {
                    if ("apqc".equals(el.getExtensions().get("x-source-framework"))) {
                        apqcElements.add(el);
                    }
                }
            } catch (Exception e) {
                log.debug("Skipping unparseable DSL document '{}': {}", doc.getPath(), e.getMessage());
            }
        }

        // Build lookup map and tree
        Map<String, ApqcHierarchyNode> nodeMap = new LinkedHashMap<>();
        for (ArchitectureElement el : apqcElements) {
            Map<String, String> ext = el.getExtensions();
            String level = ext.getOrDefault("x-apqc-level", "Unknown");
            String pcfId = ext.getOrDefault("x-apqc-pcf-id", "");
            String parentId = ext.get("x-apqc-parent");
            String taxonomyRoot = TaxonomyRootTypes.rootFor(el.getType());
            nodeMap.put(el.getId(), new ApqcHierarchyNode(
                    el.getId(), el.getTitle(), level, pcfId, parentId,
                    taxonomyRoot != null ? taxonomyRoot : "",
                    new ArrayList<>()));
        }

        // Link children to parents
        List<ApqcHierarchyNode> roots = new ArrayList<>();
        for (ApqcHierarchyNode node : nodeMap.values()) {
            if (node.parentId() != null && nodeMap.containsKey(node.parentId())) {
                nodeMap.get(node.parentId()).children().add(node);
            } else {
                roots.add(node);
            }
        }

        return roots;
    }
}
