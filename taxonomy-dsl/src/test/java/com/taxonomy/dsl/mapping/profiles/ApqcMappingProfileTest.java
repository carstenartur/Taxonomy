package com.taxonomy.dsl.mapping.profiles;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link ApqcMappingProfile}.
 */
class ApqcMappingProfileTest {

    private ApqcMappingProfile profile;

    @BeforeEach
    void setUp() {
        profile = new ApqcMappingProfile();
    }

    @Test
    void profileId() {
        assertThat(profile.profileId()).isEqualTo("apqc");
    }

    @Test
    void displayName() {
        assertThat(profile.displayName()).isEqualTo("APQC PCF");
    }

    @Test
    void mapCategoryToCapability() {
        assertThat(profile.mapElementType("Category")).isEqualTo("CP");
    }

    @Test
    void mapProcessGroupToProcess() {
        assertThat(profile.mapElementType("ProcessGroup")).isEqualTo("BP");
    }

    @Test
    void mapProcessToCoreService() {
        assertThat(profile.mapElementType("Process")).isEqualTo("CR");
    }

    @Test
    void mapActivityToCOIService() {
        assertThat(profile.mapElementType("Activity")).isEqualTo("CI");
    }

    @Test
    void mapTaskToBusinessRole() {
        assertThat(profile.mapElementType("Task")).isEqualTo("BR");
    }

    @Test
    void unknownElementReturnsNull() {
        assertThat(profile.mapElementType("UnknownType")).isNull();
    }

    @Test
    void mapParentChildToSupports() {
        assertThat(profile.mapRelationType("ParentChild")).isEqualTo("SUPPORTS");
    }

    @Test
    void mapEnablesToSupports() {
        assertThat(profile.mapRelationType("Enables")).isEqualTo("SUPPORTS");
    }

    @Test
    void mapConsumesToConsumes() {
        assertThat(profile.mapRelationType("Consumes")).isEqualTo("CONSUMES");
    }

    @Test
    void mapProducesToProduces() {
        assertThat(profile.mapRelationType("Produces")).isEqualTo("PRODUCES");
    }

    @Test
    void unknownRelationReturnsNull() {
        assertThat(profile.mapRelationType("UnknownRel")).isNull();
    }

    @Test
    void elementExtensionsIncludeLevel() {
        Map<String, String> ext = profile.elementExtensions("Category", Map.of());
        assertThat(ext).containsEntry("x-apqc-level", "Category");
    }

    @Test
    void elementExtensionsIncludeParentId() {
        Map<String, String> ext = profile.elementExtensions("Process",
                Map.of("parentId", "apqc-1-1"));
        assertThat(ext).containsEntry("x-apqc-parent", "apqc-1-1");
    }

    @Test
    void elementExtensionsIncludePcfId() {
        Map<String, String> ext = profile.elementExtensions("Category",
                Map.of("pcfId", "1.0"));
        assertThat(ext).containsEntry("x-apqc-pcf-id", "1.0");
    }

    @Test
    void relationExtensionsIncludeRelType() {
        Map<String, String> ext = profile.relationExtensions("ParentChild", Map.of());
        assertThat(ext).containsEntry("x-apqc-rel", "ParentChild");
    }

    @Test
    void supportedElementTypes() {
        assertThat(profile.supportedElementTypes())
                .containsExactlyInAnyOrder("Category", "ProcessGroup", "Process", "Activity", "Task");
    }

    @Test
    void supportedRelationTypes() {
        assertThat(profile.supportedRelationTypes())
                .containsExactlyInAnyOrder("ParentChild", "Enables", "Consumes", "Produces");
    }
}
