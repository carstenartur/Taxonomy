package com.taxonomy.dsl;

import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.ast.MetaAst;
import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.mapper.ModelToAstMapper;
import com.taxonomy.dsl.model.*;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ModelToAstMapper}: Canonical Model → AST → DSL text mapping.
 */
class ModelToAstMapperTest {

    private ModelToAstMapper mapper;
    private TaxDslSerializer serializer;
    private TaxDslParser parser;
    private AstToModelMapper astToModel;

    @BeforeEach
    void setUp() {
        mapper = new ModelToAstMapper();
        serializer = new TaxDslSerializer();
        parser = new TaxDslParser();
        astToModel = new AstToModelMapper();
    }

    @Test
    void mapElementToAst() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        ArchitectureElement el = new ArchitectureElement("CP-1023", "Capability", "Test", "Description", "CP");
        el.setExtensions(Map.of("x-owner", "CIS"));
        model.getElements().add(el);

        DocumentAst doc = mapper.toDocument(model, "test");
        String dsl = serializer.serialize(doc);

        assertThat(dsl).contains("element CP-1023 type Capability {");
        assertThat(dsl).contains("title: \"Test\"");
        assertThat(dsl).contains("description: \"Description\"");
        assertThat(dsl).contains("x-owner: \"CIS\"");
    }

    @Test
    void mapRelationToAst() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        ArchitectureRelation rel = new ArchitectureRelation("CR-1011", "SUPPORTS", "BP-1327");
        rel.setStatus("proposed");
        rel.setConfidence(0.76);
        model.getRelations().add(rel);

        DocumentAst doc = mapper.toDocument(model, "test");
        String dsl = serializer.serialize(doc);

        assertThat(dsl).contains("relation CR-1011 SUPPORTS BP-1327 {");
        assertThat(dsl).contains("status: proposed;");
        assertThat(dsl).contains("confidence: 0.76;");
    }

    @Test
    void mapViewToAst() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        ArchitectureView view = new ArchitectureView("overview", "Architecture Overview");
        view.setIncludes(List.of("CP-1023", "BP-1327"));
        view.setLayout("layered");
        model.getViews().add(view);

        DocumentAst doc = mapper.toDocument(model, "test");
        String dsl = serializer.serialize(doc);

        assertThat(dsl).contains("view overview {");
        assertThat(dsl).contains("title: \"Architecture Overview\"");
        assertThat(dsl).contains("include: \"CP-1023\"");
        assertThat(dsl).contains("include: \"BP-1327\"");
        assertThat(dsl).contains("layout: layered;");
    }

    @Test
    void fullRoundtripModelToAstToModel() {
        // Build a model
        CanonicalArchitectureModel original = new CanonicalArchitectureModel();
        original.getElements().add(new ArchitectureElement("CP-1023", "Capability", "Cap One", "Desc", "CP"));
        original.getElements().add(new ArchitectureElement("BP-1327", "Process", "Proc One", "Desc", "BP"));

        ArchitectureRelation rel = new ArchitectureRelation("CP-1023", "REALIZES", "BP-1327");
        rel.setStatus("accepted");
        rel.setConfidence(0.83);
        original.getRelations().add(rel);

        original.getRequirements().add(new ArchitectureRequirement("REQ-001", "Req One", "Req text"));

        RequirementMapping mapping = new RequirementMapping("REQ-001", "CP-1023");
        mapping.setScore(0.92);
        mapping.setSource("llm");
        original.getMappings().add(mapping);

        ArchitectureView view = new ArchitectureView("overview", "Overview");
        view.setIncludes(List.of("CP-1023", "BP-1327"));
        view.setLayout("layered");
        original.getViews().add(view);

        // Model → AST → DSL text → AST → Model
        DocumentAst doc = mapper.toDocument(original, "test");
        String dsl = serializer.serialize(doc);
        DocumentAst reparsed = parser.parse(dsl);
        CanonicalArchitectureModel restored = astToModel.map(reparsed);

        // Verify structural equivalence
        assertThat(restored.getElements()).hasSize(2);
        assertThat(restored.getRelations()).hasSize(1);
        assertThat(restored.getRequirements()).hasSize(1);
        assertThat(restored.getMappings()).hasSize(1);
        assertThat(restored.getViews()).hasSize(1);

        // Verify content (elements sorted by ID in serialized output: BP-1327 before CP-1023)
        assertThat(restored.getElements()).extracting(ArchitectureElement::getId)
                .containsExactlyInAnyOrder("CP-1023", "BP-1327");
        assertThat(restored.getRelations().get(0).getRelationType()).isEqualTo("REALIZES");
        assertThat(restored.getRequirements().get(0).getId()).isEqualTo("REQ-001");
        assertThat(restored.getMappings().get(0).getScore()).isEqualTo(0.92);
        assertThat(restored.getViews().get(0).getIncludes()).containsExactly("CP-1023", "BP-1327");
    }

    @Test
    void mapEvidenceToAst() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        ArchitectureEvidence ev = new ArchitectureEvidence("EV-001");
        ev.setForRelationSource("CR-1011");
        ev.setForRelationType("SUPPORTS");
        ev.setForRelationTarget("BP-1327");
        ev.setEvidenceType("LLM");
        ev.setModel("gpt-4.1-mini");
        ev.setConfidence(0.76);
        ev.setSummary("Service supports process.");
        model.getEvidence().add(ev);

        DocumentAst doc = mapper.toDocument(model, "test");
        String dsl = serializer.serialize(doc);

        assertThat(dsl).contains("evidence EV-001 {");
        assertThat(dsl).contains("for-relation: \"CR-1011 SUPPORTS BP-1327\";");
        assertThat(dsl).contains("type: LLM;");
        assertThat(dsl).contains("model: \"gpt-4.1-mini\"");
        assertThat(dsl).contains("confidence: 0.76;");
    }

    @Test
    void metaBlockIncluded() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        model.getElements().add(new ArchitectureElement("CP-1023", "Capability", "Test", null, null));

        DocumentAst doc = mapper.toDocument(model, "mission.secure-voice");
        String dsl = serializer.serialize(doc);

        assertThat(dsl).contains("meta {");
        assertThat(dsl).contains("language: \"taxdsl\"");
        assertThat(dsl).contains("version: \"" + MetaAst.CURRENT_VERSION + "\"");
        assertThat(dsl).contains("namespace: \"mission.secure-voice\"");
    }

    // ── Provenance model-to-AST mapping ───────────────────────────────────────

    @Test
    void mapSourceToAst() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        ArchitectureSource src = new ArchitectureSource("SRC-001", "REGULATION", "Test Regulation");
        src.setCanonicalIdentifier("VV-2026-001");
        src.setLanguage("de");
        model.getSources().add(src);

        DocumentAst doc = mapper.toDocument(model, "test");
        String dsl = serializer.serialize(doc);

        assertThat(dsl).contains("source SRC-001 {");
        assertThat(dsl).contains("type: REGULATION;");
        assertThat(dsl).contains("title: \"Test Regulation\"");
        assertThat(dsl).contains("canonicalIdentifier: \"VV-2026-001\"");
        assertThat(dsl).contains("language: \"de\"");
    }

    @Test
    void mapRequirementSourceLinkToAst() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        ArchitectureRequirementSourceLink rsl = new ArchitectureRequirementSourceLink(
                "RSL-001", "REQ-001", "SRC-001");
        rsl.setLinkType("EXTRACTED_FROM");
        rsl.setConfidence(0.91);
        rsl.setNote("Parsed from regulation");
        model.getRequirementSourceLinks().add(rsl);

        DocumentAst doc = mapper.toDocument(model, "test");
        String dsl = serializer.serialize(doc);

        assertThat(dsl).contains("requirementSourceLink RSL-001 {");
        assertThat(dsl).contains("requirement: \"REQ-001\"");
        assertThat(dsl).contains("source: \"SRC-001\"");
        assertThat(dsl).contains("linkType: \"EXTRACTED_FROM\"");
        assertThat(dsl).contains("confidence: 0.91;");
    }

    @Test
    void fullRoundtripWithProvenance() {
        CanonicalArchitectureModel original = new CanonicalArchitectureModel();
        original.getElements().add(new ArchitectureElement("CP-1023", "Capability", "Cap One", "Desc", "CP"));
        original.getRequirements().add(new ArchitectureRequirement("REQ-001", "Req One", "Req text"));

        ArchitectureSource src = new ArchitectureSource("SRC-001", "REGULATION", "Test Regulation");
        original.getSources().add(src);

        ArchitectureSourceVersion sv = new ArchitectureSourceVersion("SRCV-001", "SRC-001");
        sv.setVersionLabel("2026-04");
        original.getSourceVersions().add(sv);

        ArchitectureRequirementSourceLink rsl = new ArchitectureRequirementSourceLink(
                "RSL-001", "REQ-001", "SRC-001");
        rsl.setSourceVersionId("SRCV-001");
        rsl.setLinkType("EXTRACTED_FROM");
        rsl.setConfidence(0.91);
        original.getRequirementSourceLinks().add(rsl);

        // Model → AST → DSL → AST → Model
        DocumentAst doc = mapper.toDocument(original, "test");
        String dsl = serializer.serialize(doc);
        DocumentAst reparsed = parser.parse(dsl);
        CanonicalArchitectureModel restored = astToModel.map(reparsed);

        assertThat(restored.getElements()).hasSize(1);
        assertThat(restored.getRequirements()).hasSize(1);
        assertThat(restored.getSources()).hasSize(1);
        assertThat(restored.getSourceVersions()).hasSize(1);
        assertThat(restored.getRequirementSourceLinks()).hasSize(1);

        assertThat(restored.getSources().get(0).getId()).isEqualTo("SRC-001");
        assertThat(restored.getSources().get(0).getSourceType()).isEqualTo("REGULATION");
        assertThat(restored.getRequirementSourceLinks().get(0).getConfidence()).isEqualTo(0.91);
    }
}
