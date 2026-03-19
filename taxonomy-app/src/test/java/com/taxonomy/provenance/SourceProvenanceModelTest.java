package com.taxonomy.provenance;

import com.taxonomy.model.LinkType;
import com.taxonomy.model.SourceType;
import com.taxonomy.provenance.model.RequirementSourceLink;
import com.taxonomy.provenance.model.SourceArtifact;
import com.taxonomy.provenance.model.SourceFragment;
import com.taxonomy.provenance.model.SourceVersion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the provenance domain model classes.
 */
class SourceProvenanceModelTest {

    @Test
    void sourceArtifactCreation() {
        SourceArtifact artifact = new SourceArtifact(SourceType.REGULATION, "BayVwVfG §23");
        assertThat(artifact.getSourceType()).isEqualTo(SourceType.REGULATION);
        assertThat(artifact.getTitle()).isEqualTo("BayVwVfG §23");
        assertThat(artifact.getCreatedAt()).isNotNull();
    }

    @Test
    void sourceArtifactFieldsAreSettable() {
        SourceArtifact artifact = new SourceArtifact(SourceType.BUSINESS_REQUEST, "Test");
        artifact.setCanonicalIdentifier("BR-001");
        artifact.setCanonicalUrl("https://example.com/br-001");
        artifact.setOriginSystem("ERP");
        artifact.setAuthor("testuser");
        artifact.setDescription("A test business request");
        artifact.setLanguage("de");

        assertThat(artifact.getCanonicalIdentifier()).isEqualTo("BR-001");
        assertThat(artifact.getCanonicalUrl()).isEqualTo("https://example.com/br-001");
        assertThat(artifact.getOriginSystem()).isEqualTo("ERP");
        assertThat(artifact.getAuthor()).isEqualTo("testuser");
        assertThat(artifact.getDescription()).isEqualTo("A test business request");
        assertThat(artifact.getLanguage()).isEqualTo("de");
    }

    @Test
    void sourceVersionCreation() {
        SourceArtifact artifact = new SourceArtifact(SourceType.UPLOADED_DOCUMENT, "doc.pdf");
        SourceVersion version = new SourceVersion(artifact);
        version.setMimeType("application/pdf");
        version.setContentHash("abc123");

        assertThat(version.getSourceArtifact()).isSameAs(artifact);
        assertThat(version.getMimeType()).isEqualTo("application/pdf");
        assertThat(version.getContentHash()).isEqualTo("abc123");
        assertThat(version.getRetrievedAt()).isNotNull();
    }

    @Test
    void sourceFragmentCreation() {
        SourceArtifact artifact = new SourceArtifact(SourceType.REGULATION, "Test");
        SourceVersion version = new SourceVersion(artifact);
        SourceFragment fragment = new SourceFragment(version, "§1 The applicant must...");
        fragment.setSectionPath("§1");
        fragment.setPageFrom(3);
        fragment.setPageTo(4);

        assertThat(fragment.getSourceVersion()).isSameAs(version);
        assertThat(fragment.getFragmentText()).isEqualTo("§1 The applicant must...");
        assertThat(fragment.getSectionPath()).isEqualTo("§1");
        assertThat(fragment.getPageFrom()).isEqualTo(3);
        assertThat(fragment.getPageTo()).isEqualTo(4);
    }

    @Test
    void requirementSourceLinkCreation() {
        SourceArtifact artifact = new SourceArtifact(SourceType.REGULATION, "Test");
        RequirementSourceLink link = new RequirementSourceLink("REQ-001", artifact,
                LinkType.EXTRACTED_FROM);
        link.setConfidence(0.85);
        link.setNote("Extracted via document parser");

        assertThat(link.getRequirementId()).isEqualTo("REQ-001");
        assertThat(link.getSourceArtifact()).isSameAs(artifact);
        assertThat(link.getLinkType()).isEqualTo(LinkType.EXTRACTED_FROM);
        assertThat(link.getConfidence()).isEqualTo(0.85);
        assertThat(link.getNote()).isEqualTo("Extracted via document parser");
    }

    @Test
    void allSourceTypesExist() {
        assertThat(SourceType.values()).containsExactlyInAnyOrder(
                SourceType.BUSINESS_REQUEST,
                SourceType.REGULATION,
                SourceType.FIM_ENTRY,
                SourceType.UPLOADED_DOCUMENT,
                SourceType.EMAIL,
                SourceType.MEETING_NOTE,
                SourceType.WEB_RESOURCE,
                SourceType.MANUAL_ENTRY,
                SourceType.LEGACY_IMPORT
        );
    }

    @Test
    void allLinkTypesExist() {
        assertThat(LinkType.values()).containsExactlyInAnyOrder(
                LinkType.IMPORTED_FROM,
                LinkType.EXTRACTED_FROM,
                LinkType.QUOTED_FROM,
                LinkType.DERIVED_FROM,
                LinkType.CONFIRMED_BY,
                LinkType.REFERENCES
        );
    }

    @Test
    void sourceVersionWithVersionLabel() {
        SourceArtifact artifact = new SourceArtifact(SourceType.FIM_ENTRY, "FIM-123");
        SourceVersion version = new SourceVersion(artifact);
        version.setVersionLabel("v2.1");
        version.setStorageLocation("/uploads/doc.pdf");
        version.setRawTextLocation("/uploads/doc.txt");

        assertThat(version.getVersionLabel()).isEqualTo("v2.1");
        assertThat(version.getStorageLocation()).isEqualTo("/uploads/doc.pdf");
        assertThat(version.getRawTextLocation()).isEqualTo("/uploads/doc.txt");
    }

    @Test
    void linkWithOptionalFragment() {
        SourceArtifact artifact = new SourceArtifact(SourceType.REGULATION, "Test");
        SourceVersion version = new SourceVersion(artifact);
        SourceFragment fragment = new SourceFragment(version, "Fragment text");

        RequirementSourceLink link = new RequirementSourceLink("REQ-002", artifact,
                LinkType.QUOTED_FROM);
        link.setSourceVersion(version);
        link.setSourceFragment(fragment);

        assertThat(link.getSourceVersion()).isSameAs(version);
        assertThat(link.getSourceFragment()).isSameAs(fragment);
    }
}
