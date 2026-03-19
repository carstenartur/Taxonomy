package com.taxonomy.provenance;

import com.taxonomy.dto.RequirementSourceLinkDto;
import com.taxonomy.dto.SourceArtifactDto;
import com.taxonomy.model.LinkType;
import com.taxonomy.model.SourceType;
import com.taxonomy.provenance.model.RequirementSourceLink;
import com.taxonomy.provenance.model.SourceArtifact;
import com.taxonomy.provenance.model.SourceFragment;
import com.taxonomy.provenance.model.SourceVersion;
import com.taxonomy.provenance.repository.RequirementSourceLinkRepository;
import com.taxonomy.provenance.repository.SourceArtifactRepository;
import com.taxonomy.provenance.repository.SourceFragmentRepository;
import com.taxonomy.provenance.repository.SourceVersionRepository;
import com.taxonomy.provenance.service.SourceProvenanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SourceProvenanceService}.
 */
class SourceProvenanceServiceTest {

    private SourceArtifactRepository artifactRepo;
    private SourceVersionRepository versionRepo;
    private SourceFragmentRepository fragmentRepo;
    private RequirementSourceLinkRepository linkRepo;
    private SourceProvenanceService service;

    @BeforeEach
    void setUp() {
        artifactRepo = mock(SourceArtifactRepository.class);
        versionRepo = mock(SourceVersionRepository.class);
        fragmentRepo = mock(SourceFragmentRepository.class);
        linkRepo = mock(RequirementSourceLinkRepository.class);
        service = new SourceProvenanceService(artifactRepo, versionRepo, fragmentRepo, linkRepo);
    }

    @Test
    void createArtifactPersists() {
        SourceArtifact saved = new SourceArtifact(SourceType.REGULATION, "Test Regulation");
        saved.setId(1L);
        when(artifactRepo.save(any(SourceArtifact.class))).thenReturn(saved);

        SourceArtifact result = service.createArtifact(SourceType.REGULATION, "Test Regulation");

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getSourceType()).isEqualTo(SourceType.REGULATION);
        verify(artifactRepo).save(any(SourceArtifact.class));
    }

    @Test
    void createVersionPersists() {
        SourceArtifact artifact = new SourceArtifact(SourceType.UPLOADED_DOCUMENT, "doc.pdf");
        SourceVersion saved = new SourceVersion(artifact);
        saved.setId(1L);
        saved.setMimeType("application/pdf");
        when(versionRepo.save(any(SourceVersion.class))).thenReturn(saved);

        SourceVersion result = service.createVersion(artifact, "application/pdf", "hash123");

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getMimeType()).isEqualTo("application/pdf");
        verify(versionRepo).save(any(SourceVersion.class));
    }

    @Test
    void createFragmentPersists() {
        SourceArtifact artifact = new SourceArtifact(SourceType.REGULATION, "Test");
        SourceVersion version = new SourceVersion(artifact);
        SourceFragment saved = new SourceFragment(version, "Fragment text");
        saved.setId(1L);
        when(fragmentRepo.save(any(SourceFragment.class))).thenReturn(saved);

        SourceFragment result = service.createFragment(version, "Fragment text", "§1", 3);

        assertThat(result.getId()).isEqualTo(1L);
        verify(fragmentRepo).save(any(SourceFragment.class));
    }

    @Test
    void linkRequirementPersists() {
        SourceArtifact artifact = new SourceArtifact(SourceType.REGULATION, "Test");
        artifact.setId(1L);
        SourceVersion version = new SourceVersion(artifact);
        RequirementSourceLink saved = new RequirementSourceLink("REQ-001", artifact,
                LinkType.EXTRACTED_FROM);
        saved.setId(1L);
        when(linkRepo.save(any(RequirementSourceLink.class))).thenReturn(saved);

        RequirementSourceLink result = service.linkRequirement("REQ-001", artifact,
                version, null, LinkType.EXTRACTED_FROM);

        assertThat(result.getId()).isEqualTo(1L);
        verify(linkRepo).save(any(RequirementSourceLink.class));
    }

    @Test
    void getLinksForRequirement() {
        SourceArtifact artifact = new SourceArtifact(SourceType.REGULATION, "Test Regulation");
        artifact.setId(1L);
        RequirementSourceLink link = new RequirementSourceLink("REQ-001", artifact,
                LinkType.EXTRACTED_FROM);
        link.setId(1L);
        when(linkRepo.findByRequirementId("REQ-001")).thenReturn(List.of(link));

        List<RequirementSourceLinkDto> results = service.getLinksForRequirement("REQ-001");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getRequirementId()).isEqualTo("REQ-001");
        assertThat(results.get(0).getSourceTitle()).isEqualTo("Test Regulation");
        assertThat(results.get(0).getSourceTypeName()).isEqualTo("REGULATION");
    }

    @Test
    void listAllArtifacts() {
        SourceArtifact a1 = new SourceArtifact(SourceType.REGULATION, "Reg 1");
        a1.setId(1L);
        SourceArtifact a2 = new SourceArtifact(SourceType.BUSINESS_REQUEST, "BR 1");
        a2.setId(2L);
        when(artifactRepo.findAll()).thenReturn(List.of(a1, a2));

        List<SourceArtifactDto> results = service.listAllArtifacts();

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getSourceType()).isEqualTo(SourceType.REGULATION);
        assertThat(results.get(1).getSourceType()).isEqualTo(SourceType.BUSINESS_REQUEST);
    }

    @Test
    void findArtifactById() {
        SourceArtifact artifact = new SourceArtifact(SourceType.REGULATION, "Test");
        artifact.setId(42L);
        when(artifactRepo.findById(42L)).thenReturn(Optional.of(artifact));

        Optional<SourceArtifact> result = service.findArtifactById(42L);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(42L);
    }

    @Test
    void findArtifactsByType() {
        SourceArtifact a = new SourceArtifact(SourceType.REGULATION, "Reg");
        a.setId(1L);
        when(artifactRepo.findBySourceType(SourceType.REGULATION)).thenReturn(List.of(a));

        List<SourceArtifact> results = service.findArtifactsByType(SourceType.REGULATION);

        assertThat(results).hasSize(1);
    }
}
