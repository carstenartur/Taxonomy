package com.taxonomy.dsl.mapping.profiles;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link ArchiMateMappingProfile}.
 */
class ArchiMateMappingProfileTest {

    private final ArchiMateMappingProfile profile = new ArchiMateMappingProfile();

    // ── Element type mappings ───────────────────────────────────────────────

    @Test
    void profileId() {
        assertThat(profile.profileId()).isEqualTo("archimate");
    }

    @Test
    void displayName() {
        assertThat(profile.displayName()).isEqualTo("ArchiMate 3.x");
    }

    @Test
    void mapCapability() {
        assertThat(profile.mapElementType("Capability")).isEqualTo("CP");
    }

    @Test
    void mapBusinessProcess() {
        assertThat(profile.mapElementType("BusinessProcess")).isEqualTo("BP");
    }

    @Test
    void mapBusinessRole() {
        assertThat(profile.mapElementType("BusinessRole")).isEqualTo("BR");
    }

    @Test
    void mapApplicationService() {
        assertThat(profile.mapElementType("ApplicationService")).isEqualTo("CR");
    }

    @Test
    void mapBusinessService() {
        assertThat(profile.mapElementType("BusinessService")).isEqualTo("CI");
    }

    @Test
    void mapCommunicationNetwork() {
        assertThat(profile.mapElementType("CommunicationNetwork")).isEqualTo("CO");
    }

    @Test
    void mapTechnologyService() {
        assertThat(profile.mapElementType("TechnologyService")).isEqualTo("CR");
    }

    @Test
    void mapApplicationComponent() {
        assertThat(profile.mapElementType("ApplicationComponent")).isEqualTo("UA");
    }

    @Test
    void mapDataObject() {
        assertThat(profile.mapElementType("DataObject")).isEqualTo("IP");
    }

    @Test
    void mapBusinessObject() {
        assertThat(profile.mapElementType("BusinessObject")).isEqualTo("IP");
    }

    @Test
    void unmappedElementTypeReturnsNull() {
        assertThat(profile.mapElementType("SomeUnknown")).isNull();
    }

    @Test
    void nullElementTypeReturnsNull() {
        assertThat(profile.mapElementType(null)).isNull();
    }

    // ── Relation type mappings ──────────────────────────────────────────────

    @Test
    void mapRealization() {
        assertThat(profile.mapRelationType("Realization")).isEqualTo("REALIZES");
    }

    @Test
    void mapServing() {
        assertThat(profile.mapRelationType("Serving")).isEqualTo("SUPPORTS");
    }

    @Test
    void mapAccess() {
        assertThat(profile.mapRelationType("Access")).isEqualTo("CONSUMES");
    }

    @Test
    void mapAssignment() {
        assertThat(profile.mapRelationType("Assignment")).isEqualTo("ASSIGNED_TO");
    }

    @Test
    void mapFlow() {
        assertThat(profile.mapRelationType("Flow")).isEqualTo("COMMUNICATES_WITH");
    }

    @Test
    void mapComposition() {
        assertThat(profile.mapRelationType("Composition")).isEqualTo("RELATED_TO");
    }

    @Test
    void mapAggregation() {
        assertThat(profile.mapRelationType("Aggregation")).isEqualTo("RELATED_TO");
    }

    @Test
    void mapAssociation() {
        assertThat(profile.mapRelationType("Association")).isEqualTo("RELATED_TO");
    }

    @Test
    void mapTriggering() {
        assertThat(profile.mapRelationType("Triggering")).isEqualTo("SUPPORTS");
    }

    @Test
    void mapInfluence() {
        assertThat(profile.mapRelationType("Influence")).isEqualTo("RELATED_TO");
    }

    @Test
    void mapSpecialization() {
        assertThat(profile.mapRelationType("Specialization")).isEqualTo("RELATED_TO");
    }

    @Test
    void unmappedRelationTypeReturnsNull() {
        assertThat(profile.mapRelationType("CustomRelation")).isNull();
    }

    @Test
    void nullRelationTypeReturnsNull() {
        assertThat(profile.mapRelationType(null)).isNull();
    }

    // ── Extensions ──────────────────────────────────────────────────────────

    @Test
    void elementExtensionsContainArchimateKind() {
        Map<String, String> ext = profile.elementExtensions("Capability", Map.of());
        assertThat(ext).containsEntry("x-archimate-kind", "Capability");
    }

    @Test
    void relationExtensionsContainArchimateRel() {
        Map<String, String> ext = profile.relationExtensions("Realization", Map.of());
        assertThat(ext).containsEntry("x-archimate-rel", "Realization");
    }

    // ── Supported types ─────────────────────────────────────────────────────

    @Test
    void supportedElementTypesContainsAllMappings() {
        assertThat(profile.supportedElementTypes()).hasSize(10);
        assertThat(profile.supportedElementTypes()).contains(
                "Capability", "BusinessProcess", "BusinessRole",
                "ApplicationService", "BusinessService", "CommunicationNetwork",
                "TechnologyService", "ApplicationComponent", "DataObject", "BusinessObject");
    }

    @Test
    void supportedRelationTypesContainsAllMappings() {
        assertThat(profile.supportedRelationTypes()).hasSize(11);
        assertThat(profile.supportedRelationTypes()).contains(
                "Realization", "Serving", "Access", "Assignment", "Flow",
                "Composition", "Aggregation", "Association", "Triggering",
                "Influence", "Specialization");
    }
}
