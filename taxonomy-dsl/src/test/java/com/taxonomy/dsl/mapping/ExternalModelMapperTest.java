package com.taxonomy.dsl.mapping;

import com.taxonomy.dsl.mapping.profiles.ArchiMateMappingProfile;
import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureRelation;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the generic {@link ExternalModelMapper}.
 */
class ExternalModelMapperTest {

    private ExternalModelMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ExternalModelMapper(new ArchiMateMappingProfile());
    }

    @Test
    void mapCapabilityElement() {
        ExternalElement element = new ExternalElement(
                "el-1", "Capability", "Secure Communications", "Ability to communicate securely", Map.of());
        MappingResult result = mapper.map(List.of(element), List.of());

        CanonicalArchitectureModel model = result.model();
        assertThat(model.getElements()).hasSize(1);

        ArchitectureElement el = model.getElements().get(0);
        assertThat(el.getId()).isEqualTo("el-1");
        assertThat(el.getType()).isEqualTo("Capability");
        assertThat(el.getTitle()).isEqualTo("Secure Communications");
        assertThat(el.getDescription()).isEqualTo("Ability to communicate securely");
        assertThat(el.getExtensions()).containsEntry("x-source-framework", "archimate");
        assertThat(el.getExtensions()).containsEntry("x-archimate-kind", "Capability");
    }

    @Test
    void mapRealizationRelation() {
        ExternalRelation relation = new ExternalRelation(
                "el-1", "el-2", "Realization", Map.of());
        MappingResult result = mapper.map(List.of(), List.of(relation));

        CanonicalArchitectureModel model = result.model();
        assertThat(model.getRelations()).hasSize(1);

        ArchitectureRelation rel = model.getRelations().get(0);
        assertThat(rel.getSourceId()).isEqualTo("el-1");
        assertThat(rel.getTargetId()).isEqualTo("el-2");
        assertThat(rel.getRelationType()).isEqualTo("REALIZES");
        assertThat(rel.getStatus()).isEqualTo("proposed");
        assertThat(rel.getProvenance()).isEqualTo("archimate-import");
        assertThat(rel.getExtensions()).containsEntry("x-source-framework", "archimate");
        assertThat(rel.getExtensions()).containsEntry("x-archimate-rel", "Realization");
    }

    @Test
    void unknownElementTypeProducesWarning() {
        ExternalElement element = new ExternalElement(
                "el-x", "UnknownWidget", "Something", null, Map.of());
        MappingResult result = mapper.map(List.of(element), List.of());

        assertThat(result.warnings()).anyMatch(w -> w.contains("Unmapped element type: UnknownWidget"));
        assertThat(result.unmappedTypes()).contains("UnknownWidget");

        ArchitectureElement el = result.model().getElements().get(0);
        assertThat(el.getType()).isEqualTo("Unknown");
        assertThat(el.getExtensions()).containsEntry("x-unmapped-type", "UnknownWidget");
    }

    @Test
    void unknownRelationTypeFallsBackToRelatedTo() {
        ExternalRelation relation = new ExternalRelation(
                "el-1", "el-2", "CustomLink", Map.of());
        MappingResult result = mapper.map(List.of(), List.of(relation));

        assertThat(result.warnings()).anyMatch(w -> w.contains("Unmapped relation type: CustomLink"));
        assertThat(result.unmappedTypes()).contains("CustomLink");

        ArchitectureRelation rel = result.model().getRelations().get(0);
        assertThat(rel.getRelationType()).isEqualTo("RELATED_TO");
    }

    @Test
    void mappingStatisticsAreCorrect() {
        List<ExternalElement> elements = List.of(
                new ExternalElement("el-1", "Capability", "Cap1", null, Map.of()),
                new ExternalElement("el-2", "BusinessProcess", "Proc1", null, Map.of()),
                new ExternalElement("el-3", "UnknownType", "Unk1", null, Map.of()));
        List<ExternalRelation> relations = List.of(
                new ExternalRelation("el-1", "el-2", "Realization", Map.of()),
                new ExternalRelation("el-2", "el-3", "MysteryLink", Map.of()));

        MappingResult result = mapper.map(elements, relations);

        assertThat(result.mappingStatistics()).containsEntry("elements", 3);
        assertThat(result.mappingStatistics()).containsEntry("relations", 2);
        assertThat(result.mappingStatistics()).containsEntry("mappedElements", 2);
        assertThat(result.mappingStatistics()).containsEntry("mappedRelations", 1);
        assertThat(result.mappingStatistics()).containsEntry("unmapped", 2);
    }

    @Test
    void extensionsFromProfileAreMerged() {
        ExternalElement element = new ExternalElement(
                "el-1", "ApplicationComponent", "MyApp", null, Map.of("version", "2.0"));
        MappingResult result = mapper.map(List.of(element), List.of());

        ArchitectureElement el = result.model().getElements().get(0);
        // The profile adds x-archimate-kind, and the mapper adds x-source-framework
        assertThat(el.getExtensions()).containsEntry("x-source-framework", "archimate");
        assertThat(el.getExtensions()).containsEntry("x-archimate-kind", "ApplicationComponent");
    }

    @Test
    void emptyInputProducesEmptyModel() {
        MappingResult result = mapper.map(List.of(), List.of());

        assertThat(result.model().getElements()).isEmpty();
        assertThat(result.model().getRelations()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.unmappedTypes()).isEmpty();
    }

    @Test
    void multipleElementTypesMappedCorrectly() {
        List<ExternalElement> elements = List.of(
                new ExternalElement("e1", "Capability", "Cap", null, Map.of()),
                new ExternalElement("e2", "BusinessProcess", "Proc", null, Map.of()),
                new ExternalElement("e3", "DataObject", "Data", null, Map.of()),
                new ExternalElement("e4", "ApplicationComponent", "App", null, Map.of()));

        MappingResult result = mapper.map(elements, List.of());

        assertThat(result.model().getElements()).hasSize(4);
        assertThat(result.model().getElements().get(0).getType()).isEqualTo("Capability");
        assertThat(result.model().getElements().get(1).getType()).isEqualTo("Process");
        assertThat(result.model().getElements().get(2).getType()).isEqualTo("InformationProduct");
        assertThat(result.model().getElements().get(3).getType()).isEqualTo("UserApplication");
    }
}
