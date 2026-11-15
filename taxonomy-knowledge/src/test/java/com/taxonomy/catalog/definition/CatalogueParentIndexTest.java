package com.taxonomy.catalog.definition;

import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogueParentIndexTest {
    @Test void decisionHierarchyIncludesUnadoptedDescendantsOfAnAdoptedTypedCode() {
        var overlays = new CatalogueOverlayService(new ObjectMapper(), new DefaultResourceLoader(), true, "unused");
        var index = new CatalogueParentIndex(null, overlays);
        var model = new CanonicalArchitectureModel();
        model.getElements().add(new ArchitectureElement("BR-1002", "BusinessRole", "Staff", "", null));
        var hierarchy = index.parentsFor(model, null, com.taxonomy.dsl.model.CatalogueDefinition.standard());
        assertThat(hierarchy).containsEntry("BR-1002", "BR-1057").containsEntry("BR-1081", "BR-1002");
    }

    @Test void restoresTheActualMedicalAdvisorPathForLegacyWorkingCopies() {
        var overlays = new CatalogueOverlayService(new ObjectMapper(), new DefaultResourceLoader(), true, "unused");
        var index = new CatalogueParentIndex(null, overlays);
        var model = new CanonicalArchitectureModel();
        model.getElements().add(new ArchitectureElement("BR-1081", "BusinessRole", "Medical Advisor Roles", "", "BR"));
        assertThat(index.parentsFor(model))
                .containsEntry("BR-1081", "BR-1002")
                .containsEntry("BR-1002", "BR-1057")
                .containsEntry("BR-1057", "BR-1000");

        model.getElements().getFirst().setTaxonomy("custom");
        assertThat(index.parentsFor(model)).doesNotContainKey("BR-1081");
    }

    @Test void catalogueBrowserReadsRootAndUnmodeledDescendantsFromTheSameHierarchy() {
        var overlays = new CatalogueOverlayService(new ObjectMapper(), new DefaultResourceLoader(), true, "unused");
        var index = new CatalogueParentIndex(null, overlays);
        var catalogue = index.catalogueNodes(null);
        var root = catalogue.stream().filter(node -> "BR".equals(node.code())).findFirst().orElseThrow();
        var branch = catalogue.stream().filter(node -> "BR-1002".equals(node.code())).findFirst().orElseThrow();
        var leaf = catalogue.stream().filter(node -> "BR-1081".equals(node.code())).findFirst().orElseThrow();
        assertThat(leaf.parentCode()).isEqualTo(branch.code());
        assertThat(catalogue.indexOf(root)).isLessThan(catalogue.indexOf(branch));
        assertThat(catalogue.indexOf(branch)).isLessThan(catalogue.indexOf(leaf));
        assertThat(index.contains(null, "BR-1081")).isTrue();
        assertThat(index.contains(null, "BR-unknown")).isFalse();
    }
}
