package com.taxonomy.relations.controller;

import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.service.HypothesisService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class HypothesisApiControllerTest {

    @Mock private HypothesisService hypothesisService;
    @Mock private WorkspaceResolver workspaceResolver;
    private HypothesisApiController controller;
    private final RepositoryContext context = RepositoryContext.workspace("repo-a", "ws-1", "draft", "alice");

    @BeforeEach
    void setUp() {
        controller = new HypothesisApiController(hypothesisService, workspaceResolver);
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(context);
    }

    @Test
    void repositoryResolutionFailuresPropagateBeforeEveryHypothesisOperation() {
        IllegalArgumentException failure = new IllegalArgumentException("Selected workspace is unavailable");
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenThrow(failure);

        assertThatThrownBy(() -> controller.listHypotheses(null)).isSameAs(failure);
        assertThatThrownBy(() -> controller.listHypotheses(HypothesisStatus.PROVISIONAL)).isSameAs(failure);
        assertThatThrownBy(() -> controller.acceptHypothesis(42L)).isSameAs(failure);
        assertThatThrownBy(() -> controller.rejectHypothesis(42L)).isSameAs(failure);
        assertThatThrownBy(() -> controller.applyHypothesisForSession(42L)).isSameAs(failure);
        assertThatThrownBy(() -> controller.getHypothesisEvidence(42L)).isSameAs(failure);

        verifyNoInteractions(hypothesisService);
    }

    @Test
    void missingRepositoryContextFailsClosed() {
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(null);

        assertThatThrownBy(() -> controller.listHypotheses(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Repository context resolver returned null for a hypothesis operation");

        verifyNoInteractions(hypothesisService);
    }

    @Test
    void mvcRetainsAllEstablishedHypothesisRoutesAndTheExactRepositoryContext() throws Exception {
        var mvc = standaloneSetup(controller).build();
        when(hypothesisService.findAll(context)).thenReturn(List.of());
        when(hypothesisService.findByStatus(HypothesisStatus.PROVISIONAL, context)).thenReturn(List.of());
        when(hypothesisService.accept(42L, context)).thenThrow(new IllegalArgumentException("missing"));
        when(hypothesisService.reject(42L, context)).thenThrow(new IllegalArgumentException("missing"));
        when(hypothesisService.applyForSession(42L, context)).thenThrow(new IllegalArgumentException("missing"));
        when(hypothesisService.findEvidence(42L, context)).thenReturn(List.of());

        mvc.perform(get("/api/dsl/hypotheses")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/dsl/hypotheses").param("status", "PROVISIONAL"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(post("/api/dsl/hypotheses/42/accept")).andExpect(status().isNotFound());
        mvc.perform(post("/api/dsl/hypotheses/42/reject")).andExpect(status().isNotFound());
        mvc.perform(post("/api/dsl/hypotheses/42/apply-session")).andExpect(status().isNotFound());
        mvc.perform(get("/api/dsl/hypotheses/42/evidence"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));

        verify(hypothesisService).findAll(context);
        verify(hypothesisService).findByStatus(HypothesisStatus.PROVISIONAL, context);
        verify(hypothesisService).accept(42L, context);
        verify(hypothesisService).reject(42L, context);
        verify(hypothesisService).applyForSession(42L, context);
        verify(hypothesisService).findEvidence(42L, context);
    }

    @Test
    void hypothesesCoverSuccessAndAllExpectedErrors() {
        RelationHypothesis provisional = hypothesis(7L, HypothesisStatus.PROVISIONAL, false);
        RelationHypothesis accepted = hypothesis(7L, HypothesisStatus.ACCEPTED, false);
        RelationHypothesis rejected = hypothesis(8L, HypothesisStatus.REJECTED, false);
        RelationHypothesis applied = hypothesis(9L, HypothesisStatus.PROVISIONAL, true);

        when(hypothesisService.findAll(context)).thenReturn(List.of(provisional));
        when(hypothesisService.findByStatus(HypothesisStatus.PROVISIONAL, context)).thenReturn(List.of(provisional));
        assertThat(controller.listHypotheses(null).getBody()).containsExactly(provisional);
        assertThat(controller.listHypotheses(HypothesisStatus.PROVISIONAL).getBody()).containsExactly(provisional);

        when(hypothesisService.accept(7L, context)).thenReturn(accepted);
        assertThat(controller.acceptHypothesis(7L).getBody())
                .containsEntry("status", "ACCEPTED").containsEntry("relationType", "RELATED_TO");
        when(hypothesisService.accept(404L, context)).thenThrow(new IllegalArgumentException("missing"));
        assertThat(controller.acceptHypothesis(404L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        when(hypothesisService.accept(10L, context)).thenThrow(new IllegalStateException("already final"));
        assertThat(controller.acceptHypothesis(10L).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        when(hypothesisService.reject(8L, context)).thenReturn(rejected);
        assertThat(controller.rejectHypothesis(8L).getBody()).containsEntry("status", "REJECTED");
        when(hypothesisService.reject(404L, context)).thenThrow(new IllegalArgumentException("missing"));
        assertThat(controller.rejectHypothesis(404L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        when(hypothesisService.reject(10L, context)).thenThrow(new IllegalStateException("already final"));
        assertThat(controller.rejectHypothesis(10L).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        when(hypothesisService.applyForSession(9L, context)).thenReturn(applied);
        assertThat(controller.applyHypothesisForSession(9L).getBody())
                .containsEntry("appliedInCurrentAnalysis", true);
        when(hypothesisService.applyForSession(404L, context)).thenThrow(new IllegalArgumentException("missing"));
        assertThat(controller.applyHypothesisForSession(404L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        when(hypothesisService.findEvidence(7L, context)).thenReturn(List.of());
        assertThat(controller.getHypothesisEvidence(7L).getStatusCode()).isEqualTo(HttpStatus.OK);
        when(hypothesisService.findEvidence(404L, context)).thenThrow(new IllegalArgumentException("missing"));
        assertThat(controller.getHypothesisEvidence(404L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static RelationHypothesis hypothesis(Long id, HypothesisStatus status, boolean applied) {
        RelationHypothesis hypothesis = new RelationHypothesis();
        hypothesis.setId(id);
        hypothesis.setSourceNodeId("A");
        hypothesis.setTargetNodeId("B");
        hypothesis.setRelationType(RelationType.RELATED_TO);
        hypothesis.setStatus(status);
        hypothesis.setAppliedInCurrentAnalysis(applied);
        return hypothesis;
    }
}
