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
                case "source" -> mapSource(block, model);
                case "sourceVersion" -> mapSourceVersion(block, model);
                case "sourceFragment" -> mapSourceFragment(block, model);
                case "requirementSourceLink" -> mapRequirementSourceLink(block, model);
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

        // Parse "for-relation" property: "SOURCE TYPE TARGET"
        String forRelation = block.property("for-relation");
        if (forRelation != null) {
            String[] parts = forRelation.strip().split("\\s+");
            if (parts.length >= 3) {
                ev.setForRelationSource(parts[0]);
                ev.setForRelationType(parts[1]);
                ev.setForRelationTarget(parts[2]);
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

    private void mapSource(BlockAst block, CanonicalArchitectureModel model) {
        List<String> tokens = block.getHeaderTokens();
        ArchitectureSource src = new ArchitectureSource();

        if (!tokens.isEmpty()) {
            src.setId(tokens.get(0));
        }
        src.setSourceType(block.property("type"));
        src.setTitle(block.property("title"));
        src.setCanonicalIdentifier(block.property("canonicalIdentifier"));
        src.setCanonicalUrl(block.property("canonicalUrl"));
        src.setOriginSystem(block.property("originSystem"));
        src.setLanguage(block.property("language"));
        src.setExtensions(block.getExtensions());

        model.getSources().add(src);
    }

    private void mapSourceVersion(BlockAst block, CanonicalArchitectureModel model) {
        List<String> tokens = block.getHeaderTokens();
        ArchitectureSourceVersion sv = new ArchitectureSourceVersion();

        if (!tokens.isEmpty()) {
            sv.setId(tokens.get(0));
        }
        sv.setSourceId(block.property("source"));
        sv.setVersionLabel(block.property("versionLabel"));
        sv.setRetrievedAt(block.property("retrievedAt"));
        sv.setEffectiveDate(block.property("effectiveDate"));
        sv.setMimeType(block.property("mimeType"));
        sv.setContentHash(block.property("contentHash"));
        sv.setExtensions(block.getExtensions());

        model.getSourceVersions().add(sv);
    }

    private void mapSourceFragment(BlockAst block, CanonicalArchitectureModel model) {
        List<String> tokens = block.getHeaderTokens();
        ArchitectureSourceFragment sf = new ArchitectureSourceFragment();

        if (!tokens.isEmpty()) {
            sf.setId(tokens.get(0));
        }
        sf.setSourceVersionId(block.property("sourceVersion"));
        sf.setSectionPath(block.property("sectionPath"));
        sf.setParagraphRef(block.property("paragraphRef"));
        String pageFrom = block.property("pageFrom");
        if (pageFrom != null) {
            try { sf.setPageFrom(Integer.parseInt(pageFrom)); }
            catch (NumberFormatException ignored) { /* keep null */ }
        }
        String pageTo = block.property("pageTo");
        if (pageTo != null) {
            try { sf.setPageTo(Integer.parseInt(pageTo)); }
            catch (NumberFormatException ignored) { /* keep null */ }
        }
        sf.setText(block.property("text"));
        sf.setFragmentHash(block.property("fragmentHash"));
        sf.setParentFragmentId(block.property("parentFragment"));
        String chunkLevel = block.property("chunkLevel");
        if (chunkLevel != null) {
            try { sf.setChunkLevel(Integer.parseInt(chunkLevel)); }
            catch (NumberFormatException ignored) { /* keep null */ }
        }
        sf.setExtensions(block.getExtensions());

        model.getSourceFragments().add(sf);
    }

    private void mapRequirementSourceLink(BlockAst block, CanonicalArchitectureModel model) {
        List<String> tokens = block.getHeaderTokens();
        ArchitectureRequirementSourceLink rsl = new ArchitectureRequirementSourceLink();

        if (!tokens.isEmpty()) {
            rsl.setId(tokens.get(0));
        }
        rsl.setRequirementId(block.property("requirement"));
        rsl.setSourceId(block.property("source"));
        rsl.setSourceVersionId(block.property("sourceVersion"));
        rsl.setSourceFragmentId(block.property("sourceFragment"));
        rsl.setLinkType(block.property("linkType"));
        String conf = block.property("confidence");
        if (conf != null) {
            try { rsl.setConfidence(Double.parseDouble(conf)); }
            catch (NumberFormatException ignored) { /* keep null */ }
        }
        rsl.setNote(block.property("note"));
        rsl.setExtensions(block.getExtensions());

        model.getRequirementSourceLinks().add(rsl);
    }
}
