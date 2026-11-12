package com.taxonomy.dsl;

import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(username = "hypothesis-source-reader", roles = {"USER", "ARCHITECT"})
class HypothesisSelectedSourceHttpTest {
    private static final String URL = "/api/dsl/hypotheses";
    private static final String COMMIT = "X-Taxonomy-Commit";
    private static final String STATE = "X-Taxonomy-Relation-Projection-State";
    @Autowired MockMvc mvc;
    @Autowired DslGitRepositoryFactory git;
    @Autowired SystemRepositoryRepository repositories;
    @Autowired RelationHypothesisRepository hypotheses;
    @Autowired UserWorkspaceRepository workspaces;

    @Test
    void twoPublicRepositoriesAndTwoBranchesReturnOnlyTheirCommittedDecision() throws Exception {
        String a = repository(RepositoryVisibility.PUBLIC), b = repository(RepositoryVisibility.PUBLIC);
        String hidden = repository(RepositoryVisibility.PRIVATE);
        var am = retained(a, "main");
        var ad = retained(a, "draft");
        var bm = retained(b, "main");
        String main = commit(a, "main", am.getId(), "accepted");
        String draft = commit(a, "draft", ad.getId(), "proposed");
        String other = commit(b, "main", bm.getId(), "rejected");

        mvc.perform(selected(hidden, "main")).andExpect(status().isNotFound()).andExpect(header().doesNotExist("ETag"));
        mvc.perform(get(URL)).andExpect(status().isConflict()).andExpect(header().doesNotExist("ETag"));
        String tag = mvc.perform(selected(a, "main").param("commitId", main))
                .andExpect(status().isOk()).andExpect(header().string(COMMIT, main))
                .andExpect(header().string("Content-Location", "?repositoryId=" + a + "&workspaceId=&branch=main&commitId=" + main))
                .andExpect(header().string(STATE, "READY")).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(am.getId())).andExpect(jsonPath("$[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$[0].confidence").value(0.9)).andReturn().getResponse().getHeader("ETag");
        assertThat(tag).matches("\"[0-9a-f]{64}\"");
        mvc.perform(selected(a, "main").header("If-None-Match", tag)).andExpect(status().isNotModified());
        mvc.perform(selected(a, "draft").param("commitId", draft)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ad.getId())).andExpect(jsonPath("$[0].status").value("PROPOSED"));
        mvc.perform(selected(b, "main").param("commitId", other)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(bm.getId())).andExpect(jsonPath("$[0].status").value("REJECTED"));
        mvc.perform(selected(a, "main").param("status", "REJECTED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(selected(a, "main").param("commitId", other)).andExpect(status().isConflict())
                .andExpect(header().string(STATE, "STALE")).andExpect(header().string(COMMIT, main))
                .andExpect(header().string("Link", org.hamcrest.Matchers.containsString("commitId=" + main)))
                .andExpect(header().doesNotExist("ETag")).andExpect(content().string(""));
        mvc.perform(selected(a, "main").param("commitId", "main")).andExpect(status().isBadRequest());
        mvc.perform(selected(a, "unknown")).andExpect(status().isNotFound()).andExpect(header().string(STATE,"MISSING"));

        String wrongId = commit(a, "main", bm.getId(), "accepted");
        mvc.perform(selected(a, "main")).andExpect(status().isConflict()).andExpect(header().string(COMMIT, wrongId))
                .andExpect(header().string(STATE, "MISSING")).andExpect(content().string(""));
        mvc.perform(selected(a, "main").param("view", "working")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(am.getId())).andExpect(jsonPath("$[0].status").value("PROVISIONAL"))
                .andExpect(header().doesNotExist(COMMIT)).andExpect(header().doesNotExist("ETag"));
        mvc.perform(selected(a, "main").param("view", "working").param("commitId", wrongId))
                .andExpect(status().isBadRequest());
        mvc.perform(get(URL + "/" + bm.getId() + "/evidence").param("workspaceId", "")
                .param("repositoryId", a).param("branch","main")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/" + am.getId() + "/evidence").param("workspaceId", "")
                .param("repositoryId", a).param("branch", "main").param("commitId", wrongId))
                .andExpect(status().isBadRequest());
        assertThat(workspaces.findByUsernameAndSharedFalse("hypothesis-source-reader")).isEmpty();
    }

    private static MockHttpServletRequestBuilder selected(String repository, String branch) {
        return get(URL).param("repositoryId", repository).param("branch", branch).param("workspaceId", "");
    }

    private String repository(RepositoryVisibility visibility) {
        var row = new SystemRepository();
        String id = "hyp-source-" + UUID.randomUUID();
        row.setRepositoryId(id);
        row.setStorageRepositoryName(id);
        row.setDisplayName(id);
        row.setSlug(id);
        row.setVisibility(visibility);
        row.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        row.setDefaultBranch("main");
        row.setOwnerId("fixture-owner");
        row.setCreatedBy("fixture-owner");
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        repositories.saveAndFlush(row);
        return id;
    }

    private RelationHypothesis retained(String repository, String branch) {
        var row = new RelationHypothesis();
        row.setRepositoryId(repository);
        row.setBranchName(branch);
        row.setSourceNodeId("A");
        row.setTargetNodeId("B");
        row.setRelationType(RelationType.RELATED_TO);
        row.setStatus(HypothesisStatus.PROVISIONAL);
        row.setAnalysisSessionId("overlapping-session");
        row.setConfidence(0.1);
        return hypotheses.saveAndFlush(row);
    }

    private String commit(String repository, String branch, long id, String status) throws Exception {
        return git.getCentralRepository(repository).commitDsl(branch, """
                relation A RELATED_TO B {
                  status: %s;
                  confidence: 0.9;
                  provenance: "hypothesis-review";
                  x-hypothesis-id: "%d";
                }
                """.formatted(status, id), "reviewer", "Review selected hypothesis");
    }
}
