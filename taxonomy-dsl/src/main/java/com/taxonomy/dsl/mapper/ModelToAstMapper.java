package com.taxonomy.dsl.mapper;

import com.taxonomy.dsl.ast.*;
import com.taxonomy.dsl.model.*;

import java.util.*;

/**
 * Maps canonical architecture model objects back to {@link BlockAst} nodes
 * for serialization to DSL text.
 */
public class ModelToAstMapper {

    /**
     * Map a complete canonical model to a document AST including meta header.
     */
    public DocumentAst toDocument(CanonicalArchitectureModel model, String namespace) {
        MetaAst meta = new MetaAst(MetaAst.LANGUAGE_ID, MetaAst.CURRENT_VERSION, namespace, null);
        List<BlockAst> blocks = new ArrayList<>();

        for (ArchitectureElement el : model.getElements()) {
            blocks.add(elementToBlock(el));
        }
        for (ArchitectureRelation rel : model.getRelations()) {
            blocks.add(relationToBlock(rel));
        }
        for (ArchitectureRequirement req : model.getRequirements()) {
            blocks.add(requirementToBlock(req));
        }
        for (RequirementMapping mapping : model.getMappings()) {
            blocks.add(mappingToBlock(mapping));
        }
        for (ArchitectureView view : model.getViews()) {
            blocks.add(viewToBlock(view));
        }
        for (ArchitectureEvidence ev : model.getEvidence()) {
            blocks.add(evidenceToBlock(ev));
        }

        return new DocumentAst(meta, blocks);
    }

    private BlockAst elementToBlock(ArchitectureElement el) {
        List<String> headerTokens = new ArrayList<>();
        if (el.getId() != null) headerTokens.add(el.getId());
        if (el.getType() != null) {
            headerTokens.add("type");
            headerTokens.add(el.getType());
        }

        List<PropertyAst> props = new ArrayList<>();
        addProperty(props, "title", el.getTitle());
        addProperty(props, "description", el.getDescription());
        addProperty(props, "taxonomy", el.getTaxonomy());
        addExtensions(props, el.getExtensions());

        return new BlockAst("element", headerTokens, props, List.of(), el.getExtensions(), null);
    }

    private BlockAst relationToBlock(ArchitectureRelation rel) {
        List<String> headerTokens = new ArrayList<>();
        if (rel.getSourceId() != null) headerTokens.add(rel.getSourceId());
        if (rel.getRelationType() != null) headerTokens.add(rel.getRelationType());
        if (rel.getTargetId() != null) headerTokens.add(rel.getTargetId());

        List<PropertyAst> props = new ArrayList<>();
        addProperty(props, "status", rel.getStatus());
        if (rel.getConfidence() != null) {
            addProperty(props, "confidence", String.valueOf(rel.getConfidence()));
        }
        addProperty(props, "provenance", rel.getProvenance());
        addExtensions(props, rel.getExtensions());

        return new BlockAst("relation", headerTokens, props, List.of(), rel.getExtensions(), null);
    }

    private BlockAst requirementToBlock(ArchitectureRequirement req) {
        List<String> headerTokens = new ArrayList<>();
        if (req.getId() != null) headerTokens.add(req.getId());

        List<PropertyAst> props = new ArrayList<>();
        addProperty(props, "title", req.getTitle());
        addProperty(props, "text", req.getText());
        addExtensions(props, req.getExtensions());

        return new BlockAst("requirement", headerTokens, props, List.of(), req.getExtensions(), null);
    }

    private BlockAst mappingToBlock(RequirementMapping mapping) {
        List<String> headerTokens = new ArrayList<>();
        if (mapping.getRequirementId() != null) headerTokens.add(mapping.getRequirementId());
        headerTokens.add("->");
        if (mapping.getElementId() != null) headerTokens.add(mapping.getElementId());

        List<PropertyAst> props = new ArrayList<>();
        if (mapping.getScore() != null) {
            addProperty(props, "score", String.valueOf(mapping.getScore()));
        }
        addProperty(props, "source", mapping.getSource());
        addExtensions(props, mapping.getExtensions());

        return new BlockAst("mapping", headerTokens, props, List.of(), mapping.getExtensions(), null);
    }

    private BlockAst viewToBlock(ArchitectureView view) {
        List<String> headerTokens = new ArrayList<>();
        if (view.getId() != null) headerTokens.add(view.getId());

        List<PropertyAst> props = new ArrayList<>();
        addProperty(props, "title", view.getTitle());
        for (String inc : view.getIncludes()) {
            addProperty(props, "include", inc);
        }
        addProperty(props, "layout", view.getLayout());
        addExtensions(props, view.getExtensions());

        return new BlockAst("view", headerTokens, props, List.of(), view.getExtensions(), null);
    }

    private BlockAst evidenceToBlock(ArchitectureEvidence ev) {
        List<String> headerTokens = new ArrayList<>();
        if (ev.getId() != null) headerTokens.add(ev.getId());
        if (ev.getForRelationSource() != null) {
            headerTokens.add("for");
            headerTokens.add("relation");
            headerTokens.add(ev.getForRelationSource());
            if (ev.getForRelationType() != null) headerTokens.add(ev.getForRelationType());
            if (ev.getForRelationTarget() != null) headerTokens.add(ev.getForRelationTarget());
        }

        List<PropertyAst> props = new ArrayList<>();
        addProperty(props, "type", ev.getEvidenceType());
        addProperty(props, "model", ev.getModel());
        if (ev.getConfidence() != null) {
            addProperty(props, "confidence", String.valueOf(ev.getConfidence()));
        }
        addProperty(props, "summary", ev.getSummary());
        addExtensions(props, ev.getExtensions());

        return new BlockAst("evidence", headerTokens, props, List.of(), ev.getExtensions(), null);
    }

    private void addProperty(List<PropertyAst> props, String key, String value) {
        if (value != null) {
            props.add(new PropertyAst(key, value, null));
        }
    }

    private void addExtensions(List<PropertyAst> props, Map<String, String> extensions) {
        if (extensions != null) {
            for (Map.Entry<String, String> entry : extensions.entrySet()) {
                props.add(new PropertyAst(entry.getKey(), entry.getValue(), null));
            }
        }
    }
}
