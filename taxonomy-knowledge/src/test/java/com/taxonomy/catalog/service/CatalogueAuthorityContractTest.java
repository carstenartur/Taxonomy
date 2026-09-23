package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.dto.CatalogueNodeOrigin;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.model.RelationType;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.SystemRepositoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogueAuthorityContractTest {

    @Test
    void validSourceParentWinsOverOverlayClassificationParent(@TempDir Path dir) throws Exception {
        Map<String, TaxonomyNode> nodes = new LinkedHashMap<>();
        nodes.put("IP", node("IP", null, "IP", 0, "root"));
        nodes.put("IP-A", node("IP-A", "IP", "IP", 1, "Official A"));
        nodes.put("IP-B", node("IP-B", "IP", "IP", 1, "Official B"));
        nodes.put("IP-1", node("IP-1", "IP-A", "IP", 2, "Product"));

        CatalogueOverlayService service = overlay(dir, """
                {
                  "schemaVersion": 2,
                  "mode": "OVERLAY",
                  "baseCatalogue": "base.xlsx",
                  "mappingVersion": "test",
                  "nodePatches": [{
                    "code": "IP-1",
                    "expectedTitle": "Product",
                    "expectedState": "draft",
                    "parentCode": "IP-B",
                    "analysisRole": "PRODUCT",
                    "confidence": 0.8,
                    "reviewRequired": true,
                    "justification": "navigation suggestion"
                  }]
                }
                """);

        service.applyAndValidate(nodes, Map.of(), dir.resolve("base.xlsx").toUri().toString());

        assertThat(nodes.get("IP-1").getParentCode())
                .as("a valid official source parent must not be replaced by local navigation")
                .isEqualTo("IP-A");
    }

    @Test
    void onlyInformationProductGapsMayReceiveSupplementalParents(@TempDir Path dir) throws Exception {
        Map<String, TaxonomyNode> nodes = new LinkedHashMap<>();
        nodes.put("UA", node("UA", null, "UA", 0, "root"));
        nodes.put("UA-A", node("UA-A", "UA", "UA", 1, "Applications"));
        nodes.put("UA-1", node("UA-1", null, "UA", 1, "Client"));

        CatalogueOverlayService service = overlay(dir, """
                {
                  "schemaVersion": 2,
                  "mode": "OVERLAY",
                  "baseCatalogue": "base.xlsx",
                  "mappingVersion": "test",
                  "nodePatches": [{
                    "code": "UA-1",
                    "expectedTitle": "Client",
                    "expectedState": "draft",
                    "parentCode": "UA-A",
                    "analysisRole": "PRODUCT",
                    "confidence": 0.8,
                    "reviewRequired": true,
                    "justification": "must not supplement non-IP"
                  }]
                }
                """);

        assertThatThrownBy(() -> service.applyAndValidate(
                nodes, Map.of(), dir.resolve("base.xlsx").toUri().toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IP");
    }

    @Test
    void originalUnresolvedParentReferenceRemainsInspectableAfterSupplement(@TempDir Path dir) throws Exception {
        Map<String, TaxonomyNode> nodes = new LinkedHashMap<>();
        nodes.put("IP", node("IP", null, "IP", 0, "root"));
        nodes.put("IP-A", node("IP-A", "IP", "IP", 1, "Official A"));
        nodes.put("IP-1", node("IP-1", "missing-source-parent", "IP", 2, "Product"));

        CatalogueOverlayService service = overlay(dir, """
                {
                  "schemaVersion": 2,
                  "mode": "OVERLAY",
                  "baseCatalogue": "base.xlsx",
                  "mappingVersion": "test",
                  "nodePatches": [{
                    "code": "IP-1",
                    "expectedTitle": "Product",
                    "expectedState": "draft",
                    "parentCode": "IP-A",
                    "analysisRole": "PRODUCT",
                    "confidence": 0.8,
                    "reviewRequired": true,
                    "justification": "repair unresolved source parent"
                  }]
                }
                """);

        service.applyAndValidate(nodes, Map.of(), dir.resolve("base.xlsx").toUri().toString());

        assertThat(nodes.get("IP-1").getSourceParentReference()).isEqualTo("missing-source-parent");
        assertThat(nodes.get("IP-1").getParentCode()).isEqualTo("IP-A");
    }

    @Test
    void persistedGapNeverPromotesSupplementalParentToOfficialSource(@TempDir Path dir) throws Exception {
        Map<String, TaxonomyNode> nodes = new LinkedHashMap<>();
        nodes.put("IP", node("IP", null, "IP", 0, "root"));
        nodes.put("IP-A", node("IP-A", "IP", "IP", 1, "Official A"));
        TaxonomyNode product = node("IP-1", "IP-A", "IP", 2, "Product");
        product.setSourceParentReference(null);
        product.setSourceParentCode(null);
        product.setSourceOrder(42);
        nodes.put("IP-1", product);

        CatalogueOverlayService service = overlay(dir, """
                {
                  "schemaVersion": 2,
                  "mode": "OVERLAY",
                  "baseCatalogue": "base.xlsx",
                  "mappingVersion": "test",
                  "nodePatches": [{
                    "code": "IP-1",
                    "expectedTitle": "Product",
                    "expectedState": "draft",
                    "parentCode": "IP-A",
                    "analysisRole": "PRODUCT",
                    "confidence": 0.8,
                    "reviewRequired": true,
                    "justification": "persisted navigation repair"
                  }]
                }
                """);

        service.applyAndValidate(nodes, Map.of(), dir.resolve("base.xlsx").toUri().toString());

        assertThat(product.getSourceParentReference()).isNull();
        assertThat(product.getSourceParentCode()).isNull();
        assertThat(product.getParentCode()).isEqualTo("IP-A");
        assertThat(service.hasParentPatch("IP-1"))
                .as("supplemental navigation must remain visibly non-source after restart")
                .isTrue();
    }

    @Test
    void localNavigationNodeCannotBeArchitectureRelationEndpoint() throws Exception {
        TaxonomyNode local = node("local:ip:test", null, "IP", 1, "Local helper");
        TaxonomyNode official = node("IP-1", null, "IP", 1, "Official product");
        local.setCatalogueOrigin(CatalogueNodeOrigin.LOCAL_NAVIGATION);

        TaxonomyNodeRepository nodes = mock(TaxonomyNodeRepository.class);
        TaxonomyRelationRepository relations = mock(TaxonomyRelationRepository.class);
        when(nodes.findByCode(local.getCode())).thenReturn(Optional.of(local));
        when(nodes.findByCode(official.getCode())).thenReturn(Optional.of(official));
        when(relations.findCentralByRepositoryAndSourceTargetType(
                "repo", local.getCode(), official.getCode(), RelationType.RELATED_TO))
                .thenReturn(List.of());
        when(relations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TaxonomyRelationService service = new TaxonomyRelationService(
                relations, nodes, mock(SystemRepositoryService.class));

        assertThatThrownBy(() -> service.createRelationInContext(
                local.getCode(), official.getCode(), RelationType.RELATED_TO,
                "invalid edge", "manual", RepositoryContext.centralWrite("repo", "main", "user")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("official");
    }

    @Test
    void dtoChildrenFollowOriginalSourceOrder() throws Exception {
        TaxonomyNode parent = node("IP", null, "IP", 0, "Information Products");
        TaxonomyNode second = node("IP-2", "IP", "IP", 1, "Second");
        second.setSortOrder(2);
        TaxonomyNode first = node("IP-1", "IP", "IP", 1, "First");
        first.setSortOrder(1);
        parent.setChildren(List.of(second, first));

        TaxonomyService service = new TaxonomyService(
                mock(TaxonomyNodeRepository.class),
                mock(TaxonomyRelationRepository.class),
                mock(AppInitializationStateService.class));
        Field overlay = TaxonomyService.class.getDeclaredField("catalogueOverlayService");
        overlay.setAccessible(true);
        overlay.set(service, new CatalogueOverlayService(
                new ObjectMapper(), new DefaultResourceLoader(), false, "unused"));

        assertThat(service.toDto(parent).getChildren())
                .extracting(dto -> dto.getCode())
                .containsExactly("IP-1", "IP-2");
    }

    private static CatalogueOverlayService overlay(Path dir, String json) throws Exception {
        Files.writeString(dir.resolve("overlay.json"), json);
        Files.write(dir.resolve("base.xlsx"), new byte[0]);
        return new CatalogueOverlayService(
                new ObjectMapper(), new DefaultResourceLoader(), true,
                dir.resolve("overlay.json").toUri().toString());
    }

    private static TaxonomyNode node(
            String code, String parent, String root, int level, String title) {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode(code);
        node.setParentCode(parent);
        node.setTaxonomyRoot(root);
        node.setLevel(level);
        node.setNameEn(title);
        node.setState("draft");
        return node;
    }

}
