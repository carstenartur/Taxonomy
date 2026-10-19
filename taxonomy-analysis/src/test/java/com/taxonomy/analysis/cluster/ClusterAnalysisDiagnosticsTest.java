package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.service.*;
import com.taxonomy.dto.LlmCallDetail;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ClusterAnalysisDiagnosticsTest {
    private static final String URL = "https://diagnostics.invalid/chat/completions";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TaxonomyShardRoot CP = TaxonomyShardRoot.of("CP");

    @Test void fundedRelationshipWorkRetainsDiagnosticsBeyond2048LogicalCalls() {
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var context = ClusterAnalysisStoreTest.context("diagnostic-relation-budget"); db.admit(context);
            var task = db.task(CP); db.store.start(task); var session = db.store.diagnostics(task);
            long call = 0;
            for (int number = 1; number <= 2049; number++) {
                call = session.started("Mock", "CP");
                assertThat(call).isEqualTo(number);
            }
            session.prepared(call, "funded relationship question");
            var detail = new LlmCallDetail(); detail.setRawResponse("accepted answer");
            session.completed(call, detail, 1);
            assertThat(db.store.callDiagnostics().detail(context, call).response()).isEqualTo("accepted answer");
            assertThat(db.store.callDiagnostics().summaries(context).calls()).hasSize(32);
            assertThat(db.store.callDiagnostics().summaries(context).omitted()).isEqualTo(2017);
        }
    }

    @Test void independentDurableStartsPrecedeEveryHttpRetryAndSafeSummariesOmitDetail() {
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var context = ClusterAnalysisStoreTest.context("diagnostic-retries"); db.admit(context);
            var task = db.task(CP); db.store.start(task); var session = db.store.diagnostics(task);
            long call = session.started("Custom", "CP"); session.prepared(call, "PRIVATE-PROMPT");
            var http = new RestTemplate(); var server = MockRestServiceServer.bindTo(http).build();
            var inspected = new java.util.concurrent.atomic.AtomicInteger();
            server.expect(requestTo(URL)).andRespond(request -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                var attempts = db.store.callDiagnostics().detail(context, call).attempts();
                assertThat(attempts).hasSize(1); assertThat(attempts.getFirst().state()).isEqualTo("STARTED");
                inspected.incrementAndGet(); return withStatus(HttpStatus.SERVICE_UNAVAILABLE).createResponse(request);
            });
            server.expect(requestTo(URL)).andRespond(request -> {
                var attempts = db.store.callDiagnostics().detail(context, call).attempts();
                assertThat(attempts).hasSize(2); assertThat(attempts.getLast().state()).isEqualTo("STARTED");
                assertThat(attempts.getFirst().httpStatus()).isEqualTo(503);
                inspected.incrementAndGet(); return withSuccess("{\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}", MediaType.APPLICATION_JSON).createResponse(request);
            });
            try (var journal = LlmTransportMeter.openJournal(session)) { gateway(http).sendHttpRequest("PRIVATE-PROMPT", "fixture"); }
            server.verify(); assertThat(inspected.get()).isEqualTo(2);
            var detail = new LlmCallDetail(); detail.setPrompt("PRIVATE-PROMPT"); detail.setRawResponse("PRIVATE-ANSWER");
            session.completed(call, detail, 1);
            var retained = db.store.callDiagnostics().detail(context, call);
            assertThat(retained.attempts()).hasSize(2);
            assertThat(retained.attempts().getFirst().usage().totalTokens()).isNull();
            assertThat(retained.attempts().getLast().usage().totalTokens()).isEqualTo(7L);
            assertThat(retained.attempts().getFirst().invocationId()).isEqualTo(retained.attempts().getLast().invocationId());
            assertThat(retained.attempts().getFirst().attemptId()).isNotEqualTo(retained.attempts().getLast().attemptId());
            assertThat(db.store.callDiagnostics().summaries(context).toString()).doesNotContain("PRIVATE-PROMPT", "PRIVATE-ANSWER");
        }
    }

    @Test void failedAttemptAdmissionNeverSendsHttpAndCompletionFailureLeavesUnresolvedStart() {
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var context = ClusterAnalysisStoreTest.context("diagnostic-failures"); db.admit(context);
            var task = db.task(CP); db.store.start(task);
            var em = spy(db.em); var failedStart = new AtomicBoolean(true); var failedEnd = new AtomicBoolean();
            doAnswer(invocation -> {
                if (invocation.getArgument(0) instanceof ClusterAnalysisAttempt && failedStart.get()) throw new IllegalStateException("fixture admission unavailable");
                return invocation.callRealMethod();
            }).when(em).persist(any());
            doAnswer(invocation -> { if (failedEnd.getAndSet(false)) throw new IllegalStateException("fixture completion unavailable"); return invocation.callRealMethod(); }).when(em).flush();
            var diagnostics = new ClusterAnalysisDiagnostics(em, db.transactions, JSON, db.store);
            var session = diagnostics.session(task); long call = session.started("Custom", "CP");
            var http = new RestTemplate(); var server = MockRestServiceServer.bindTo(http).build();
            try (var journal = LlmTransportMeter.openJournal(session)) {
                assertThatThrownBy(() -> gateway(http).sendHttpRequest("fixture", "fixture")).isInstanceOf(LlmTransportMeter.JournalStartException.class);
            }
            server.verify(); assertThat(diagnostics.detail(context, call).attempts()).isEmpty();
            failedStart.set(false);
            server.expect(requestTo(URL)).andRespond(request -> { failedEnd.set(true); return withSuccess("{}", MediaType.APPLICATION_JSON).createResponse(request); });
            try (var journal = LlmTransportMeter.openJournal(session)) { assertThat(gateway(http).sendHttpRequest("fixture", "fixture")).isEqualTo("{}"); }
            server.verify();
            assertThat(diagnostics.detail(context, call).attempts()).singleElement().satisfies(attempt -> {
                assertThat(attempt.state()).isEqualTo("STARTED"); assertThat(attempt.usage().totalTokens()).isNull();
            });
        }
    }

    @Test void attemptIdentityIsIdempotentAndLateCompletionCannotSettleAnotherDelivery() {
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var context = ClusterAnalysisStoreTest.context("diagnostic-late"); db.admit(context); var task=db.task(CP); db.store.start(task);
            var first=db.store.diagnostics(task); long one=first.started("Custom", "CP");
            var attempt=new LlmTransportMeter.Attempt(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"CUSTOM_OPENAI",LlmTransportMeter.Source.HTTP,0);
            first.started(attempt); first.started(attempt);
            db.store.start(task); var second=db.store.diagnostics(task); long two=second.started("Custom", "CP");
            var newer=new LlmTransportMeter.Attempt(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"CUSTOM_OPENAI",LlmTransportMeter.Source.HTTP,0); second.started(newer);
            db.store.cancel(context);
            var response=new LlmTransportMeter.Observation(attempt.invocationId(),attempt.provider(),attempt.source(),0,200,LlmTransportMeter.Outcome.RESPONSE,3,new LlmTransportMeter.Usage(null,null,null,null,null,false));
            first.completed(attempt,response); first.completed(attempt,response); first.started(attempt);
            assertThat(db.store.callDiagnostics().detail(context,one).attempts()).singleElement().satisfies(a->assertThat(a.state()).isEqualTo("COMPLETED"));
            assertThat(db.store.callDiagnostics().detail(context,two).attempts()).singleElement().satisfies(a->assertThat(a.state()).isEqualTo("STARTED"));
            assertThatThrownBy(()->second.completed(attempt,response)).isInstanceOf(IllegalStateException.class);
            assertThat(db.store.snapshot(context).state()).isEqualTo(ClusterAnalysisState.CANCELLED);
            assertThatThrownBy(()->first.completed(attempt,new LlmTransportMeter.Observation(attempt.invocationId(),attempt.provider(),attempt.source(),0,500,LlmTransportMeter.Outcome.HTTP_ERROR,3,response.usage()))).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void replayIsRetainedWithoutUsageAndForeignTaskIsRejected() {
        try (var db=new ClusterAnalysisStoreTest.Database()) {
            var context=ClusterAnalysisStoreTest.context("diagnostic-replay"); db.admit(context); var task=db.task(CP); db.store.start(task);
            var session=db.store.diagnostics(task); long call=session.started("Custom","CP");
            var attempt=new LlmTransportMeter.Attempt(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"CUSTOM_OPENAI",LlmTransportMeter.Source.RECORDING_REPLAY,0);
            session.started(attempt); session.completed(attempt,new LlmTransportMeter.Observation(attempt.invocationId(),attempt.provider(),attempt.source(),0,null,LlmTransportMeter.Outcome.RESPONSE,0,new LlmTransportMeter.Usage(null,null,null,null,null,false)));
            assertThat(db.store.callDiagnostics().detail(context,call).attempts()).singleElement().satisfies(a->{assertThat(a.source()).isEqualTo("RECORDING_REPLAY");assertThat(a.usage().totalTokens()).isNull();});
            var foreign=ClusterAnalysisStoreTest.context("foreign"); var foreignTask=(SubtaxonomyAnalysisTask)new AnalysisMessageFactory(foreign,java.time.Clock.systemUTC()).task(AnalysisTaskGraph.plan(foreign.operationId(),List.of(CP),false).tasks().getFirst());
            assertThatThrownBy(()->db.store.diagnostics(foreignTask)).isInstanceOf(IllegalStateException.class);
        }
    }

    private OpenAiCompatibleGateway gateway(RestTemplate http) {
        AnalysisRuntimeSettings settings=(key,fallback)->"llm.retry.max".equals(key)?1:fallback;
        return new OpenAiCompatibleGateway(LlmProvider.CUSTOM_OPENAI,URL,"fixture",0,http,JSON,new LlmResponseParser(JSON),settings,null,null);
    }
}
