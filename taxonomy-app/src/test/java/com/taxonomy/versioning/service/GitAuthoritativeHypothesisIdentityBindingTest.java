package com.taxonomy.versioning.service;

import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.SystemRepositoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@WithMockUser(roles = "ADMIN")
class GitAuthoritativeHypothesisIdentityBindingTest {

    @Autowired
    private HypothesisService hypothesisService;

    @Autowired
    private SystemRepositoryService repositoryService;

    @Test
    void newAndRetriedAnalysisDtosReceiveTheSamePersistedIdentity() {
        assertThat(hypothesisService)
                .isInstanceOf(GitAuthoritativeHypothesisService.class);
        SystemRepository primary = repositoryService.getPrimaryRepository();
        RepositoryContext context = RepositoryContext.centralWrite(
                primary.getRepositoryId(),
                primary.getDefaultBranch(),
                "system");
        String sessionId = "identity-binding-" + UUID.randomUUID();

        RelationHypothesisDto firstDto = hypothesis();
        var first = hypothesisService.persistFromAnalysis(
                List.of(firstDto), sessionId, context);

        assertThat(first).hasSize(1);
        assertThat(firstDto.getHypothesisId())
                .isEqualTo(first.getFirst().getId());

        RelationHypothesisDto retriedDto = hypothesis();
        var retry = hypothesisService.persistFromAnalysis(
                List.of(retriedDto), sessionId, context);

        assertThat(retry).isEmpty();
        assertThat(retriedDto.getHypothesisId())
                .isEqualTo(firstDto.getHypothesisId());
    }

    private static RelationHypothesisDto hypothesis() {
        return new RelationHypothesisDto(
                "CR",
                "Core Services",
                "CO",
                "Combat Organisation",
                "REALIZES",
                0.82,
                "Stable analysis evidence");
    }
}
