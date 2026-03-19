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
        for (ArchitectureSource src : model.getSources()) {
            blocks.add(sourceToBlock(src));
        }
        for (ArchitectureSourceVersion sv : model.getSourceVersions()) {
            blocks.add(sourceVersionToBlock(sv));
        }
        for (ArchitectureSourceFragment sf : model.getSourceFragments()) {
            blocks.add(sourceFragmentToBlock(sf));
        }
        for (ArchitectureRequirementSourceLink rsl : model.getRequirementSourceLinks()) {
            blocks.add(requirementSourceLinkToBlock(rsl));
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

        List<PropertyAst> props = new ArrayList<>();
        // Serialize for-relation as a property: "SOURCE TYPE TARGET"
        if (ev.getForRelationSource() != null) {
            StringBuilder forRel = new StringBuilder(ev.getForRelationSource());
            if (ev.getForRelationType() != null) forRel.append(' ').append(ev.getForRelationType());
            if (ev.getForRelationTarget() != null) forRel.append(' ').append(ev.getForRelationTarget());
            addProperty(props, "for-relation", forRel.toString());
        }
        addProperty(props, "type", ev.getEvidenceType());
        addProperty(props, "model", ev.getModel());
        if (ev.getConfidence() != null) {
            addProperty(props, "confidence", String.valueOf(ev.getConfidence()));
        }
        addProperty(props, "summary", ev.getSummary());
        addExtensions(props, ev.getExtensions());

        return new BlockAst("evidence", headerTokens, props, List.of(), ev.getExtensions(), null);
    }

    private BlockAst sourceToBlock(ArchitectureSource src) {
        List<String> headerTokens = new ArrayList<>();
        if (src.getId() != null) headerTokens.add(src.getId());

        List<PropertyAst> props = new ArrayList<>();
        addProperty(props, "type", src.getSourceType());
        addProperty(props, "title", src.getTitle());
        addProperty(props, "canonicalIdentifier", src.getCanonicalIdentifier());
        addProperty(props, "canonicalUrl", src.getCanonicalUrl());
        addProperty(props, "originSystem", src.getOriginSystem());
        addProperty(props, "language", src.getLanguage());
        addExtensions(props, src.getExtensions());

        return new BlockAst("source", headerTokens, props, List.of(), src.getExtensions(), null);
    }

    private BlockAst sourceVersionToBlock(ArchitectureSourceVersion sv) {
        List<String> headerTokens = new ArrayList<>();
        if (sv.getId() != null) headerTokens.add(sv.getId());

        List<PropertyAst> props = new ArrayList<>();
        addProperty(props, "source", sv.getSourceId());
        addProperty(props, "versionLabel", sv.getVersionLabel());
        addProperty(props, "retrievedAt", sv.getRetrievedAt());
        addProperty(props, "effectiveDate", sv.getEffectiveDate());
        addProperty(props, "mimeType", sv.getMimeType());
        addProperty(props, "contentHash", sv.getContentHash());
        addExtensions(props, sv.getExtensions());

        return new BlockAst("sourceVersion", headerTokens, props, List.of(), sv.getExtensions(), null);
    }

    private BlockAst sourceFragmentToBlock(ArchitectureSourceFragment sf) {
        List<String> headerTokens = new ArrayList<>();
        if (sf.getId() != null) headerTokens.add(sf.getId());

        List<PropertyAst> props = new ArrayList<>();
        addProperty(props, "sourceVersion", sf.getSourceVersionId());
        addProperty(props, "sectionPath", sf.getSectionPath());
        addProperty(props, "paragraphRef", sf.getParagraphRef());
        if (sf.getPageFrom() != null) {
            addProperty(props, "pageFrom", String.valueOf(sf.getPageFrom()));
        }
        if (sf.getPageTo() != null) {
            addProperty(props, "pageTo", String.valueOf(sf.getPageTo()));
        }
        addProperty(props, "text", sf.getText());
        addProperty(props, "fragmentHash", sf.getFragmentHash());
        addExtensions(props, sf.getExtensions());

        return new BlockAst("sourceFragment", headerTokens, props, List.of(), sf.getExtensions(), null);
    }

    private BlockAst requirementSourceLinkToBlock(ArchitectureRequirementSourceLink rsl) {
        List<String> headerTokens = new ArrayList<>();
        if (rsl.getId() != null) headerTokens.add(rsl.getId());

        List<PropertyAst> props = new ArrayList<>();
        addProperty(props, "requirement", rsl.getRequirementId());
        addProperty(props, "source", rsl.getSourceId());
        addProperty(props, "sourceVersion", rsl.getSourceVersionId());
        addProperty(props, "sourceFragment", rsl.getSourceFragmentId());
        addProperty(props, "linkType", rsl.getLinkType());
        if (rsl.getConfidence() != null) {
            addProperty(props, "confidence", String.valueOf(rsl.getConfidence()));
        }
        addProperty(props, "note", rsl.getNote());
        addExtensions(props, rsl.getExtensions());

        return new BlockAst("requirementSourceLink", headerTokens, props, List.of(), rsl.getExtensions(), null);
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
