package com.taxonomy.portfolio.reformulation;

import com.taxonomy.portfolio.repository.ArchitectureProjectRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementVersionRepository;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ReformulationEvidenceCodecBoundaryTest {

    private final ReformulationAdoptionRepository adoptions =
            mock(ReformulationAdoptionRepository.class);
    private final ReformulationPortableEvidenceRepository imported =
            mock(ReformulationPortableEvidenceRepository.class);
    private final ArchitectureProjectRepository projects =
            mock(ArchitectureProjectRepository.class);
    private final ProjectRequirementRepository requirements =
            mock(ProjectRequirementRepository.class);
    private final ProjectRequirementVersionRepository versions =
            mock(ProjectRequirementVersionRepository.class);

    private final ReformulationEvidenceCodec codec = new ReformulationEvidenceCodec(
            new PortfolioJsonCodec(new ObjectMapper()),
            adoptions,
            imported,
            projects,
            requirements,
            versions);

    @Test
    void emptyDocumentsContainNoPortableEvidence() {
        assertThat(codec.validateForMaterialization(null)).isEmpty();
        assertThat(codec.validateForMaterialization("   ")).isEmpty();

        verifyNoInteractions(adoptions, imported, projects, requirements, versions);
    }

    @Test
    void contributionRequiresAnExactNonBlankScopeBeforeRepositoryAccess() {
        assertThatThrownBy(() -> codec.contributeTo(null, null))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("exact portfolio scope");
        assertThatThrownBy(() -> codec.contributeTo("", "   "))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("exact portfolio scope");

        verifyNoInteractions(adoptions, imported, projects, requirements, versions);
    }

    @Test
    void absentImportedEvidenceIsANoopWithoutScopeOrPersistenceAccess() {
        codec.storeImported(null, null);
        codec.storeImported(List.of(), "   ");

        verifyNoInteractions(adoptions, imported, projects, requirements, versions);
    }

    @Test
    void nonemptyImportedEvidenceRequiresAnExactScopeBeforeMaterializedLookups() {
        var evidence = new ReformulationEvidenceCodec.Evidence(
                "P", "R", 2,
                ReformulationEvidenceCodec.CURRENT_SCHEMA,
                "{}", "0".repeat(64), "1".repeat(64));

        assertThatThrownBy(() -> codec.storeImported(List.of(evidence), null))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("exact portfolio scope");
        assertThatThrownBy(() -> codec.storeImported(List.of(evidence), " "))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("exact portfolio scope");

        verifyNoInteractions(adoptions, imported, projects, requirements, versions);
    }
}
