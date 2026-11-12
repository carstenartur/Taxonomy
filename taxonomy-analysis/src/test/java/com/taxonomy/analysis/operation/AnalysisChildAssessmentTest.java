package com.taxonomy.analysis.operation;

import com.taxonomy.analysis.service.AnalysisFollowUpProviders;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.analysis.usecase.*;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.*;
import com.taxonomy.extension.api.llm.ProviderId;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisChildAssessmentTest {
    final TaxonomyService catalogue = mock(TaxonomyService.class);
    final LlmService llm = mock(LlmService.class);
    final LlmProviderConfig config = mock(LlmProviderConfig.class);
    final AnalyzeNodeChildrenUseCase nodes = mock(AnalyzeNodeChildrenUseCase.class);
    final JustifyLeafUseCase leaves = mock(JustifyLeafUseCase.class);
    final AnalysisNodeReceipts receipts = mock(AnalysisNodeReceipts.class);
    final WorkspaceContext workspace = new WorkspaceContext("owner", "workspace", "draft", "repo");
    final ObjectMapper json = new ObjectMapper();
    final String operationId = "00000000-0000-0000-0000-000000000004";
    final String source = "ab".repeat(32);

    AnalysisChildAssessment service() {
        var providers = new AnalysisFollowUpProviders(config);
        ReflectionTestUtils.setField(providers, "nodeReceipts", receipts);
        var service = new AnalysisChildAssessment(catalogue, llm, providers, nodes, leaves);
        ReflectionTestUtils.setField(service, "receipts", receipts);
        return service;
    }

    @Test void retryAndNewReplicaUseOriginalParentWithoutReplayingProviderPayloads() {
        var command = new AnalyzeNodeChildrenCommand("parent", "requirement", 65);
        var children = List.of(new TaxonomyNode());
        when(catalogue.getChildrenOf("parent")).thenReturn(children);
        when(llm.interactiveSource(children, true)).thenReturn(source);
        when(config.getActiveProviderId()).thenReturn(new ProviderId("GEMINI"), new ProviderId("OPENAI"));
        when(llm.interactiveDispatchAvailable(true)).thenReturn(true, false);
        when(nodes.analyze(command, children)).thenReturn(new AnalyzeNodeChildrenResult(Map.of("a", 65d), Map.of("a", "reason"),
                "private prompt", "private provider payload", "Gemini", 7, null));
        var admitted = new AtomicReference<AnalysisNodeReceipts.BoundNode>();
        var fingerprint = new AtomicReference<String>();
        when(receipts.executeNode(anyString(), anyBoolean(), any(), any(), any())).thenAnswer(call -> {
            if (admitted.get() == null) {
                assertThat((boolean) call.getArgument(1)).isTrue();
                assertThat(call.<BooleanSupplier>getArgument(4).getAsBoolean()).isTrue();
                fingerprint.set(call.getArgument(0));
                admitted.set(call.<Function<String, AnalysisNodeReceipts.BoundNode>>getArgument(2).apply(operationId));
            } else {
                assertThat(call.<String>getArgument(0)).isEqualTo(fingerprint.get());
                assertThat((boolean) call.getArgument(1)).isFalse();
            }
            return admitted.get().result();
        });
        var result = service().node(command, null, null, "owner", workspace, AnalysisReasonLength.SHORT, AnalysisLanguage.GERMAN);
        assertThat(service().node(command, null, null, "owner", workspace, AnalysisReasonLength.SHORT, AnalysisLanguage.GERMAN)).isEqualTo(result);
        assertThat(result.analysisOperationId()).isEqualTo(operationId);
        assertThat(result.analysisProviderId()).isEqualTo("GEMINI");
        assertThat(result.scores()).containsEntry("a", 65d);
        verify(nodes).analyze(command, children);
        var archive = json.valueToTree(admitted.get());
        assertThat(archive.toString()).doesNotContain("private prompt", "private provider payload");
        ChildAnalysisReceipt.validate("NODE_CHILDREN", operationId, archive);
        assertThat(ChildAnalysisReceipt.publicResult("NODE_CHILDREN", archive).has("parentBinding")).isFalse();
        when(receipts.readParent(operationId)).thenReturn(admitted.get().parentBinding());
        var restored = new AnalysisFollowUpProviders(config);
        ReflectionTestUtils.setField(restored, "nodeReceipts", receipts);
        var pin = restored.bind(operationId, null, "requirement", "owner", workspace, AnalysisReasonLength.STANDARD, AnalysisLanguage.ENGLISH);
        assertThat(pin.provider()).isEqualTo("GEMINI");
        assertThat(pin.analysisLanguage()).isEqualTo(AnalysisLanguage.GERMAN);
        assertThatThrownBy(() -> restored.bind(operationId, null, "different text", "owner", workspace,
                AnalysisReasonLength.STANDARD, AnalysisLanguage.ENGLISH)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void optionalParentUsesTheAuthenticatedWorkspaceEvenForANewAnalysis() {
        var providers = new AnalysisFollowUpProviders(config);
        var cluster = mock(com.taxonomy.analysis.cluster.ClusterAnalysisStore.class);
        ReflectionTestUtils.setField(providers, "cluster", cluster);
        assertThat(providers.openParentCatalogue(null, "owner", workspace)).isNull();
        assertThat(providers.parentCurrent(null, "owner", workspace)).isTrue();
        verify(cluster, times(2)).findAuthorized(null, "owner", workspace);
        assertThatThrownBy(() -> providers.openParentCatalogue(null, "other", workspace))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(providers.parentCurrent(null, "other", workspace)).isFalse();
        verifyNoMoreInteractions(cluster);
    }

    @Test void freshEmptyNodeDoesNotReserveProviderWork() {
        var command = new AnalyzeNodeChildrenCommand("leaf", "requirement", 100);
        when(config.getActiveProviderId()).thenReturn(new ProviderId("GEMINI"));
        when(catalogue.getChildrenOf("leaf")).thenReturn(List.of());
        when(llm.interactiveSource(List.of(), true)).thenReturn(source);
        when(nodes.analyze(command, List.of())).thenReturn(AnalyzeNodeChildrenResult.empty());
        when(receipts.executeNode(anyString(), eq(false), any(), any(), any()))
                .thenAnswer(call -> call.<Supplier<AnalyzeNodeChildrenResult>>getArgument(3).get());
        var result = service().node(command, null, null, "owner", workspace, AnalysisReasonLength.STANDARD, AnalysisLanguage.ENGLISH);
        assertThat(result.scores()).isEmpty();
        assertThat(result.analysisOperationId()).isNotBlank();
        verify(llm, never()).interactiveDispatchAvailable(anyBoolean());
    }
}
