package com.taxonomy.dsl.mapping.profiles;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link C4MappingProfile}.
 */
class C4MappingProfileTest {

    private C4MappingProfile profile;

    @BeforeEach
    void setUp() {
        profile = new C4MappingProfile();
    }

    @Test
    void profileId() {
        assertThat(profile.profileId()).isEqualTo("c4");
    }

    @Test
    void displayName() {
        assertThat(profile.displayName()).isEqualTo("C4 / Structurizr");
    }

    // ── Element type mappings ──

    @Test
    void mapPersonToBusinessRole() {
        assertThat(profile.mapElementType("Person")).isEqualTo("BR");
    }

    @Test
    void mapSoftwareSystemToSystem() {
        assertThat(profile.mapElementType("SoftwareSystem")).isEqualTo("SY");
    }

    @Test
    void mapContainerToUserApplication() {
        assertThat(profile.mapElementType("Container")).isEqualTo("UA");
    }

    @Test
    void mapComponentToComponent() {
        assertThat(profile.mapElementType("Component")).isEqualTo("CM");
    }

    @Test
    void mapDeploymentNodeToCommunications() {
        assertThat(profile.mapElementType("DeploymentNode")).isEqualTo("CO");
    }

    @Test
    void mapInfrastructureNodeToCommunications() {
        assertThat(profile.mapElementType("InfrastructureNode")).isEqualTo("CO");
    }

    @Test
    void mapContainerInstanceToUserApplication() {
        assertThat(profile.mapElementType("ContainerInstance")).isEqualTo("UA");
    }

    @Test
    void unknownElementTypeReturnsNull() {
        assertThat(profile.mapElementType("UnknownElement")).isNull();
    }

    // ── Relation type mappings ──

    @Test
    void mapUsesToUses() {
        assertThat(profile.mapRelationType("Uses")).isEqualTo("USES");
    }

    @Test
    void mapDeliversToProduces() {
        assertThat(profile.mapRelationType("Delivers")).isEqualTo("PRODUCES");
    }

    @Test
    void mapInteractsWithToCommunicatesWith() {
        assertThat(profile.mapRelationType("InteractsWith")).isEqualTo("COMMUNICATES_WITH");
    }

    @Test
    void mapDependsOnToDependsOn() {
        assertThat(profile.mapRelationType("DependsOn")).isEqualTo("DEPENDS_ON");
    }

    @Test
    void mapContainsToContains() {
        assertThat(profile.mapRelationType("Contains")).isEqualTo("CONTAINS");
    }

    @Test
    void mapRealizesToRealizes() {
        assertThat(profile.mapRelationType("Realizes")).isEqualTo("REALIZES");
    }

    @Test
    void mapSupportsToSupports() {
        assertThat(profile.mapRelationType("Supports")).isEqualTo("SUPPORTS");
    }

    @Test
    void unknownRelationTypeReturnsNull() {
        assertThat(profile.mapRelationType("UnknownRel")).isNull();
    }

    // ── Extensions ──

    @Test
    void elementExtensionsIncludeKind() {
        Map<String, String> ext = profile.elementExtensions("SoftwareSystem", Map.of());
        assertThat(ext).containsEntry("x-c4-kind", "SoftwareSystem");
    }

    @Test
    void elementExtensionsIncludeTechnology() {
        Map<String, String> ext = profile.elementExtensions("Container",
                Map.of("technology", "Spring Boot"));
        assertThat(ext)
                .containsEntry("x-c4-kind", "Container")
                .containsEntry("x-c4-technology", "Spring Boot");
    }

    @Test
    void relationExtensionsIncludeRelType() {
        Map<String, String> ext = profile.relationExtensions("Uses", Map.of());
        assertThat(ext).containsEntry("x-c4-rel", "Uses");
    }

    @Test
    void relationExtensionsIncludeTechnology() {
        Map<String, String> ext = profile.relationExtensions("Uses",
                Map.of("technology", "HTTPS/JSON"));
        assertThat(ext)
                .containsEntry("x-c4-rel", "Uses")
                .containsEntry("x-c4-technology", "HTTPS/JSON");
    }

    // ── Supported types ──

    @Test
    void supportedElementTypes() {
        assertThat(profile.supportedElementTypes())
                .containsExactlyInAnyOrder("Person", "SoftwareSystem", "Container", "Component",
                        "DeploymentNode", "InfrastructureNode", "ContainerInstance");
    }

    @Test
    void supportedRelationTypes() {
        assertThat(profile.supportedRelationTypes())
                .containsExactlyInAnyOrder("Uses", "Delivers", "InteractsWith", "DependsOn",
                        "Contains", "Realizes", "Supports");
    }
}
