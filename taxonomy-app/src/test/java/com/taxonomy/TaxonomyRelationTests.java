package com.taxonomy;

import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.repository.RelationDecisionProjectionCheckpointRepository;
import com.taxonomy.relations.repository.RelationDecisionProjectionRepository;
import com.taxonomy.relations.repository.RelationProjectionRecoveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class TaxonomyRelationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaxonomyRelationService relationService;

    @Autowired
    private TaxonomyRelationRepository relationRepository;

    @Autowired
    private RelationDecisionProjectionRepository projectionRepository;

    @Autowired
    private RelationDecisionProjectionCheckpointRepository checkpointRepository;

    @Autowired
    private RelationProjectionRecoveryRepository recoveryRepository;

    @Autowired
    private TaxonomyNodeRepository nodeRepository;

    @Autowired
    private TaxonomyService taxonomyService;

    @BeforeEach
    void cleanRelationReadModels() {
        recoveryRepository.deleteAll();
        checkpointRepository.deleteAll();
        projectionRepository.deleteAll();
        relationRepository.deleteAll();
    }

    @Test
    void contextLoadsWithRelationService() {
        assertThat(relationService).isNotNull();
        assertThat(nodeRepository).isNotNull();
    }

    @Test
    void initialLegacyRelationCountIsZero() {
        assertThat(relationService.countRelations()).isEqualTo(0);
    }

    @Test
    void legacyCompatibilityServiceStillPersistsAndMapsRows() {
        TaxonomyRelationDto dto = relationService.createRelation(
                "BP",
                "CP",
                RelationType.SUPPORTS,
                "BP supports CP",
                "test");

        assertThat(dto.getId()).isNotNull();
        assertThat(dto.getSourceCode()).isEqualTo("BP");
        assertThat(dto.getTargetCode()).isEqualTo("CP");
        assertThat(dto.getRelationType()).isEqualTo("SUPPORTS");
        assertThat(dto.getProvenance()).isEqualTo("test");
        assertThat(relationService.countRelations()).isEqualTo(1);
    }

    @Test
    void legacyCompatibilityQueriesRemainAvailableDuringMigrationFallback() {
        relationService.createRelation(
                "BP", "CP", RelationType.SUPPORTS, null, "test");
        relationService.createRelation(
                "CR", "BP", RelationType.DEPENDS_ON, null, "test");
        relationService.createRelation(
                "CP", "CR", RelationType.REALIZES, null, "test");

        List<TaxonomyRelationDto> nodeRelations =
                relationService.getRelationsForNode("BP");
        List<TaxonomyRelationDto> supports =
                relationService.getRelationsByType(RelationType.SUPPORTS);

        assertThat(nodeRelations).hasSize(2);
        assertThat(supports).singleElement()
                .extracting(TaxonomyRelationDto::getRelationType)
                .isEqualTo("SUPPORTS");
    }

    @Test
    void legacyCompatibilityDeleteAndDuplicateGuardRemainIntact() {
        TaxonomyRelationDto dto = relationService.createRelation(
                "BP", "CP", RelationType.SUPPORTS, null, "test");
        assertThatThrownBy(() -> relationService.createRelation(
                "BP", "CP", RelationType.SUPPORTS, null, "test"))
                .isInstanceOf(Exception.class);

        relationService.deleteRelation(dto.getId());

        assertThat(relationService.countRelations()).isZero();
    }

    @Test
    void productReadCountUsesObservableLegacyFallbackBeforeFirstBuild()
            throws Exception {
        mockMvc.perform(get("/api/relations/count")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "X-Taxonomy-Relation-Read-Model",
                        "LEGACY_FALLBACK"))
                .andExpect(header().string(
                        "X-Taxonomy-Relation-Projection-State",
                        "NOT_BUILT"))
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void productReadAllUsesTheSameObservableFallback() throws Exception {
        mockMvc.perform(get("/api/relations")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "X-Taxonomy-Relation-Read-Model",
                        "LEGACY_FALLBACK"))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void typeAndNodeProductReadsFilterOneFallbackSnapshot() throws Exception {
        relationService.createRelation(
                "BP", "CP", RelationType.SUPPORTS, null, "test");
        relationService.createRelation(
                "CP", "CR", RelationType.REALIZES, null, "test");
        relationService.createRelation(
                "CR", "BP", RelationType.DEPENDS_ON, null, "test");

        mockMvc.perform(get("/api/relations")
                        .param("type", "SUPPORTS")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].relationType")
                        .value("SUPPORTS"));
        mockMvc.perform(get("/api/node/BP/relations")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void dbFirstWriteEndpointsAreExplicitlyRetired() throws Exception {
        mockMvc.perform(post("/api/relations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceCode\":\"BP\",\"targetCode\":\"CP\",\"relationType\":\"SUPPORTS\"}"))
                .andExpect(status().isGone());
        mockMvc.perform(delete("/api/relations/42"))
                .andExpect(status().isGone());
    }

    @Test
    void relationDtoMappingIsCorrect() {
        TaxonomyRelationDto dto = relationService.createRelation(
                "BP",
                "CP",
                RelationType.SUPPORTS,
                "A description",
                "manual");

        assertThat(dto.getSourceCode()).isEqualTo("BP");
        assertThat(dto.getSourceName()).isNotNull();
        assertThat(dto.getTargetCode()).isEqualTo("CP");
        assertThat(dto.getTargetName()).isNotNull();
        assertThat(dto.getRelationType()).isEqualTo("SUPPORTS");
        assertThat(dto.getDescription()).isEqualTo("A description");
        assertThat(dto.getProvenance()).isEqualTo("manual");
        assertThat(dto.isBidirectional()).isFalse();
    }

    @Test
    void nodeDtoContainsRelationFields() {
        var tree = taxonomyService.getFullTree();
        assertThat(tree).isNotEmpty();
        var root = tree.getFirst();
        assertThat(root.getOutgoingRelations()).isNotNull();
        assertThat(root.getIncomingRelations()).isNotNull();
    }

    @Test
    void missingRelationsSheetDoesNotBreakLoader() {
        assertThat(taxonomyService.getFullTree()).hasSize(8);
    }
}
