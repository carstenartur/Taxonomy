package com.taxonomy.portfolio.reformulation;

import com.taxonomy.portfolio.repository.ArchitectureProjectRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementVersionRepository;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import com.taxonomy.reformulation.ReformulationBaseline;
import com.taxonomy.identity.StableIdentityHash;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.taxonomy.portfolio.model.PortfolioTenantIdentity;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
    private final ReformulationProposalRepository proposals =
            mock(ReformulationProposalRepository.class);

    private final ReformulationEvidenceCodec codec = new ReformulationEvidenceCodec(
            new PortfolioJsonCodec(new ObjectMapper()),
            adoptions,
            imported,
            projects,
            requirements,
            versions,
            proposals);

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

    @Test
    void publicPayloadDecoderRejectsUnknownStoredSchemaBeforeReadingAsV1() {
        var future = new ReformulationEvidenceCodec.Evidence("P", "R", 2,
                "reformulation-evidence-v99", "{}", "0".repeat(64), "1".repeat(64));
        assertThatThrownBy(() -> codec.payload(future))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("Unsupported reformulation evidence schema");
    }

    @Test
    void ancestryReadsRecordedLineageWithoutMaterializingUnrelatedFrozenText() {
        var factory = JsonFactory.builder().streamReadConstraints(
                StreamReadConstraints.builder().maxStringLength(1024).build()).build();
        var json = new PortfolioJsonCodec(JsonMapper.builder(factory).build());
        var limited = new ReformulationEvidenceCodec(json, adoptions, imported, projects, requirements, versions, proposals);
        String scopeKey = new PortfolioTenantIdentity(
                "repo", "WORKSPACE:workspace", "main").scopeKey();
        var receipt = new ReformulationAdoption("receipt", "proposal", "preview", scopeKey, "command",
                20L, 30L, "{}", Instant.now());
        var context = Map.of("catalogue", "catalogue text ".repeat(5000), "adoptedLineage",
                "{\"entries\":[],\"roots\":[\"recorded-root\"],\"decisionContext\":\"historical context\"}");
        var baseline = new ReformulationBaseline(new ReformulationBaseline.Scope("repo", "workspace", "main", 10L, 20L),
                40L, "original", StableIdentityHash.sha256("original"), "snapshot", "{}", context, "en", "v1");
        when(proposals.findById("proposal")).thenReturn(Optional.of(
                new ReformulationProposal("proposal", scopeKey, 10L, 20L, 40L, "snapshot",
                        json.write(baseline), "actor", Instant.now())));

        ReformulationEvidenceCodec.LineageSnapshot lineage = ReflectionTestUtils.invokeMethod(limited, "ancestry", receipt, scopeKey, 40L);
        assertThat(lineage.roots()).containsExactly("recorded-root");
        assertThat(lineage.decisionContext()).isEqualTo("historical context");
    }

    @Test
    void ancestryRejectsBaselineOutsidePhysicalProposalAndReceipt() {
        var json = new PortfolioJsonCodec(new ObjectMapper());
        String scopeKey = new PortfolioTenantIdentity(
                "repo", "WORKSPACE:workspace", "main").scopeKey();
        var receipt = new ReformulationAdoption("receipt", "proposal", "preview", scopeKey, "command",
                20L, 30L, "{}", Instant.now());
        for (var scope : List.of(
                new ReformulationBaseline.Scope("other-repo", "workspace", "main", 10L, 20L),
                new ReformulationBaseline.Scope("repo", "other-workspace", "main", 10L, 20L),
                new ReformulationBaseline.Scope("repo", "workspace", "other-branch", 10L, 20L),
                new ReformulationBaseline.Scope("repo", "workspace", "main", 99L, 20L),
                new ReformulationBaseline.Scope("repo", "workspace", "main", 10L, 99L))) {
            var baseline = new ReformulationBaseline(scope, 40L, "original", StableIdentityHash.sha256("original"),
                    "snapshot", "{}", Map.of("adoptedLineage", "{\"entries\":[],\"roots\":[],\"decisionContext\":\"[]\"}"), "en", "v1");
            when(proposals.findById("proposal")).thenReturn(Optional.of(
                    new ReformulationProposal("proposal", scopeKey, 10L, 20L, 40L, "snapshot",
                            json.write(baseline), "actor", Instant.now())));
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                    codec, "ancestry", receipt, scopeKey, 40L))
                    .isInstanceOf(PortfolioException.class).hasMessageContaining("baseline");
        }
    }
}
