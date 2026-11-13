package com.taxonomy.composition.report;

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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(username = "decision-source-reader", roles = {"USER", "ARCHITECT"})
class CurrentDecisionReportSourceHttpTest {
    @Autowired MockMvc mvc;
    @Autowired SystemRepositoryRepository repositories;
    @Autowired UserWorkspaceRepository workspaces;
    @Autowired DslGitRepositoryFactory git;

    @Test
    void currentExportRetainsTheSelectedRepositoryBranchAndCommitWithoutAllocatingAWorkspace() throws Exception {
        String first = repository();
        String second = repository();
        String firstMain = commit(first, "main", "First");
        String firstDraft = commit(first, "review", "Draft");
        String secondMain = commit(second, "main", "Second");

        for (var source : new String[][] {{first, "main", firstMain}, {first, "review", firstDraft}, {second, "main", secondMain}}) {
            var response = mvc.perform(report().param("repositoryId", source[0]).param("workspaceId", "").param("branch", source[1])
                            .param("commitId", source[2]))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.metadata.repositoryId").value(source[0]))
                    .andExpect(jsonPath("$.metadata.branch").value(source[1]))
                    .andExpect(jsonPath("$.metadata.basedOnCommit").value(source[2]))
                    .andExpect(header().string("X-Taxonomy-Source-State", "READY"))
                    .andExpect(header().string("X-Taxonomy-Commit", source[2])).andReturn().getResponse();
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.getContentAsByteArray()));
            assertThat(response.getHeader("ETag")).isEqualTo("\"" + digest + "\"");
        }
        mvc.perform(report()).andExpect(status().isConflict());
        mvc.perform(report().param("repositoryId", first).param("workspaceId", "").param("branch", "main")
                .param("commitId", firstDraft)).andExpect(status().isConflict())
                .andExpect(header().string("X-Taxonomy-Source-State", "STALE"))
                .andExpect(header().string("X-Taxonomy-Commit", firstMain)).andExpect(content().string(""));
        mvc.perform(report().param("repositoryId", first).param("workspaceId", "").param("branch", "main")
                .param("commitId", "main")).andExpect(status().isBadRequest());
        mvc.perform(report().param("repositoryId", second).param("workspaceId", "").param("branch", "missing"))
                .andExpect(status().isNotFound()).andExpect(header().string("X-Taxonomy-Source-State", "MISSING"))
                .andExpect(header().doesNotExist("X-Taxonomy-Commit"));
        assertThat(workspaces.findByUsernameAndSharedFalse("decision-source-reader")).isEmpty();
    }

    private static MockHttpServletRequestBuilder report() {
        return post("/api/decision-report/json").with(csrf()).contentType("application/json")
                .content("""
                        {"scores":{"BP":60},"reasons":{"BP":"Owner assessment"},"businessText":"Compare selected repositories",
                         "provider":"MOCK","analysisStatus":"SUCCESS","language":"en"}
                        """);
    }

    private String repository() {
        String id = "report-source-" + UUID.randomUUID();
        var row = new SystemRepository();
        row.setRepositoryId(id);
        row.setStorageRepositoryName(id);
        row.setDisplayName(id);
        row.setSlug(id);
        row.setVisibility(RepositoryVisibility.PUBLIC);
        row.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        row.setDefaultBranch("main");
        row.setOwnerId("report-fixture-owner");
        row.setCreatedBy("report-fixture-owner");
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        repositories.saveAndFlush(row);
        return id;
    }

    private String commit(String repository, String branch, String name) throws Exception {
        return git.getCentralRepository(repository).commitDsl(branch,
                "element BP type Capability { title: \"" + name + "\"; }", "report-author", name);
    }
}
