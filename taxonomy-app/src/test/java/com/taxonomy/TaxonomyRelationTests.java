package com.taxonomy;

import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.model.TaxonomyNode;
import com.taxonomy.model.TaxonomyRelation;
import com.taxonomy.repository.TaxonomyNodeRepository;
import com.taxonomy.repository.TaxonomyRelationRepository;
import com.taxonomy.service.TaxonomyRelationService;
import com.taxonomy.service.TaxonomyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.security.test.context.support.WithMockUser;

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
    private TaxonomyNodeRepository nodeRepository;

    @Autowired
    private TaxonomyService taxonomyService;

    @BeforeEach
    void cleanRelations() {
        relationRepository.deleteAll();
    }

    @Test
    void contextLoadsWithRelationService() {
        assertThat(relationService).isNotNull();
    }

    @Test
    void initialRelationCountIsZero() {
        assertThat(relationService.countRelations()).isEqualTo(0);
    }

    @Test
    void createRelationPersistsAndReturnsDto() {
        TaxonomyRelationDto dto = relationService.createRelation("BP", "CP",
                RelationType.SUPPORTS, "BP supports CP", "test");
        assertThat(dto).isNotNull();
        assertThat(dto.getId()).isNotNull();
        assertThat(dto.getSourceCode()).isEqualTo("BP");
        assertThat(dto.getTargetCode()).isEqualTo("CP");
        assertThat(dto.getRelationType()).isEqualTo("SUPPORTS");
        assertThat(dto.getProvenance()).isEqualTo("test");
        assertThat(relationService.countRelations()).isEqualTo(1);
    }

    @Test
    void getRelationsForNodeReturnsIncomingAndOutgoing() {
        relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");
        relationService.createRelation("CR", "BP", RelationType.DEPENDS_ON, null, "test");

        List<TaxonomyRelationDto> relations = relationService.getRelationsForNode("BP");
        assertThat(relations).hasSize(2);
    }

    @Test
    void getRelationsByTypeFiltersCorrectly() {
        relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");
        relationService.createRelation("CP", "CR", RelationType.REALIZES, null, "test");

        List<TaxonomyRelationDto> supports = relationService.getRelationsByType(RelationType.SUPPORTS);
        assertThat(supports).hasSize(1);
        assertThat(supports.get(0).getRelationType()).isEqualTo("SUPPORTS");

        List<TaxonomyRelationDto> realizes = relationService.getRelationsByType(RelationType.REALIZES);
        assertThat(realizes).hasSize(1);
        assertThat(realizes.get(0).getRelationType()).isEqualTo("REALIZES");
    }

    @Test
    void deleteRelationRemovesIt() {
        TaxonomyRelationDto dto = relationService.createRelation("BP", "CP",
                RelationType.SUPPORTS, null, "test");
        assertThat(relationService.countRelations()).isEqualTo(1);

        relationService.deleteRelation(dto.getId());
        assertThat(relationService.countRelations()).isEqualTo(0);
    }

    @Test
    void duplicateRelationIsRejected() {
        relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");
        assertThatThrownBy(() -> {
            relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");
        }).isInstanceOf(Exception.class);
    }

    @Test
    void relationsApiCountEndpointReturnsZero() throws Exception {
        mockMvc.perform(get("/api/relations/count").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void relationsApiGetAllReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/relations").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void relationsApiPostCreatesRelation() throws Exception {
        mockMvc.perform(post("/api/relations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceCode\":\"BP\",\"targetCode\":\"CP\",\"relationType\":\"SUPPORTS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceCode").value("BP"))
                .andExpect(jsonPath("$.targetCode").value("CP"))
                .andExpect(jsonPath("$.relationType").value("SUPPORTS"));
    }

    @Test
    void relationsApiPostReturnsBadRequestForMissingFields() throws Exception {
        mockMvc.perform(post("/api/relations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceCode\":\"BP\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void relationsApiPostReturnsBadRequestForUnknownType() throws Exception {
        mockMvc.perform(post("/api/relations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceCode\":\"BP\",\"targetCode\":\"CP\",\"relationType\":\"UNKNOWN_TYPE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void relationsApiPostReturnsBadRequestForUnknownNode() throws Exception {
        mockMvc.perform(post("/api/relations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceCode\":\"NONEXISTENT\",\"targetCode\":\"CP\",\"relationType\":\"SUPPORTS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void relationsApiGetByTypeFilters() throws Exception {
        mockMvc.perform(post("/api/relations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceCode\":\"BP\",\"targetCode\":\"CP\",\"relationType\":\"SUPPORTS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/relations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceCode\":\"CP\",\"targetCode\":\"CR\",\"relationType\":\"REALIZES\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/relations").param("type", "SUPPORTS").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].relationType").value("SUPPORTS"));
    }

    @Test
    void relationsApiGetForNodeReturnsIncomingAndOutgoing() throws Exception {
        mockMvc.perform(post("/api/relations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceCode\":\"BP\",\"targetCode\":\"CP\",\"relationType\":\"SUPPORTS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/relations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceCode\":\"CR\",\"targetCode\":\"BP\",\"relationType\":\"DEPENDS_ON\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/node/BP/relations").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void relationsApiDeleteRemovesRelation() throws Exception {
        String createResponse = mockMvc.perform(post("/api/relations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceCode\":\"BP\",\"targetCode\":\"CP\",\"relationType\":\"SUPPORTS\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Extract id from response manually using simple string search
        int idStart = createResponse.indexOf("\"id\":") + 5;
        int idEnd = createResponse.indexOf(",", idStart);
        if (idEnd < 0) idEnd = createResponse.indexOf("}", idStart);
        long id = Long.parseLong(createResponse.substring(idStart, idEnd).trim());

        mockMvc.perform(delete("/api/relations/" + id))
                .andExpect(status().isNoContent());

        assertThat(relationService.countRelations()).isEqualTo(0);
    }

    @Test
    void relationDtoMappingIsCorrect() {
        TaxonomyRelationDto dto = relationService.createRelation("BP", "CP",
                RelationType.SUPPORTS, "A description", "manual");
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
        // After loading, the DTO should have outgoing/incoming relation lists (empty by default)
        var tree = taxonomyService.getFullTree();
        assertThat(tree).isNotEmpty();
        var root = tree.get(0);
        assertThat(root.getOutgoingRelations()).isNotNull();
        assertThat(root.getIncomingRelations()).isNotNull();
    }

    @Test
    void missingRelationsSheetDoesNotBreakLoader() {
        // The taxonomy was already loaded without a Relations sheet — all 8 roots should exist
        var tree = taxonomyService.getFullTree();
        assertThat(tree).hasSize(8);
    }
}
