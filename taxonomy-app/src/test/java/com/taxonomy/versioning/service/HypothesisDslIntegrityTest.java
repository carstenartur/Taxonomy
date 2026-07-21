package com.taxonomy.versioning.service;

import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.repository.RelationEvidenceRepository;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HypothesisDslIntegrityTest {

    @Mock
    private RelationHypothesisRepository hypothesisRepository;
    @Mock
    private RelationEvidenceRepository evidenceRepository;
    @Mock
    private TaxonomyRelationService relationService;
    @Mock
    private TaxonomyNodeRepository nodeRepository;
    @Mock
    private DslGitRepositoryFactory repositoryFactory;
    @Mock
    private DslGitRepository workspaceRepository;

    private HypothesisService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new HypothesisService(
                hypothesisRepository,
                evidenceRepository,
                relationService,
                nodeRepository,
                repositoryFactory);
        when(hypothesisRepository.findBySourceNodeIdAndTargetNodeIdAndRelationType(
                anyString(), anyString(), any())).thenReturn(List.of());
        when(hypothesisRepository.save(any(RelationHypothesis.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByCode(anyString())).thenReturn(Optional.empty());
        when(workspaceRepository.commitDsl(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("commit-1");
    }

    @Test
    void persistsWorkspaceScopeAndCommitsRoundTrippableTaxDslV2() throws Exception {
        WorkspaceContext context = new WorkspaceContext("alice", "workspace-a", "draft");
        when(repositoryFactory.resolveRepository(context)).thenReturn(workspaceRepository);

        RelationHypothesisDto dto = new RelationHypothesisDto(
                "CP-1023", "Communication Capability",
                "CR-1047", "Infrastructure Services",
                "REALIZES", 0.82, "Architecture compatibility rule");

        List<RelationHypothesis> persisted = service.persistFromAnalysis(
                List.of(dto), "analysis-1", context);

        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).getWorkspaceId()).isEqualTo("workspace-a");
        assertThat(persisted.get(0).getOwnerUsername()).isEqualTo("alice");

        ArgumentCaptor<String> dslCaptor = ArgumentCaptor.forClass(String.class);
        verify(workspaceRepository).commitDsl(
                org.mockito.ArgumentMatchers.eq("draft"),
                dslCaptor.capture(),
                org.mockito.ArgumentMatchers.eq("alice"),
                org.mockito.ArgumentMatchers.contains("analysis-1"));
        verify(repositoryFactory).resolveRepository(context);

        String dsl = dslCaptor.getValue();
        assertThat(dsl)
                .contains("meta {")
                .contains("version: \"2.1\";")
                .contains("element CP-1023 type Capability {")
                .contains("relation CP-1023 REALIZES CR-1047 {")
                .contains("status: provisional;");

        CanonicalArchitectureModel roundTripped = new AstToModelMapper().map(
                new TaxDslParser().parse(dsl, "hypotheses.taxdsl"));
        assertThat(roundTripped.getElements()).hasSize(2);
        assertThat(roundTripped.getRelations()).hasSize(1);
        assertThat(roundTripped.getRelations().get(0).getSourceId()).isEqualTo("CP-1023");
        assertThat(roundTripped.getRelations().get(0).getTargetId()).isEqualTo("CR-1047");
    }
}
