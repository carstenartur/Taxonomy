package com.taxonomy.analysis.service;

import com.taxonomy.analysis.recovery.AnalysisCheckpointSession;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.extension.api.llm.ProviderId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

class AnalysisExecutionFailureCheckpointTest {
    private static final String URL = "https://admission.invalid/v1/chat/completions";

    @ParameterizedTest(name = "relation={0}, wrapped={1}")
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void failedDurableJournalStartStopsAtTheCheckpoint(boolean relation, boolean wrapped) {
        var http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        var starts = new AtomicInteger();
        var journal = new LlmTransportMeter.Journal() {
            public void started(LlmTransportMeter.Attempt attempt) {
                starts.incrementAndGet();
                throw new IllegalStateException("durable START was not confirmed");
            }
            public void completed(LlmTransportMeter.Attempt attempt, LlmTransportMeter.Observation result) {
                fail("An unconfirmed journal START cannot complete a physical request");
            }
        };
        try (var scope = LlmTransportMeter.openJournal(journal)) {
            var failure = assertCheckpointStops(gateway(http), relation, wrapped);
            assertThat(failure).isInstanceOf(LlmTransportMeter.JournalStartException.class).hasNoCause();
        }
        assertThat(starts).hasValue(1);
        server.verify(); // No HTTP request is expected when durable admission fails.
    }

    @ParameterizedTest(name = "relation={0}, wrapped={1}")
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void unavailableQuotaAuthorityStopsAtTheCheckpoint(boolean relation, boolean wrapped) {
        var http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        var attempts = new AtomicInteger();
        var unavailable = new ProviderRateQuotas.UnavailableException("Invalid provider quota ledger", null);
        var gateway = gateway(http);
        gateway.configureProviderQuotas(new ProviderRateQuotas() {
            public TokenBudget requiredBudget(ProviderId provider) { return null; }
            public Decision tryReserve(ProviderId provider, String id, TokenBudget budget, Runnable checkpoint) {
                attempts.incrementAndGet();
                throw unavailable;
            }
            public void defer(ProviderId provider, long delayMillis) {
                fail("Unavailable quota authority is not a provider rate-limit response");
            }
        });
        assertThat(assertCheckpointStops(gateway, relation, wrapped)).isSameAs(unavailable);
        assertThat(attempts).hasValue(1);
        server.verify();
    }

    private static RuntimeException assertCheckpointStops(OpenAiCompatibleGateway gateway, boolean relation, boolean wrapped) {
        var checkpoints = new Checkpoints();
        var admittedFailure = new AtomicReference<RuntimeException>();
        var propagatedFailure = new AtomicReference<RuntimeException>();
        var followingCalls = new AtomicInteger();
        try (var session = new AnalysisCheckpointSession(checkpoints)) {
            var thrown = catchThrowable(() -> {
                evaluate(relation, "BP-1", () -> {
                    try {
                        gateway.sendHttpRequest("fixture prompt", "fixture-key");
                        throw new AssertionError("Unavailable admission unexpectedly returned a provider response");
                    } catch (RuntimeException failure) {
                        admittedFailure.set(failure);
                        var propagated = wrapped ? new IllegalStateException("assessment adapter failed", failure) : failure;
                        propagatedFailure.set(propagated);
                        throw propagated;
                    }
                });
                evaluate(relation, "BP-2", () -> {
                    followingCalls.incrementAndGet();
                    var detail = new LlmCallDetail();
                    detail.setScores(Map.of("BP-2", 70)); detail.setRawResponse("accepted fixture answer");
                    return detail;
                });
            });
            assertThat(thrown).as("Execution failure must escape the checkpoint with its original wrapper")
                    .isNotNull().isSameAs(propagatedFailure.get());
        }
        assertThat(followingCalls).as("Execution failure must stop independent assessment admission").hasValue(0);
        assertThat(checkpoints.prepared).isEqualTo(1);
        assertThat(checkpoints.finishedStates).as("No fabricated completed or UNASSESSED checkpoint").isEmpty();
        return admittedFailure.get();
    }

    private static LlmCallDetail evaluate(boolean relation, String node, Supplier<LlmCallDetail> operation) {
        return relation
                ? AnalysisCheckpointSession.evaluateEvidence("NAVIGATE", "OPENAI", List.of(node), "fixture " + node, operation)
                : AnalysisCheckpointSession.evaluate("CATEGORY", "OPENAI", List.of(node), "fixture " + node, operation);
    }

    private static OpenAiCompatibleGateway gateway(RestTemplate http) {
        var json = new ObjectMapper();
        return new OpenAiCompatibleGateway(LlmProvider.OPENAI, URL, "fixture-model", 0,
                http, json, new LlmResponseParser(json), null, null, null);
    }

    private static final class Checkpoints implements AnalysisCheckpointSession.Store {
        int prepared;
        final List<String> finishedStates = new ArrayList<>();
        public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question question) {
            prepared++;
            return new AnalysisCheckpointSession.Checkpoint(question, "ATTEMPT", null);
        }
        public void finish(AnalysisCheckpointSession.Question question, String state, LlmCallDetail detail) {
            finishedStates.add(state);
        }
        public void checkActive() { }
    }
}
