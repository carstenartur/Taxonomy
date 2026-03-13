package com.taxonomy.dsl;

import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationHypothesis;
import com.taxonomy.model.RelationType;
import com.taxonomy.repository.RelationHypothesisRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the DSL API endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DslApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RelationHypothesisRepository hypothesisRepository;

    @Test
    void exportCurrentArchitectureReturnsText() throws Exception {
        mockMvc.perform(get("/api/dsl/export"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN));
    }

    @Test
    void exportWithCustomNamespace() throws Exception {
        mockMvc.perform(get("/api/dsl/export").param("namespace", "test.namespace"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("namespace: \"test.namespace\";")));
    }

    @Test
    void parseValidDslReturnsJsonResult() throws Exception {
        String dsl = """
                element CP-1023 type Capability {
                  title: "Test";
                }
                
                relation CP-1023 REALIZES BP-1327 {
                  status: accepted;
                }
                """;

        mockMvc.perform(post("/api/dsl/parse")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elements").value(1))
                .andExpect(jsonPath("$.relations").value(1));
    }

    @Test
    void parseEmptyDslReturnsValidResult() throws Exception {
        mockMvc.perform(post("/api/dsl/parse")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.elements").value(0));
    }

    @Test
    void validateValidDslReturnsNoErrors() throws Exception {
        String dsl = """
                element CP-1023 type Capability {
                  title: "Test";
                }
                """;

        mockMvc.perform(post("/api/dsl/validate")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void validateDslWithDuplicateIdsReturnsErrors() throws Exception {
        String dsl = """
                element CP-1023 type Capability {
                  title: "First";
                }
                
                element CP-1023 type Capability {
                  title: "Duplicate";
                }
                """;

        mockMvc.perform(post("/api/dsl/validate")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("Duplicate")));
    }

    @Test
    void listHypothesesReturnsArray() throws Exception {
        mockMvc.perform(get("/api/dsl/hypotheses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void acceptNonExistentHypothesisReturns404() throws Exception {
        mockMvc.perform(post("/api/dsl/hypotheses/99999/accept"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectNonExistentHypothesisReturns404() throws Exception {
        mockMvc.perform(post("/api/dsl/hypotheses/99999/reject"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listDocumentsReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/dsl/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // --- New endpoints ---

    @Test
    void getCurrentArchitectureReturnsStructuredJson() throws Exception {
        mockMvc.perform(get("/api/dsl/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elements").isArray())
                .andExpect(jsonPath("$.relations").isArray())
                .andExpect(jsonPath("$.requirements").isArray())
                .andExpect(jsonPath("$.mappings").isArray())
                .andExpect(jsonPath("$.views").isArray())
                .andExpect(jsonPath("$.evidence").isArray());
    }

    @Test
    void materializeDslWithInvalidContentReturnsBadRequest() throws Exception {
        String dsl = """
                element CP-1023 type Capability {
                  title: "First";
                }
                
                element CP-1023 type Capability {
                  title: "Duplicate";
                }
                """;

        mockMvc.perform(post("/api/dsl/materialize")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void materializeValidDslReturnsSuccess() throws Exception {
        // Use real taxonomy node codes that exist in the test database
        String dsl = """
                meta {
                  language: "taxdsl";
                  version: "2.0";
                  namespace: "test";
                }
                
                element CP-1010 type Capability {
                  title: "Test Capability";
                }
                
                element BP-1327 type Process {
                  title: "Test Process";
                }
                """;

        mockMvc.perform(post("/api/dsl/materialize")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.documentId").isNotEmpty());
    }

    @Test
    void acceptHypothesisCreatesRelation() throws Exception {
        // Use root-level codes with a relation type not preloaded from CSV
        RelationHypothesis h = new RelationHypothesis();
        h.setSourceNodeId("UA");
        h.setTargetNodeId("BP");
        h.setRelationType(RelationType.REALIZES);
        h.setStatus(HypothesisStatus.PROVISIONAL);
        h.setConfidence(0.85);
        h.setAnalysisSessionId("test-session-accept");
        RelationHypothesis saved = hypothesisRepository.save(h);

        mockMvc.perform(post("/api/dsl/hypotheses/" + saved.getId() + "/accept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.sourceNodeId").value("UA"))
                .andExpect(jsonPath("$.targetNodeId").value("BP"));
    }

    @Test
    void rejectHypothesisUpdatesStatus() throws Exception {
        RelationHypothesis h = new RelationHypothesis();
        h.setSourceNodeId("BP");
        h.setTargetNodeId("CP");
        h.setRelationType(RelationType.SUPPORTS);
        h.setStatus(HypothesisStatus.PROVISIONAL);
        h.setConfidence(0.50);
        h.setAnalysisSessionId("test-session-2");
        RelationHypothesis saved = hypothesisRepository.save(h);

        mockMvc.perform(post("/api/dsl/hypotheses/" + saved.getId() + "/reject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void acceptAlreadyAcceptedHypothesisReturnsBadRequest() throws Exception {
        RelationHypothesis h = new RelationHypothesis();
        h.setSourceNodeId("BP");
        h.setTargetNodeId("CP");
        h.setRelationType(RelationType.DEPENDS_ON);
        h.setStatus(HypothesisStatus.ACCEPTED);
        h.setConfidence(0.60);
        h.setAnalysisSessionId("test-session-3");
        RelationHypothesis saved = hypothesisRepository.save(h);

        mockMvc.perform(post("/api/dsl/hypotheses/" + saved.getId() + "/accept"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void getHypothesisEvidenceReturnsArray() throws Exception {
        RelationHypothesis h = new RelationHypothesis();
        h.setSourceNodeId("BP");
        h.setTargetNodeId("CP");
        h.setRelationType(RelationType.RELATED_TO);
        h.setStatus(HypothesisStatus.PROVISIONAL);
        h.setConfidence(0.50);
        h.setAnalysisSessionId("test-session-4");
        RelationHypothesis saved = hypothesisRepository.save(h);

        mockMvc.perform(get("/api/dsl/hypotheses/" + saved.getId() + "/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void listHypothesesFilteredByStatus() throws Exception {
        RelationHypothesis h1 = new RelationHypothesis();
        h1.setSourceNodeId("BP");
        h1.setTargetNodeId("CP");
        h1.setRelationType(RelationType.FULFILLS);
        h1.setStatus(HypothesisStatus.PROPOSED);
        h1.setConfidence(0.70);
        h1.setAnalysisSessionId("test-filter");
        hypothesisRepository.save(h1);

        mockMvc.perform(get("/api/dsl/hypotheses").param("status", "PROPOSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // --- Commit / History / Diff / Branch endpoints ---

    @Test
    void commitDslStoresVersionedDocument() throws Exception {
        String dsl = """
                meta {
                  language: "taxdsl";
                  version: "2.0";
                  namespace: "test.commit";
                }
                
                element CP-2001 type Capability {
                  title: "Commit Test";
                }
                """;

        mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl)
                        .param("branch", "draft")
                        .param("author", "test-user")
                        .param("message", "initial commit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commitId").isNotEmpty())
                .andExpect(jsonPath("$.branch").value("draft"))
                .andExpect(jsonPath("$.author").value("test-user"))
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void commitInvalidDslReturnsBadRequest() throws Exception {
        String dsl = """
                element CP-1023 type Capability {
                  title: "First";
                }
                
                element CP-1023 type Capability {
                  title: "Duplicate";
                }
                """;

        mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl)
                        .param("branch", "draft"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void commitDefaultsToDraftBranch() throws Exception {
        String dsl = """
                element CP-3001 type Capability {
                  title: "Default Branch Test";
                }
                """;

        mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branch").value("draft"));
    }

    @Test
    void getHistoryReturnsDocumentsForBranch() throws Exception {
        // First commit
        String dsl1 = """
                element CP-4001 type Capability {
                  title: "History V1";
                }
                """;
        mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl1)
                        .param("branch", "history-test"));

        // Second commit
        String dsl2 = """
                element CP-4001 type Capability {
                  title: "History V2";
                }
                element CP-4002 type Capability {
                  title: "Added";
                }
                """;
        mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl2)
                        .param("branch", "history-test"));

        mockMvc.perform(get("/api/dsl/history").param("branch", "history-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void getHistoryEmptyBranchReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/dsl/history").param("branch", "nonexistent-branch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void diffBetweenTwoDocuments() throws Exception {
        // Commit two different versions and then diff them using Git SHAs
        String dsl1 = """
                element CP-5001 type Capability {
                  title: "V1";
                }
                """;
        String dsl2 = """
                element CP-5001 type Capability {
                  title: "V2 Changed";
                }
                element CP-5002 type Capability {
                  title: "V2 Added";
                }
                """;

        // Commit both to get Git SHAs
        var result1 = mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl1)
                        .param("branch", "diff-test"))
                .andReturn();
        String commitId1 = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result1.getResponse().getContentAsString())
                .get("commitId").asText();

        var result2 = mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl2)
                        .param("branch", "diff-test"))
                .andReturn();
        String commitId2 = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result2.getResponse().getContentAsString())
                .get("commitId").asText();

        mockMvc.perform(get("/api/dsl/diff/" + commitId1 + "/" + commitId2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalChanges").isNumber())
                .andExpect(jsonPath("$.isEmpty").value(false))
                .andExpect(jsonPath("$.addedElements").value(1))
                .andExpect(jsonPath("$.changedElements").value(1));
    }

    @Test
    void diffWithNonExistentDocReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/dsl/diff/99999/99998"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void listBranchesReturnsArray() throws Exception {
        mockMvc.perform(get("/api/dsl/branches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void createBranchForksDocument() throws Exception {
        // First create a commit on the source branch
        String dsl = """
                element CP-6001 type Capability {
                  title: "Branch Source";
                }
                """;
        mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl)
                        .param("branch", "branch-source"))
                .andExpect(status().isOk());

        // Create a new branch forked from that branch
        mockMvc.perform(post("/api/dsl/branches")
                        .param("name", "review-branch")
                        .param("fromBranch", "branch-source"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branch").value("review-branch"))
                .andExpect(jsonPath("$.commitId").isNotEmpty())
                .andExpect(jsonPath("$.forkedFrom").value("branch-source"));
    }

    @Test
    void createBranchFromNonExistentBranchReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/dsl/branches")
                        .param("name", "bad-branch")
                        .param("fromBranch", "nonexistent-branch"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void incrementalMaterializationEndpoint() throws Exception {
        // Use materialize endpoint (not commit) to get document IDs,
        // since materialize stores ArchitectureDslDocument in JPA
        String dsl1 = """
                element CP-7001 type Capability {
                  title: "Incr V1";
                }
                """;
        String dsl2 = """
                element CP-7001 type Capability {
                  title: "Incr V1";
                }
                element CP-7002 type Capability {
                  title: "Incr Added";
                }
                """;

        var result1 = mockMvc.perform(post("/api/dsl/materialize")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl1))
                .andReturn();
        Long docId1 = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result1.getResponse().getContentAsString())
                .get("documentId").asLong();

        var result2 = mockMvc.perform(post("/api/dsl/materialize")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl2))
                .andReturn();
        Long docId2 = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result2.getResponse().getContentAsString())
                .get("documentId").asLong();

        mockMvc.perform(post("/api/dsl/materialize-incremental")
                        .param("beforeDocId", docId1.toString())
                        .param("afterDocId", docId2.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    // --- Cherry-pick, merge, and text diff endpoints ---

    @Test
    void textDiffBetweenTwoCommits() throws Exception {
        String dsl1 = """
                element CP-8001 type Capability {
                  title: "TextDiff V1";
                }
                """;
        String dsl2 = """
                element CP-8001 type Capability {
                  title: "TextDiff V2 Changed";
                }
                element CP-8002 type Capability {
                  title: "TextDiff Added";
                }
                """;

        var result1 = mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl1)
                        .param("branch", "textdiff-test"))
                .andReturn();
        String commitId1 = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result1.getResponse().getContentAsString())
                .get("commitId").asText();

        var result2 = mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl2)
                        .param("branch", "textdiff-test"))
                .andReturn();
        String commitId2 = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result2.getResponse().getContentAsString())
                .get("commitId").asText();

        mockMvc.perform(get("/api/dsl/diff/text/" + commitId1 + "/" + commitId2))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN));
    }

    @Test
    void textDiffWithInvalidCommitReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/dsl/diff/text/0000000000000000000000000000000000000000/0000000000000000000000000000000000000001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void cherryPickCommitOntoNewBranch() throws Exception {
        // Create a common base commit
        String baseDsl = """
                element CP-9000 type Capability {
                  title: "Cherry Base";
                }
                """;
        mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(baseDsl)
                        .param("branch", "cherry-source"))
                .andExpect(status().isOk());

        // Fork cherry-target from cherry-source (they share the base)
        mockMvc.perform(post("/api/dsl/branches")
                        .param("name", "cherry-target")
                        .param("fromBranch", "cherry-source"))
                .andExpect(status().isOk());

        // Add a second commit on cherry-source (this is the one we'll cherry-pick)
        String dsl2 = """
                element CP-9000 type Capability {
                  title: "Cherry Base";
                }
                element CP-9001 type Capability {
                  title: "Cherry Source Added";
                }
                """;
        var commitResult = mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl2)
                        .param("branch", "cherry-source"))
                .andReturn();
        String commitId = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(commitResult.getResponse().getContentAsString())
                .get("commitId").asText();

        // Cherry-pick the second commit onto cherry-target
        mockMvc.perform(post("/api/dsl/cherry-pick")
                        .param("commitId", commitId)
                        .param("targetBranch", "cherry-target"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commitId").isNotEmpty())
                .andExpect(jsonPath("$.targetBranch").value("cherry-target"))
                .andExpect(jsonPath("$.cherryPickedFrom").value(commitId));
    }

    @Test
    void cherryPickInvalidCommitReturnsBadRequest() throws Exception {
        // Target branch must exist first
        String dsl = """
                element CP-9010 type Capability {
                  title: "Target for bad cherry-pick";
                }
                """;
        mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl)
                        .param("branch", "cherry-bad-target"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/dsl/cherry-pick")
                        .param("commitId", "0000000000000000000000000000000000000000")
                        .param("targetBranch", "cherry-bad-target"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void mergeBranchesSucceeds() throws Exception {
        // Create a common base commit
        String baseDsl = """
                element CP-9100 type Capability {
                  title: "Merge Base";
                }
                """;
        mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(baseDsl)
                        .param("branch", "merge-from"))
                .andExpect(status().isOk());

        // Fork merge-into from merge-from (they share the base)
        mockMvc.perform(post("/api/dsl/branches")
                        .param("name", "merge-into")
                        .param("fromBranch", "merge-from"))
                .andExpect(status().isOk());

        // Add a second commit on merge-from
        String dsl2 = """
                element CP-9100 type Capability {
                  title: "Merge Base";
                }
                element CP-9101 type Capability {
                  title: "From Branch Added";
                }
                """;
        mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl2)
                        .param("branch", "merge-from"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/dsl/merge")
                        .param("fromBranch", "merge-from")
                        .param("intoBranch", "merge-into"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commitId").isNotEmpty())
                .andExpect(jsonPath("$.fromBranch").value("merge-from"))
                .andExpect(jsonPath("$.intoBranch").value("merge-into"));
    }

    @Test
    void mergeNonExistentBranchReturnsBadRequest() throws Exception {
        // Create target branch
        String dsl = """
                element CP-9110 type Capability {
                  title: "Merge target";
                }
                """;
        mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl)
                        .param("branch", "merge-existing"));

        mockMvc.perform(post("/api/dsl/merge")
                        .param("fromBranch", "nonexistent-merge-branch")
                        .param("intoBranch", "merge-existing"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void getGitHeadReturnsDslContent() throws Exception {
        String dsl = """
                element CP-9201 type Capability {
                  title: "Git Head Test";
                }
                """;
        mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl)
                        .param("branch", "git-head-test"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/dsl/git/head").param("branch", "git-head-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branch").value("git-head-test"))
                .andExpect(jsonPath("$.dslText").isNotEmpty())
                .andExpect(jsonPath("$.length").isNumber());
    }

    @Test
    void getGitHeadNonExistentBranchReturns404() throws Exception {
        mockMvc.perform(get("/api/dsl/git/head").param("branch", "nonexistent-git-branch"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getGitCommitReturnsDslContent() throws Exception {
        String dsl = """
                element CP-9301 type Capability {
                  title: "Git Commit Test";
                }
                """;
        var result = mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl)
                        .param("branch", "git-commit-test"))
                .andReturn();
        String commitId = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result.getResponse().getContentAsString())
                .get("commitId").asText();

        mockMvc.perform(get("/api/dsl/git/commit/" + commitId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commitId").value(commitId))
                .andExpect(jsonPath("$.dslText").isNotEmpty());
    }

    // --- History index + search endpoints ---

    @Test
    void indexAndSearchHistory() throws Exception {
        String dsl = """
                element CP-9401 type Capability {
                  title: "Indexable Cap";
                }
                relation CP-9401 REALIZES CR-9402 {
                  status: proposed;
                }
                element CR-9402 type CoreService {
                  title: "Indexable Svc";
                }
                """;
        mockMvc.perform(post("/api/dsl/commit")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(dsl)
                        .param("branch", "index-test"))
                .andExpect(status().isOk());

        // Index the branch
        mockMvc.perform(post("/api/dsl/history/index")
                        .param("branch", "index-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(1));

        // Search by element ID
        mockMvc.perform(get("/api/dsl/history/element/CP-9401"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // Full-text search
        mockMvc.perform(get("/api/dsl/history/search")
                        .param("query", "CP-9401"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void applyHypothesisForSession() throws Exception {
        // Create a hypothesis
        RelationHypothesis h = new RelationHypothesis();
        h.setSourceNodeId("BP");
        h.setTargetNodeId("CP");
        h.setRelationType(RelationType.SUPPORTS);
        h.setStatus(HypothesisStatus.PROVISIONAL);
        h.setConfidence(0.80);
        h.setAnalysisSessionId("session-apply-test");
        h = hypothesisRepository.save(h);

        mockMvc.perform(post("/api/dsl/hypotheses/" + h.getId() + "/apply-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedInCurrentAnalysis").value(true))
                .andExpect(jsonPath("$.status").value("PROVISIONAL"));
    }

    @Test
    void applyHypothesisForSessionNotFound() throws Exception {
        mockMvc.perform(post("/api/dsl/hypotheses/999999/apply-session"))
                .andExpect(status().isNotFound());
    }
}
