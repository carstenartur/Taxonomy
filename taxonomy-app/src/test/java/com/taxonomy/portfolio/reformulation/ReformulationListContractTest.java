package com.taxonomy.portfolio.reformulation;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reuses the live HTTP/database fixture and its inherited scope-isolation checks. */
class ReformulationListContractTest extends ReformulationIsolationTest {

    @Test
    void listsOnlyMetadataAndLoadsFrozenEvidenceSeparately() throws Exception {
        String first = create().path("id").asText();
        String second = create().path("id").asText();
        mvc.perform(post(base() + "/" + first + "/revisions").with(csrf())
                .header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("text", "Edited draft", "rationale", "Explicit edit"))))
                .andExpect(status().isCreated());

        var listed = json.readTree(mvc.perform(get(base())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(listed.size()).isEqualTo(2);
        var ids = new java.util.HashSet<String>();
        for (var summary : listed) {
            ids.add(summary.path("id").asText());
            assertThat(summary.has("baseline")).isFalse();
            assertThat(summary.has("text")).isFalse();
            assertThat(summary.has("frozenContext")).isFalse();
            assertThat(summary.path("currentRevision").isIntegralNumber()).isTrue();
            assertThat(summary.path("currentRevision").asLong())
                    .isEqualTo(summary.path("id").asText().equals(first) ? 2 : 1);
            assertThat(summary.path("sourceVersionId").asLong()).isEqualTo(requirement.currentVersionId());
            assertThat(summary.path("snapshotId").asText()).isEqualTo(snapshot);
        }
        assertThat(ids).containsExactlyInAnyOrder(first, second);
        var detail = json.readTree(mvc.perform(get(base() + "/" + first))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(detail.at("/baseline/originalText").asText()).isEqualTo(ORIGINAL);
        assertThat(detail.at("/baseline/snapshotPayload").asText()).contains("Frozen catalogue description");
        assertThat(detail.at("/currentRevision/text").asText()).isEqualTo("Edited draft");

        var other = createRequirement("LIST-OTHER");
        var empty = json.readTree(mvc.perform(get("/api/projects/" + project.id()
                        + "/requirements/" + other.id() + "/reformulations"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(empty.size()).isZero();
    }

    @Test
    void unevaluatedFindingRetainsItsWarningWithoutDuplicatingTheOriginal() throws Exception {
        var proposal = create();
        assertThat(proposal.at("/currentRevision/validation/findings/0/code").asText())
                .isEqualTo("NOT_SYNTHESIZED");
        assertThat(proposal.at("/currentRevision/validation/findings/0/sourceSpans").size()).isZero();
        String id = proposal.path("id").asText();
        var saved = json.readTree(mvc.perform(post(base() + "/" + id + "/revisions").with(csrf())
                        .header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("text", "Short draft", "rationale", "Human edit"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(saved.at("/baseline/originalText").asText()).isEqualTo(ORIGINAL);
        assertThat(saved.path("currentRevision").toString()).doesNotContain(ORIGINAL);
        var persisted = json.readTree(mvc.perform(get(base() + "/" + id + "/revisions/2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(persisted.path("text").asText()).isEqualTo("Short draft");
        assertThat(persisted.at("/validation/findings/0/sourceSpans").size()).isZero();
    }
}
