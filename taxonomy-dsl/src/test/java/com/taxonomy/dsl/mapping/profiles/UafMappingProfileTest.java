package com.taxonomy.dsl.mapping.profiles;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link UafMappingProfile}.
 */
class UafMappingProfileTest {

    private final UafMappingProfile profile = new UafMappingProfile();

    // ── Profile metadata ────────────────────────────────────────────────────

    @Test
    void profileId() {
        assertThat(profile.profileId()).isEqualTo("uaf");
    }

    @Test
    void displayName() {
        assertThat(profile.displayName()).isEqualTo("UAF / DoDAF");
    }

    // ── Element type mappings ───────────────────────────────────────────────

    @Test
    void mapCapability() {
        assertThat(profile.mapElementType("Capability")).isEqualTo("CP");
    }

    @Test
    void mapOperationalActivity() {
        assertThat(profile.mapElementType("OperationalActivity")).isEqualTo("BP");
    }

    @Test
    void mapServiceFunction() {
        assertThat(profile.mapElementType("ServiceFunction")).isEqualTo("CR");
    }

    @Test
    void mapCapabilityConfiguration() {
        assertThat(profile.mapElementType("CapabilityConfiguration")).isEqualTo("CI");
    }

    @Test
    void mapCommunicationsFunction() {
        assertThat(profile.mapElementType("CommunicationsFunction")).isEqualTo("CO");
    }

    @Test
    void mapSystem() {
        assertThat(profile.mapElementType("System")).isEqualTo("UA");
    }

    @Test
    void mapPlatform() {
        assertThat(profile.mapElementType("Platform")).isEqualTo("UA");
    }

    @Test
    void mapPerformer() {
        assertThat(profile.mapElementType("Performer")).isEqualTo("BR");
    }

    @Test
    void mapOrganization() {
        assertThat(profile.mapElementType("Organization")).isEqualTo("BR");
    }

    @Test
    void mapResourcePerformer() {
        assertThat(profile.mapElementType("ResourcePerformer")).isEqualTo("BR");
    }

    @Test
    void mapInformationElement() {
        assertThat(profile.mapElementType("InformationElement")).isEqualTo("IP");
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
    void mapImplements() {
        assertThat(profile.mapRelationType("Implements")).isEqualTo("REALIZES");
    }

    @Test
    void mapSupports() {
        assertThat(profile.mapRelationType("Supports")).isEqualTo("SUPPORTS");
    }

    @Test
    void mapConsumes() {
        assertThat(profile.mapRelationType("Consumes")).isEqualTo("CONSUMES");
    }

    @Test
    void mapUses() {
        assertThat(profile.mapRelationType("Uses")).isEqualTo("USES");
    }

    @Test
    void mapProvides() {
        assertThat(profile.mapRelationType("Provides")).isEqualTo("FULFILLS");
    }

    @Test
    void mapIsAssignedTo() {
        assertThat(profile.mapRelationType("IsAssignedTo")).isEqualTo("ASSIGNED_TO");
    }

    @Test
    void mapDependsOn() {
        assertThat(profile.mapRelationType("DependsOn")).isEqualTo("DEPENDS_ON");
    }

    @Test
    void mapProduces() {
        assertThat(profile.mapRelationType("Produces")).isEqualTo("PRODUCES");
    }

    @Test
    void mapCommunicatesWith() {
        assertThat(profile.mapRelationType("CommunicatesWith")).isEqualTo("COMMUNICATES_WITH");
    }

    @Test
    void unmappedRelationTypeFallsThrough() {
        assertThat(profile.mapRelationType("other")).isNull();
    }

    @Test
    void nullRelationTypeReturnsNull() {
        assertThat(profile.mapRelationType(null)).isNull();
    }

    // ── Extensions ──────────────────────────────────────────────────────────

    @Test
    void elementExtensionsContainUafKind() {
        Map<String, String> ext = profile.elementExtensions("Capability", Map.of());
        assertThat(ext).containsEntry("x-uaf-kind", "Capability");
    }

    @Test
    void relationExtensionsContainUafRel() {
        Map<String, String> ext = profile.relationExtensions("Implements", Map.of());
        assertThat(ext).containsEntry("x-uaf-rel", "Implements");
    }

    // ── Supported types ─────────────────────────────────────────────────────

    @Test
    void supportedElementTypesContainsAllMappings() {
        assertThat(profile.supportedElementTypes()).hasSize(11);
        assertThat(profile.supportedElementTypes()).contains(
                "Capability", "OperationalActivity", "ServiceFunction",
                "CapabilityConfiguration", "CommunicationsFunction",
                "System", "Platform", "Performer", "Organization",
                "ResourcePerformer", "InformationElement");
    }

    @Test
    void supportedRelationTypesContainsAllMappings() {
        assertThat(profile.supportedRelationTypes()).hasSize(9);
        assertThat(profile.supportedRelationTypes()).contains(
                "Implements", "Supports", "Consumes", "Uses", "Provides",
                "IsAssignedTo", "DependsOn", "Produces", "CommunicatesWith");
    }
}
