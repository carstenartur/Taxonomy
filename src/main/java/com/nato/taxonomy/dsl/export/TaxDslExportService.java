package com.nato.taxonomy.dsl.export;

import com.nato.taxonomy.dsl.mapper.ModelToAstMapper;
import com.nato.taxonomy.dsl.model.*;
import com.nato.taxonomy.dsl.serializer.TaxDslSerializer;
import com.nato.taxonomy.model.RelationType;
import com.nato.taxonomy.model.TaxonomyNode;
import com.nato.taxonomy.model.TaxonomyRelation;
import com.nato.taxonomy.repository.TaxonomyNodeRepository;
import com.nato.taxonomy.repository.TaxonomyRelationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Exports the current architecture state (taxonomy nodes and relations)
 * as DSL text.
 */
@Service
public class TaxDslExportService {

    private final TaxonomyNodeRepository nodeRepository;
    private final TaxonomyRelationRepository relationRepository;
    private final ModelToAstMapper modelToAstMapper = new ModelToAstMapper();
    private final TaxDslSerializer serializer = new TaxDslSerializer();

    public TaxDslExportService(TaxonomyNodeRepository nodeRepository,
                               TaxonomyRelationRepository relationRepository) {
        this.nodeRepository = nodeRepository;
        this.relationRepository = relationRepository;
    }

    /**
     * Export the complete current architecture as a single DSL document.
     *
     * @param namespace the namespace for the meta block
     * @return serialized DSL text
     */
    @Transactional(readOnly = true)
    public String exportAll(String namespace) {
        CanonicalArchitectureModel model = buildCanonicalModel();
        var doc = modelToAstMapper.toDocument(model, namespace);
        return serializer.serialize(doc);
    }

    /**
     * Build a canonical architecture model from the current database state.
     */
    @Transactional(readOnly = true)
    public CanonicalArchitectureModel buildCanonicalModel() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();

        // Export all non-root taxonomy nodes as elements
        List<TaxonomyNode> allNodes = nodeRepository.findAll();
        for (TaxonomyNode node : allNodes) {
            if (node.getLevel() > 0) { // Skip virtual root nodes
                ArchitectureElement el = new ArchitectureElement();
                el.setId(node.getCode());
                el.setType(mapTaxonomyRootToType(node.getTaxonomyRoot()));
                el.setTitle(node.getNameEn());
                el.setDescription(node.getDescriptionEn());
                el.setTaxonomy(node.getTaxonomyRoot());
                model.getElements().add(el);
            }
        }

        // Export all relations
        List<TaxonomyRelation> allRelations = relationRepository.findAll();
        for (TaxonomyRelation rel : allRelations) {
            ArchitectureRelation archRel = new ArchitectureRelation();
            archRel.setSourceId(rel.getSourceNode().getCode());
            archRel.setRelationType(rel.getRelationType().name());
            archRel.setTargetId(rel.getTargetNode().getCode());
            archRel.setStatus("accepted");
            archRel.setProvenance(rel.getProvenance());
            model.getRelations().add(archRel);
        }

        return model;
    }

    /**
     * Map a taxonomy root code to a human-readable element type.
     */
    private String mapTaxonomyRootToType(String taxonomyRoot) {
        if (taxonomyRoot == null) return "Unknown";
        return switch (taxonomyRoot) {
            case "CP" -> "Capability";
            case "BP" -> "Process";
            case "CR" -> "CoreService";
            case "CI" -> "COIService";
            case "CO" -> "CommunicationsService";
            case "UA" -> "UserApplication";
            case "IP" -> "InformationProduct";
            case "BR" -> "BusinessRole";
            default -> taxonomyRoot;
        };
    }
}
