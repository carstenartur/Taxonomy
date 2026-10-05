package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.catalog.service.AppInitializationStateService;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.search.LocalEmbeddingIndexRebuilder;
import com.taxonomy.search.LocalOnnxIndexInitializer;
import com.taxonomy.search.config.CatalogueWorkerIndexConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CatalogueWorkerIsolationTest {
    @Test
    void duplicateShardsFailStartupAfterWhitespaceNormalization() {
        for (String configured : List.of("CP,CP", "CP, CP", " CP ,IP,CP ")) {
            assertThatThrownBy(() -> new CatalogueRuntimePolicy("worker", configured))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void legacyBlankOrNullShardConfigurationMeansAllEightRoots() {
        for (String configured : new String[] { null, "", "  " }) {
            var policy = new CatalogueRuntimePolicy("worker", configured);
            for (CatalogueRoot root : CatalogueRoot.values()) {
                policy.requireConfiguredRoots(java.util.Set.of(root.name()));
            }
        }
    }

    @Test
    void workerStartsReadyWithoutReadingCatalogueOverlayOrEmbeddingIndexes() {
        var nodes = mock(TaxonomyNodeRepository.class);
        var relations = mock(TaxonomyRelationRepository.class);
        var overlay = mock(CatalogueOverlayService.class);
        var embedding = mock(LocalEmbeddingService.class);
        var rebuilder = mock(LocalEmbeddingIndexRebuilder.class);
        var state = new AppInitializationStateService();
        var worker = new CatalogueRuntimePolicy("worker", "CP");
        var taxonomy = new TaxonomyService(nodes, relations, state);
        ReflectionTestUtils.setField(taxonomy, "catalogueRuntimePolicy", worker);
        ReflectionTestUtils.setField(taxonomy, "catalogueOverlayService", overlay);
        ReflectionTestUtils.setField(taxonomy, "asyncInit", true);
        var initializer = new LocalOnnxIndexInitializer(embedding, state, rebuilder, "LOCAL_ONNX");
        ReflectionTestUtils.setField(initializer, "catalogueRuntimePolicy", worker);

        taxonomy.initOnStartup();
        taxonomy.onApplicationReady(null);
        initializer.initializeLocalOnnxIndex();

        assertThat(taxonomy.isInitialized()).isTrue();
        assertThat(taxonomy.getInitStatus()).isEqualTo("ready");
        assertThat(initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.DISABLED);
        assertThatThrownBy(taxonomy::getFullTree).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> taxonomy.getAssessmentContexts(List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CatalogueSnapshotService(nodes, overlay, worker, CatalogueSnapshotServiceTest.sources(null))
                .captureRoot(new CatalogueSourceIdentity("repo", null, "branch", "commit"), "CP"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(nodes, relations, overlay, embedding, rebuilder);
    }

    @Test
    void workerDisablesHibernateSearchBeforeItCanOpenUnrelatedIndexes() {
        new ApplicationContextRunner().withUserConfiguration(CatalogueWorkerIndexConfiguration.class)
                .withPropertyValues("taxonomy.analysis.runtime-role=worker")
                .run(context -> {
                    Map<String, Object> properties = new HashMap<>();
                    properties.put("hibernate.search.enabled", true);
                    context.getBean(HibernatePropertiesCustomizer.class).customize(properties);
                    assertThat(properties).containsEntry("hibernate.search.enabled", false);
                });
    }

    @Test
    void localAndCoordinatorRolesKeepTheirExistingIndexes() {
        for (String role : List.of("all", "coordinator")) {
            new ApplicationContextRunner().withUserConfiguration(CatalogueWorkerIndexConfiguration.class)
                    .withPropertyValues("taxonomy.analysis.runtime-role=" + role)
                    .run(context -> assertThat(context).doesNotHaveBean(HibernatePropertiesCustomizer.class));
        }
        assertThatThrownBy(() -> new CatalogueRuntimePolicy("worker", "CP,unknown"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
