package com.taxonomy.dsl;

import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.model.*;
import com.taxonomy.dsl.parser.TaxDslParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AstToModelMapper}: DSL → Canonical Model mapping.
 */
class AstToModelMapperTest {

    private TaxDslParser parser;
    private AstToModelMapper mapper;

    @BeforeEach
    void setUp() {
        parser = new TaxDslParser();
        mapper = new AstToModelMapper();
    }

    @Test
    void mapElement() {
        String dsl = """
                element CP-1001 type Capability
                  title "Secure Communications"
                  description "Ability to communicate securely"
                  taxonomy "Capabilities"
                  x-owner "CIS"
                """;
        DocumentAst doc = parser.parse(dsl);
        CanonicalArchitectureModel model = mapper.map(doc);

        assertThat(model.getElements()).hasSize(1);
        ArchitectureElement el = model.getElements().get(0);
        assertThat(el.getId()).isEqualTo("CP-1001");
        assertThat(el.getType()).isEqualTo("Capability");
        assertThat(el.getTitle()).isEqualTo("Secure Communications");
        assertThat(el.getDescription()).isEqualTo("Ability to communicate securely");
        assertThat(el.getTaxonomy()).isEqualTo("Capabilities");
        assertThat(el.getExtensions()).containsEntry("x-owner", "CIS");
    }

    @Test
    void mapRelation() {
        String dsl = """
                relation SRV-2008 SUPPORTS BP-1040
                  status proposed
                  confidence 0.76
                  provenance "analysis"
                """;
        DocumentAst doc = parser.parse(dsl);
        CanonicalArchitectureModel model = mapper.map(doc);

        assertThat(model.getRelations()).hasSize(1);
        ArchitectureRelation rel = model.getRelations().get(0);
        assertThat(rel.getSourceId()).isEqualTo("SRV-2008");
        assertThat(rel.getRelationType()).isEqualTo("SUPPORTS");
        assertThat(rel.getTargetId()).isEqualTo("BP-1040");
        assertThat(rel.getStatus()).isEqualTo("proposed");
        assertThat(rel.getConfidence()).isEqualTo(0.76);
        assertThat(rel.getProvenance()).isEqualTo("analysis");
    }

    @Test
    void mapRequirement() {
        String dsl = """
                requirement REQ-001
                  title "Secure voice comms"
                  text "Provide secure voice communications"
                """;
        DocumentAst doc = parser.parse(dsl);
        CanonicalArchitectureModel model = mapper.map(doc);

        assertThat(model.getRequirements()).hasSize(1);
        ArchitectureRequirement req = model.getRequirements().get(0);
        assertThat(req.getId()).isEqualTo("REQ-001");
        assertThat(req.getTitle()).isEqualTo("Secure voice comms");
        assertThat(req.getText()).isEqualTo("Provide secure voice communications");
    }

    @Test
    void mapMapping() {
        String dsl = """
                mapping REQ-001 -> CP-1001
                  score 0.92
                  source "llm"
                """;
        DocumentAst doc = parser.parse(dsl);
        CanonicalArchitectureModel model = mapper.map(doc);

        assertThat(model.getMappings()).hasSize(1);
        RequirementMapping m = model.getMappings().get(0);
        assertThat(m.getRequirementId()).isEqualTo("REQ-001");
        assertThat(m.getElementId()).isEqualTo("CP-1001");
        assertThat(m.getScore()).isEqualTo(0.92);
        assertThat(m.getSource()).isEqualTo("llm");
    }

    @Test
    void mapView() {
        String dsl = """
                view overview
                  title "Overview"
                  include CP-1001
                  include BP-1040
                  layout layered
                """;
        DocumentAst doc = parser.parse(dsl);
        CanonicalArchitectureModel model = mapper.map(doc);

        assertThat(model.getViews()).hasSize(1);
        ArchitectureView view = model.getViews().get(0);
        assertThat(view.getId()).isEqualTo("overview");
        assertThat(view.getTitle()).isEqualTo("Overview");
        assertThat(view.getIncludes()).containsExactly("CP-1001", "BP-1040");
        assertThat(view.getLayout()).isEqualTo("layered");
    }

    @Test
    void mapEvidence() {
        String dsl = """
                evidence EV-001 for relation SRV-2008 SUPPORTS BP-1040
                  type LLM
                  model "gpt-4.1-mini"
                  confidence 0.76
                  summary "The service supports the process."
                """;
        DocumentAst doc = parser.parse(dsl);
        CanonicalArchitectureModel model = mapper.map(doc);

        assertThat(model.getEvidence()).hasSize(1);
        ArchitectureEvidence ev = model.getEvidence().get(0);
        assertThat(ev.getId()).isEqualTo("EV-001");
        assertThat(ev.getForRelationSource()).isEqualTo("SRV-2008");
        assertThat(ev.getForRelationType()).isEqualTo("SUPPORTS");
        assertThat(ev.getForRelationTarget()).isEqualTo("BP-1040");
        assertThat(ev.getEvidenceType()).isEqualTo("LLM");
        assertThat(ev.getModel()).isEqualTo("gpt-4.1-mini");
        assertThat(ev.getConfidence()).isEqualTo(0.76);
    }

    @Test
    void mapUnknownBlockTypesAreSkipped() {
        String dsl = """
                constraint CON-001
                  title "Max latency"
                
                element CP-1001 type Capability
                  title "Test"
                """;
        DocumentAst doc = parser.parse(dsl);
        CanonicalArchitectureModel model = mapper.map(doc);

        // Unknown block should not appear in model, only in AST
        assertThat(model.getElements()).hasSize(1);
        assertThat(model.getRelations()).isEmpty();
        assertThat(doc.getBlocks()).hasSize(2); // both blocks in AST
    }

    @Test
    void mapCompleteDocument() {
        String dsl = """
                meta
                  language "taxdsl"
                  version "1.0"
                  namespace "test"
                
                element CP-1001 type Capability
                  title "Cap One"
                
                element BP-1040 type Process
                  title "Process One"
                
                relation CP-1001 REALIZES BP-1040
                  status accepted
                  confidence 0.83
                
                requirement REQ-001
                  title "Req One"
                  text "Requirement text"
                
                mapping REQ-001 -> CP-1001
                  score 0.92
                  source "llm"
                
                view overview
                  title "Overview"
                  include CP-1001
                  include BP-1040
                  layout layered
                
                evidence EV-001 for relation CP-1001 REALIZES BP-1040
                  type LLM
                  confidence 0.83
                  summary "Direct realization."
                """;
        DocumentAst doc = parser.parse(dsl);
        CanonicalArchitectureModel model = mapper.map(doc);

        assertThat(model.getElements()).hasSize(2);
        assertThat(model.getRelations()).hasSize(1);
        assertThat(model.getRequirements()).hasSize(1);
        assertThat(model.getMappings()).hasSize(1);
        assertThat(model.getViews()).hasSize(1);
        assertThat(model.getEvidence()).hasSize(1);
    }
}
