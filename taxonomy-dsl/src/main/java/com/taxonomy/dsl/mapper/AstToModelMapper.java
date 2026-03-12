package com.taxonomy.dsl.mapper;

import com.taxonomy.dsl.ast.*;
import com.taxonomy.dsl.model.*;

import java.util.*;

/**
 * Maps a {@link DocumentAst} (generic AST) into the {@link CanonicalArchitectureModel}.
 *
 * <p>Known block types are mapped to typed canonical objects. Unknown block types
 * are silently skipped (they remain accessible in the AST for lossless round-trips).
 */
public class AstToModelMapper {

    /**
     * Map a parsed document AST to a canonical architecture model.
     */
    public CanonicalArchitectureModel map(DocumentAst document) {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();

        for (BlockAst block : document.getBlocks()) {
            switch (block.getKind()) {
                case "element" -> mapElement(block, model);
                case "relation" -> mapRelation(block, model);
                case "requirement" -> mapRequirement(block, model);
                case "mapping" -> mapMapping(block, model);
                case "view" -> mapView(block, model);
                case "evidence" -> mapEvidence(block, model);
                default -> { /* unknown block type — preserved in AST */ }
            }
        }

        return model;
    }

    private void mapElement(BlockAst block, CanonicalArchitectureModel model) {
        List<String> tokens = block.getHeaderTokens();
        ArchitectureElement el = new ArchitectureElement();

        if (!tokens.isEmpty()) {
            el.setId(tokens.get(0));
        }
        // Parse "type <TypeName>" from header tokens
        for (int i = 1; i < tokens.size() - 1; i++) {
            if ("type".equals(tokens.get(i))) {
                el.setType(tokens.get(i + 1));
            }
        }

        el.setTitle(block.property("title"));
        el.setDescription(block.property("description"));
        el.setTaxonomy(block.property("taxonomy"));
        el.setExtensions(block.getExtensions());

        model.getElements().add(el);
    }

    private void mapRelation(BlockAst block, CanonicalArchitectureModel model) {
        // Header: sourceId RELATION_TYPE targetId
        List<String> tokens = block.getHeaderTokens();
        ArchitectureRelation rel = new ArchitectureRelation();

        if (tokens.size() >= 3) {
            rel.setSourceId(tokens.get(0));
            rel.setRelationType(tokens.get(1));
            rel.setTargetId(tokens.get(2));
        }

        rel.setStatus(block.property("status"));
        String conf = block.property("confidence");
        if (conf != null) {
            try { rel.setConfidence(Double.parseDouble(conf)); }
            catch (NumberFormatException ignored) { /* keep null */ }
        }
        rel.setProvenance(block.property("provenance"));
        rel.setExtensions(block.getExtensions());

        model.getRelations().add(rel);
    }

    private void mapRequirement(BlockAst block, CanonicalArchitectureModel model) {
        List<String> tokens = block.getHeaderTokens();
        ArchitectureRequirement req = new ArchitectureRequirement();

        if (!tokens.isEmpty()) {
            req.setId(tokens.get(0));
        }
        req.setTitle(block.property("title"));
        req.setText(block.property("text"));
        req.setExtensions(block.getExtensions());

        model.getRequirements().add(req);
    }

    private void mapMapping(BlockAst block, CanonicalArchitectureModel model) {
        // Header: REQ-ID -> ELEMENT-ID
        List<String> tokens = block.getHeaderTokens();
        RequirementMapping mapping = new RequirementMapping();

        if (tokens.size() >= 3 && "->".equals(tokens.get(1))) {
            mapping.setRequirementId(tokens.get(0));
            mapping.setElementId(tokens.get(2));
        }

        String score = block.property("score");
        if (score != null) {
            try { mapping.setScore(Double.parseDouble(score)); }
            catch (NumberFormatException ignored) { /* keep null */ }
        }
        mapping.setSource(block.property("source"));
        mapping.setExtensions(block.getExtensions());

        model.getMappings().add(mapping);
    }

    private void mapView(BlockAst block, CanonicalArchitectureModel model) {
        List<String> tokens = block.getHeaderTokens();
        ArchitectureView view = new ArchitectureView();

        if (!tokens.isEmpty()) {
            view.setId(tokens.get(0));
        }
        view.setTitle(block.property("title"));
        view.setIncludes(block.propertyValues("include"));
        view.setLayout(block.property("layout"));
        view.setExtensions(block.getExtensions());

        model.getViews().add(view);
    }

    private void mapEvidence(BlockAst block, CanonicalArchitectureModel model) {
        List<String> tokens = block.getHeaderTokens();
        ArchitectureEvidence ev = new ArchitectureEvidence();

        if (!tokens.isEmpty()) {
            ev.setId(tokens.get(0));
        }
        // Parse "for relation SOURCE TYPE TARGET" from header tokens
        for (int i = 1; i < tokens.size(); i++) {
            if ("for".equals(tokens.get(i)) && i + 4 < tokens.size()
                    && "relation".equals(tokens.get(i + 1))) {
                ev.setForRelationSource(tokens.get(i + 2));
                ev.setForRelationType(tokens.get(i + 3));
                ev.setForRelationTarget(tokens.get(i + 4));
            }
        }

        ev.setEvidenceType(block.property("type"));
        ev.setModel(block.property("model"));
        String conf = block.property("confidence");
        if (conf != null) {
            try { ev.setConfidence(Double.parseDouble(conf)); }
            catch (NumberFormatException ignored) { /* keep null */ }
        }
        ev.setSummary(block.property("summary"));
        ev.setExtensions(block.getExtensions());

        model.getEvidence().add(ev);
    }
}
