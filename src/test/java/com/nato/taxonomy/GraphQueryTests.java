package com.nato.taxonomy;

import com.nato.taxonomy.dto.*;
import com.nato.taxonomy.model.RelationType;
import com.nato.taxonomy.repository.TaxonomyRelationRepository;
import com.nato.taxonomy.service.ArchitectureGraphQueryService;
import com.nato.taxonomy.service.TaxonomyRelationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class GraphQueryTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArchitectureGraphQueryService graphQueryService;

    @Autowired
    private TaxonomyRelationService relationService;

    @Autowired
    private TaxonomyRelationRepository relationRepository;

    @BeforeEach
    void cleanRelations() {
        relationRepository.deleteAll();
    }

    // ── Requirement Impact Tests ────────────────────────────────────────────

    @Test
    void requirementImpactWithNoScoresReturnsNote() {
        RequirementImpactView view = graphQueryService.findImpactForRequirement(
                Map.of(), "test requirement", 2);

        assertThat(view.getBusinessText()).isEqualTo("test requirement");
        assertThat(view.getMaxHops()).isEqualTo(2);
        assertThat(view.getImpactedElements()).isEmpty();
        assertThat(view.getNotes()).isNotEmpty();
    }

    @Test
    void requirementImpactWithNullScoresReturnsNote() {
        RequirementImpactView view = graphQueryService.findImpactForRequirement(
                null, "test requirement", 2);

        assertThat(view.getNotes()).isNotEmpty();
        assertThat(view.getImpactedElements()).isEmpty();
    }

    @Test
    void requirementImpactFindsAnchorAndPropagatedNodes() {
        // Create relation: BP → CP via SUPPORTS
        relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");

        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("BP", 91);

        RequirementImpactView view = graphQueryService.findImpactForRequirement(
                scores, "secure voice communications", 2);

        assertThat(view.getImpactedElements()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(view.getImpactedElements().stream()
                .map(ImpactElement::getNodeCode).toList())
                .contains("BP", "CP");
        assertThat(view.getTotalElements()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void requirementImpactRespectsMaxHops() {
        // Chain: BP → CP → CR (2 hops)
        relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");
        relationService.createRelation("CP", "CR", RelationType.SUPPORTS, null, "test");

        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("BP", 91);

        // With maxHops=1, CR should NOT appear
        RequirementImpactView view1 = graphQueryService.findImpactForRequirement(scores, "test", 1);
        assertThat(view1.getImpactedElements().stream()
                .map(ImpactElement::getNodeCode).toList())
                .contains("BP", "CP")
                .doesNotContain("CR");

        // With maxHops=2, CR should appear
        RequirementImpactView view2 = graphQueryService.findImpactForRequirement(scores, "test", 2);
        assertThat(view2.getImpactedElements().stream()
                .map(ImpactElement::getNodeCode).toList())
                .contains("BP", "CP", "CR");
    }

    @Test
    void requirementImpactTracksRelationships() {
        relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");

        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("BP", 91);

        RequirementImpactView view = graphQueryService.findImpactForRequirement(scores, "test", 2);

        assertThat(view.getTraversedRelationships()).isNotEmpty();
        assertThat(view.getTraversedRelationships().get(0).getRelationType()).isEqualTo("SUPPORTS");
        assertThat(view.getTotalRelationships()).isGreaterThanOrEqualTo(1);
    }

    // ── Upstream Tests ──────────────────────────────────────────────────────

    @Test
    void upstreamFindsIncomingRelations() {
        // CP → BP via SUPPORTS means BP has CP as upstream
        relationService.createRelation("CP", "BP", RelationType.SUPPORTS, null, "test");

        GraphNeighborhoodView view = graphQueryService.findUpstream("BP", 2);

        assertThat(view.getOriginNodeCode()).isEqualTo("BP");
        assertThat(view.getDirection()).isEqualTo("UPSTREAM");
        assertThat(view.getNeighbors()).isNotEmpty();
        assertThat(view.getNeighbors().stream()
                .map(ImpactElement::getNodeCode).toList())
                .contains("CP");
    }

    @Test
    void upstreamDoesNotIncludeOutgoingRelations() {
        // BP → CP (BP is source, CP is target)
        // For upstream of BP, CP should NOT appear as a neighbor
        relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");

        GraphNeighborhoodView view = graphQueryService.findUpstream("BP", 2);

        assertThat(view.getNeighbors().stream()
                .map(ImpactElement::getNodeCode).toList())
                .doesNotContain("CP");
    }

    @Test
    void upstreamForNonExistentNodeReturnsNote() {
        GraphNeighborhoodView view = graphQueryService.findUpstream("NONEXISTENT", 2);

        assertThat(view.getNotes()).isNotEmpty();
        assertThat(view.getNeighbors()).isEmpty();
    }

    @Test
    void upstreamChainsMultipleHops() {
        // CR → CP → BP: upstream of BP should find CP (hop 1) and CR (hop 2)
        relationService.createRelation("CP", "BP", RelationType.SUPPORTS, null, "test");
        relationService.createRelation("CR", "CP", RelationType.SUPPORTS, null, "test");

        GraphNeighborhoodView view = graphQueryService.findUpstream("BP", 2);

        assertThat(view.getNeighbors().stream()
                .map(ImpactElement::getNodeCode).toList())
                .contains("CP", "CR");
    }

    // ── Downstream Tests ────────────────────────────────────────────────────

    @Test
    void downstreamFindsOutgoingRelations() {
        // BP → CP via SUPPORTS means CP is downstream of BP
        relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");

        GraphNeighborhoodView view = graphQueryService.findDownstream("BP", 2);

        assertThat(view.getOriginNodeCode()).isEqualTo("BP");
        assertThat(view.getDirection()).isEqualTo("DOWNSTREAM");
        assertThat(view.getNeighbors()).isNotEmpty();
        assertThat(view.getNeighbors().stream()
                .map(ImpactElement::getNodeCode).toList())
                .contains("CP");
    }

    @Test
    void downstreamDoesNotIncludeIncomingRelations() {
        // CP → BP (CP is source, BP is target)
        // For downstream of BP, CP should NOT appear
        relationService.createRelation("CP", "BP", RelationType.SUPPORTS, null, "test");

        GraphNeighborhoodView view = graphQueryService.findDownstream("BP", 2);

        assertThat(view.getNeighbors().stream()
                .map(ImpactElement::getNodeCode).toList())
                .doesNotContain("CP");
    }

    @Test
    void downstreamForNonExistentNodeReturnsNote() {
        GraphNeighborhoodView view = graphQueryService.findDownstream("NONEXISTENT", 2);

        assertThat(view.getNotes()).isNotEmpty();
        assertThat(view.getNeighbors()).isEmpty();
    }

    @Test
    void downstreamChainsMultipleHops() {
        // BP → CP → CR: downstream of BP should find CP (hop 1) and CR (hop 2)
        relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");
        relationService.createRelation("CP", "CR", RelationType.SUPPORTS, null, "test");

        GraphNeighborhoodView view = graphQueryService.findDownstream("BP", 2);

        assertThat(view.getNeighbors().stream()
                .map(ImpactElement::getNodeCode).toList())
                .contains("CP", "CR");
    }

    // ── Failure Impact Tests ────────────────────────────────────────────────

    @Test
    void failureImpactFindsDirectlyAffected() {
        // BP → CP via SUPPORTS: if BP fails, CP is directly affected
        relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");

        ChangeImpactView view = graphQueryService.findFailureImpact("BP", 3);

        assertThat(view.getFailedNodeCode()).isEqualTo("BP");
        assertThat(view.getDirectlyAffected()).isNotEmpty();
        assertThat(view.getDirectlyAffected().stream()
                .map(ImpactElement::getNodeCode).toList())
                .contains("CP");
    }

    @Test
    void failureImpactSplitsDirectAndIndirect() {
        // BP → CP → CR: direct = CP, indirect = CR
        relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");
        relationService.createRelation("CP", "CR", RelationType.SUPPORTS, null, "test");

        ChangeImpactView view = graphQueryService.findFailureImpact("BP", 3);

        assertThat(view.getDirectlyAffected().stream()
                .map(ImpactElement::getNodeCode).toList())
                .contains("CP");
        assertThat(view.getIndirectlyAffected().stream()
                .map(ImpactElement::getNodeCode).toList())
                .contains("CR");
        assertThat(view.getTotalAffected()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void failureImpactForNonExistentNodeReturnsNote() {
        ChangeImpactView view = graphQueryService.findFailureImpact("NONEXISTENT", 3);

        assertThat(view.getNotes()).isNotEmpty();
        assertThat(view.getDirectlyAffected()).isEmpty();
        assertThat(view.getIndirectlyAffected()).isEmpty();
    }

    @Test
    void failureImpactAlsoTraversesIncomingRelations() {
        // CP → BP: if BP fails, CP is also affected (upstream dependency)
        relationService.createRelation("CP", "BP", RelationType.SUPPORTS, null, "test");

        ChangeImpactView view = graphQueryService.findFailureImpact("BP", 3);

        assertThat(view.getDirectlyAffected().stream()
                .map(ImpactElement::getNodeCode).toList())
                .contains("CP");
    }

    @Test
    void failureImpactTracksRelationships() {
        relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");

        ChangeImpactView view = graphQueryService.findFailureImpact("BP", 3);

        assertThat(view.getTraversedRelationships()).isNotEmpty();
        assertThat(view.getTotalRelationships()).isGreaterThanOrEqualTo(1);
    }

    // ── MaxHops Clamping Tests ──────────────────────────────────────────────

    @Test
    void maxHopsClampedToMinimum1() {
        RequirementImpactView view = graphQueryService.findImpactForRequirement(
                Map.of("BP", 91), "test", 0);
        assertThat(view.getMaxHops()).isEqualTo(1);
    }

    @Test
    void maxHopsClampedToMaximum5() {
        RequirementImpactView view = graphQueryService.findImpactForRequirement(
                Map.of("BP", 91), "test", 10);
        assertThat(view.getMaxHops()).isEqualTo(5);
    }

    // ── API Integration Tests ───────────────────────────────────────────────

    @Test
    void requirementImpactEndpointReturnsOk() throws Exception {
        mockMvc.perform(post("/api/graph/impact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessText\":\"secure voice communications\"," +
                                "\"scores\":{\"BP\":91},\"maxHops\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessText").value("secure voice communications"))
                .andExpect(jsonPath("$.maxHops").value(2))
                .andExpect(jsonPath("$.impactedElements").isArray())
                .andExpect(jsonPath("$.traversedRelationships").isArray());
    }

    @Test
    void requirementImpactEndpointRejectsMissingBusinessText() throws Exception {
        mockMvc.perform(post("/api/graph/impact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":{\"BP\":91}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upstreamEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/api/graph/node/BP/upstream")
                        .param("maxHops", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originNodeCode").value("BP"))
                .andExpect(jsonPath("$.direction").value("UPSTREAM"))
                .andExpect(jsonPath("$.neighbors").isArray());
    }

    @Test
    void downstreamEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/api/graph/node/BP/downstream")
                        .param("maxHops", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originNodeCode").value("BP"))
                .andExpect(jsonPath("$.direction").value("DOWNSTREAM"))
                .andExpect(jsonPath("$.neighbors").isArray());
    }

    @Test
    void failureImpactEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/api/graph/node/BP/failure-impact")
                        .param("maxHops", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedNodeCode").value("BP"))
                .andExpect(jsonPath("$.maxHops").value(3))
                .andExpect(jsonPath("$.directlyAffected").isArray())
                .andExpect(jsonPath("$.indirectlyAffected").isArray());
    }

    @Test
    void upstreamEndpointDefaultsMaxHopsTo2() throws Exception {
        mockMvc.perform(get("/api/graph/node/BP/upstream"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxHops").value(2));
    }

    @Test
    void failureImpactEndpointDefaultsMaxHopsTo3() throws Exception {
        mockMvc.perform(get("/api/graph/node/BP/failure-impact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxHops").value(3));
    }

    // ── Relation Type Tests ─────────────────────────────────────────────────

    @Test
    void failureImpactDoesNotTraverseNonWhitelistedRelations() {
        relationService.createRelation("BP", "CP", RelationType.RELATED_TO, null, "test");

        ChangeImpactView view = graphQueryService.findFailureImpact("BP", 3);

        assertThat(view.getDirectlyAffected().stream()
                .map(ImpactElement::getNodeCode).toList())
                .doesNotContain("CP");
    }

    @Test
    void downstreamTraversesRealizesRelation() {
        relationService.createRelation("BP", "CP", RelationType.REALIZES, null, "test");

        GraphNeighborhoodView view = graphQueryService.findDownstream("BP", 2);

        assertThat(view.getNeighbors().stream()
                .map(ImpactElement::getNodeCode).toList())
                .contains("CP");
    }

    @Test
    void upstreamTraversesUsesRelation() {
        relationService.createRelation("CP", "BP", RelationType.USES, null, "test");

        GraphNeighborhoodView view = graphQueryService.findUpstream("BP", 2);

        assertThat(view.getNeighbors().stream()
                .map(ImpactElement::getNodeCode).toList())
                .contains("CP");
    }
}
