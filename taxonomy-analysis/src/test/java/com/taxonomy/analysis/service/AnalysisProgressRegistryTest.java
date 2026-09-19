package com.taxonomy.analysis.service;

import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class AnalysisProgressRegistryTest {
    private final AnalysisProgressRegistry registry = new AnalysisProgressRegistry(new StandardEnvironment());
    private final WorkspaceContext scope = new WorkspaceContext("alice", "work-a", "draft", "repo-a");
    @Test void progressIsVisibleBeforeTheProviderReturns() {
        try(var handle=registry.open(null,"alice",scope,null)) {
            AnalysisRunControl.call("MOCK","CP", () -> {
                var snapshot=registry.snapshot(handle.id(),"alice",scope);
                assertThat(snapshot.status()).isEqualTo("RUNNING");
                assertThat(snapshot.calls()).hasSize(1);
                assertThat(snapshot.calls().getFirst().status()).isEqualTo("STARTED");
                return detail("CP", "prompt", "response");
            });
            var snapshot=registry.snapshot(handle.id(),"alice",scope);
            assertThat(snapshot.rawScores()).containsEntry("CP",80);
            assertThat(snapshot.calls().getFirst().status()).isEqualTo("COMPLETED");
            handle.finish("SUCCESS");
            assertThat(registry.snapshot(handle.id(),"alice",scope).status()).isEqualTo("COMPLETED");
        }
        assertThat(AnalysisRunControl.active()).isFalse();
    }
    @Test void ownerWorkspaceRepositoryAndBranchAreAllRequired() {
        try(var handle=registry.open(null,"alice",scope,null)) {
            assertThatThrownBy(() -> registry.snapshot(handle.id(),"bob",scope)).hasMessageContaining("404");
            for(var other:new WorkspaceContext[]{
                    new WorkspaceContext("alice","work-b","draft","repo-a"),
                    new WorkspaceContext("alice","work-a","draft","repo-b"),
                    new WorkspaceContext("alice","work-a","other","repo-a")}) {
                assertThatThrownBy(() -> registry.snapshot(handle.id(),"alice",other)).hasMessageContaining("404");
                assertThatThrownBy(() -> registry.cancel(handle.id(),"alice",other)).hasMessageContaining("404");
            }
            assertThat(registry.snapshot(handle.id(),"alice",scope).status()).isEqualTo("RUNNING");
        }
    }
    @Test void duplicateIdsNeverStartASecondAnalysis() {
        String id=UUID.randomUUID().toString();
        try(var first=registry.open(id,"alice",scope,null)) {
            assertThatThrownBy(() -> registry.open(id,"alice",scope,null)).hasMessageContaining("409");
        }
    }
    @Test void cancellingStopsTheNextCallWithoutExecutingItsBody() {
        try(var handle=registry.open(null,"alice",scope,null)) {
            registry.cancel(handle.id(),"alice",scope);
            assertThatThrownBy(() -> AnalysisRunControl.call("MOCK","CP", () -> {
                throw new AssertionError("provider must not be called");
            })).isInstanceOf(AnalysisStoppedException.class).hasMessageContaining("CANCELLED");
        }
        assertThat(AnalysisRunControl.active()).isFalse();
    }
    @Test void retainedCallsAndRawTranscriptsStayBounded() {
        try(var handle=registry.open(null,"alice",scope,null)) {
            for(int i=0;i<100;i++) {
                final int index=i;
                AnalysisRunControl.call("MOCK","CP", () -> detail("CP-"+index,"p".repeat(20000),"r".repeat(20000)));
            }
            var snapshot=registry.snapshot(handle.id(),"alice",scope);
            assertThat(snapshot.calls()).hasSize(32);
            assertThat(snapshot.omittedCalls()).isEqualTo(68);
            assertThat(snapshot.rawScores()).hasSize(100);
            var call=registry.callDetail(handle.id(),snapshot.calls().getFirst().id(),"alice",scope);
            assertThat(call.prompt().length()).isLessThanOrEqualTo(8192);
            assertThat(call.response().length()).isLessThanOrEqualTo(8192);
            assertThat(call.truncated()).isTrue();
        }
    }
    @Test void currentContextIsRestoredAfterNestedScopes() {
        try(var outer=registry.open(null,"alice",scope,null)) {
            try(var inner=registry.open(null,"alice",scope,null)) {
                AnalysisRunControl.phase("INNER",null);
            }
            AnalysisRunControl.phase("OUTER",null);
            assertThat(registry.snapshot(outer.id(),"alice",scope).phase()).isEqualTo("OUTER");
        }
        assertThat(AnalysisRunControl.active()).isFalse();
    }
    @Test void terminalRetentionIsBoundedButLiveRunsAreNotEvicted() {
        try(var active=registry.open(null,"alice",scope,null)) {
            for(int i=0;i<40;i++) {
                try(var completed=registry.open(null,"alice",scope,null)) { completed.finish("SUCCESS"); }
            }
            assertThat(registry.recent("alice",scope,null,null)).hasSizeLessThanOrEqualTo(16);
            assertThat(registry.snapshot(active.id(),"alice",scope).status()).isEqualTo("RUNNING");
        }
    }
    private static LlmCallDetail detail(String code,String prompt,String response) {
        var detail=new LlmCallDetail();
        detail.setScores(Map.of(code,80));detail.setReasons(Map.of(code,"reason"));
        detail.setPrompt(prompt);detail.setRawResponse(response);detail.setProvider("MOCK");
        return detail;
    }
}
