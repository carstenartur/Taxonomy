package com.taxonomy.catalog.definition;

import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogueParentIndexTest {
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
}
