package com.nato.taxonomy.dsl;

import com.nato.taxonomy.dsl.ast.DocumentAst;
import com.nato.taxonomy.dsl.mapper.AstToModelMapper;
import com.nato.taxonomy.dsl.mapper.ModelToAstMapper;
import com.nato.taxonomy.dsl.model.*;
import com.nato.taxonomy.dsl.parser.TaxDslParser;
import com.nato.taxonomy.dsl.serializer.TaxDslSerializer;
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
        ArchitectureElement el = new ArchitectureElement("CP-1001", "Capability", "Test", "Description", "CP");
        el.setExtensions(Map.of("x-owner", "CIS"));
        model.getElements().add(el);

        DocumentAst doc = mapper.toDocument(model, "test");
        String dsl = serializer.serialize(doc);

        assertThat(dsl).contains("element CP-1001 type Capability");
        assertThat(dsl).contains("title \"Test\"");
        assertThat(dsl).contains("description \"Description\"");
        assertThat(dsl).contains("x-owner \"CIS\"");
    }

    @Test
    void mapRelationToAst() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        ArchitectureRelation rel = new ArchitectureRelation("SRV-2008", "SUPPORTS", "BP-1040");
        rel.setStatus("proposed");
        rel.setConfidence(0.76);
        model.getRelations().add(rel);

        DocumentAst doc = mapper.toDocument(model, "test");
        String dsl = serializer.serialize(doc);

        assertThat(dsl).contains("relation SRV-2008 SUPPORTS BP-1040");
        assertThat(dsl).contains("status proposed");
        assertThat(dsl).contains("confidence 0.76");
    }

    @Test
    void mapViewToAst() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        ArchitectureView view = new ArchitectureView("overview", "Architecture Overview");
        view.setIncludes(List.of("CP-1001", "BP-1040"));
        view.setLayout("layered");
        model.getViews().add(view);

        DocumentAst doc = mapper.toDocument(model, "test");
        String dsl = serializer.serialize(doc);

        assertThat(dsl).contains("view overview");
        assertThat(dsl).contains("title \"Architecture Overview\"");
        assertThat(dsl).contains("include \"CP-1001\"");
        assertThat(dsl).contains("include \"BP-1040\"");
        assertThat(dsl).contains("layout layered");
    }

    @Test
    void fullRoundtripModelToAstToModel() {
        // Build a model
        CanonicalArchitectureModel original = new CanonicalArchitectureModel();
        original.getElements().add(new ArchitectureElement("CP-1001", "Capability", "Cap One", "Desc", "CP"));
        original.getElements().add(new ArchitectureElement("BP-1040", "Process", "Proc One", "Desc", "BP"));

        ArchitectureRelation rel = new ArchitectureRelation("CP-1001", "REALIZES", "BP-1040");
        rel.setStatus("accepted");
        rel.setConfidence(0.83);
        original.getRelations().add(rel);

        original.getRequirements().add(new ArchitectureRequirement("REQ-001", "Req One", "Req text"));

        RequirementMapping mapping = new RequirementMapping("REQ-001", "CP-1001");
        mapping.setScore(0.92);
        mapping.setSource("llm");
        original.getMappings().add(mapping);

        ArchitectureView view = new ArchitectureView("overview", "Overview");
        view.setIncludes(List.of("CP-1001", "BP-1040"));
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

        // Verify content
        assertThat(restored.getElements().get(0).getId()).isEqualTo("CP-1001");
        assertThat(restored.getRelations().get(0).getRelationType()).isEqualTo("REALIZES");
        assertThat(restored.getRequirements().get(0).getId()).isEqualTo("REQ-001");
        assertThat(restored.getMappings().get(0).getScore()).isEqualTo(0.92);
        assertThat(restored.getViews().get(0).getIncludes()).containsExactly("CP-1001", "BP-1040");
    }

    @Test
    void mapEvidenceToAst() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        ArchitectureEvidence ev = new ArchitectureEvidence("EV-001");
        ev.setForRelationSource("SRV-2008");
        ev.setForRelationType("SUPPORTS");
        ev.setForRelationTarget("BP-1040");
        ev.setEvidenceType("LLM");
        ev.setModel("gpt-4.1-mini");
        ev.setConfidence(0.76);
        ev.setSummary("Service supports process.");
        model.getEvidence().add(ev);

        DocumentAst doc = mapper.toDocument(model, "test");
        String dsl = serializer.serialize(doc);

        assertThat(dsl).contains("evidence EV-001 for relation SRV-2008 SUPPORTS BP-1040");
        assertThat(dsl).contains("type LLM");
        assertThat(dsl).contains("model \"gpt-4.1-mini\"");
        assertThat(dsl).contains("confidence 0.76");
    }

    @Test
    void metaBlockIncluded() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        model.getElements().add(new ArchitectureElement("CP-1001", "Capability", "Test", null, null));

        DocumentAst doc = mapper.toDocument(model, "mission.secure-voice");
        String dsl = serializer.serialize(doc);

        assertThat(dsl).contains("meta");
        assertThat(dsl).contains("language \"taxdsl\"");
        assertThat(dsl).contains("version \"1.0\"");
        assertThat(dsl).contains("namespace \"mission.secure-voice\"");
    }
}
