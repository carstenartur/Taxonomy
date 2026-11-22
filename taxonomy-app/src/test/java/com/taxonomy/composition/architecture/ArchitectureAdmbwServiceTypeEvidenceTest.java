package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.model.CatalogueDefinition;
import com.taxonomy.dsl.model.CatalogueDefinitionResolver;
import com.taxonomy.dsl.model.CatalogueDefinitionResolver.Node;
import com.taxonomy.dsl.model.CatalogueReference;
import com.taxonomy.extension.api.integration.AdmbwQeaExport.ServiceSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureAdmbwServiceTypeEvidenceTest {
    private static final String UUID = "30000000-0000-0000-0000-000000000001";
    private static final String NATIVE_GUID = "{40000000-0000-0000-0000-000000000001}";
    private static final String NATIVE_ROOT = "{20000000-0000-0000-0000-000000000001}";
    private static final String TITLE = "Records Management Services";
    private static final String SOURCE = "NATO C3 Taxonomy";
    private static final Node ENTRY = new Node("CR-1", "CR", null, TITLE, "Schriftgutverwaltungsdienste", 1, UUID);

    @Test void corroboratesTheServiceTypeUsingAUniquePinnedTitleAndAnExplicitNativeSource() {
        var model = model("CR-1", TITLE);
        var specification = specification(NATIVE_GUID, "Records\n Management Services", SOURCE);
        var evidence = ArchitectureAdmbwPreparationService.serviceTypeEvidence(model, catalogue(ENTRY), List.of(specification));

        assertThat(evidence).containsExactly(new ArchitectureAdmbwPreparationService.ServiceTypeEvidence("CR-1", UUID, specification));
        assertThat(model.getElements().getFirst().getExtensions()).isEmpty();
        assertThat(model.getRelations()).isEmpty();
    }

    @Test void excludesAnUnpinnedModelUserAuthoredTitlesAndConflictingSourceIdentity() {
        var specification = specification(NATIVE_GUID, TITLE, SOURCE);
        var unpinned = model("CR-1", TITLE);
        unpinned.setCatalogueRevision(null);
        var wrongUuid = model("CR-1", TITLE);
        wrongUuid.getElements().getFirst().getExtensions().put(CatalogueReference.UUID_PROPERTY, "50000000-0000-0000-0000-000000000001");

        for (var model : List.of(unpinned, model("custom-service", TITLE), model("CR-1", "Authored service"), wrongUuid)) {
            assertThat(ArchitectureAdmbwPreparationService.serviceTypeEvidence(model, catalogue(ENTRY), List.of(specification))).isEmpty();
        }
    }

    @Test void excludesAmbiguousNamesAndUnattestedSources() {
        var model = model("CR-1", TITLE);
        var first = specification(NATIVE_GUID, TITLE, SOURCE);
        var ambiguous = specification("{40000000-0000-0000-0000-000000000002}", TITLE, null);
        var alternativeName = specification(NATIVE_GUID, "Records Management Service", SOURCE);
        var secondCatalogueEntry = new Node("CR-2", "CR", null, TITLE, null, 2, "30000000-0000-0000-0000-000000000002");

        assertThat(ArchitectureAdmbwPreparationService.serviceTypeEvidence(model, catalogue(ENTRY), List.of(first, ambiguous))).isEmpty();
        assertThat(ArchitectureAdmbwPreparationService.serviceTypeEvidence(model, catalogue(ENTRY), List.of(ambiguous, alternativeName))).isEmpty();
        assertThat(ArchitectureAdmbwPreparationService.serviceTypeEvidence(model, catalogue(ENTRY, secondCatalogueEntry), List.of(first))).isEmpty();
    }

    private static CanonicalArchitectureModel model(String id, String title) {
        var model = new CanonicalArchitectureModel();
        model.setCatalogueRevision("applied-workbook-revision");
        model.getElements().add(new ArchitectureElement(id, "CoreService", title, null, "CR"));
        return model;
    }

    private static CatalogueDefinitionResolver catalogue(Node... nodes) {
        return new CatalogueDefinitionResolver() {
            @Override public CatalogueDefinition forModel(CanonicalArchitectureModel model) { return CatalogueDefinition.standard(); }
            @Override public List<Node> catalogueNodes(CanonicalArchitectureModel model) { return List.of(nodes); }
        };
    }

    private static ServiceSpecification specification(String guid, String title, String source) {
        return new ServiceSpecification(guid, title, List.of("Root", "Specifications"), NATIVE_ROOT, source);
    }
}
