package com.taxonomy.analysis.service;

import com.taxonomy.analysis.service.AnalysisRuntimeSettings;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** No HTTP is sent. Both contenders use the real gateway's RPM window and run control. */
class GatewayThrottleCancellationTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aSecondWaitingAnalysisCanCancelWhileAnotherWaitsForTheRateLimit(boolean gemini) throws Exception {
        var preferences = mock(AnalysisRuntimeSettings.class);
        when(preferences.getInt(anyString(), anyInt())).thenReturn(1);
        var http = mock(RestTemplate.class);
        Runnable throttle = gemini
                ? new GeminiGateway(mock(LlmProviderConfig.class), http, new ObjectMapper(),
                        mock(LlmResponseParser.class), preferences, null, null)::throttle
                : new OpenAiCompatibleGateway(LlmProvider.OPENAI, "https://example.invalid", "model", 1,
                        http, new ObjectMapper(), mock(LlmResponseParser.class), preferences, null, null)::throttle;
        throttle.run(); // Occupy the sole available slot for the real one-minute window.
        CountDownLatch firstWaiting = new CountDownLatch(1), secondStarted = new CountDownLatch(1);
        AtomicBoolean cancelSecond = new AtomicBoolean();
        AtomicReference<Throwable> firstResult = new AtomicReference<>(), secondResult = new AtomicReference<>();
        Thread first = contender(throttle, new AtomicBoolean(), firstWaiting, new CountDownLatch(0), firstResult);
        Thread second = null;
        try {
            assertTrue(firstWaiting.await(2, TimeUnit.SECONDS), "First contender did not reach throttle wait");
            second = contender(throttle, cancelSecond, new CountDownLatch(0), secondStarted, secondResult);
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
            // Boolean cancellation, not interrupting a synchronized monitor acquisition.
            cancelSecond.set(true);
            second.join(1500);
            assertFalse(second.isAlive(), "A different analysis's RPM wait must not lock out cancellation");
            assertInstanceOf(AnalysisStoppedException.class, secondResult.get());
            assertEquals(AnalysisStoppedException.Reason.CANCELLED,
                    ((AnalysisStoppedException) secondResult.get()).reason());
            assertTrue(first.isAlive(), "Cancelling one waiter must not admit another call early");
            verifyNoInteractions(http);
        } finally {
            first.interrupt();
            if (second != null) second.interrupt();
            first.join(3000);
            if (second != null) second.join(3000);
        }
    }

    private static Thread contender(Runnable throttle, AtomicBoolean cancelled, CountDownLatch waiting,
                                    CountDownLatch started, AtomicReference<Throwable> result) {
        return Thread.ofPlatform().start(() -> {
            AnalysisRunControl.Observer observer = mock(AnalysisRunControl.Observer.class);
            doAnswer(invocation -> { waiting.countDown(); return null; })
                    .when(observer).phase(eq("WAITING_RATE_LIMIT"), isNull());
            var guard = new AnalysisMemoryGuard(new AnalysisMemoryGuard.Policy(80, 92, 1024 * 1024, 5000, 120000),
                    () -> new AnalysisMemoryGuard.Sample(1, 100L * 1024 * 1024), () -> 0L);
            try (var control = new AnalysisRunControl(observer, cancelled::get, guard)) {
                started.countDown();
                throttle.run();
                result.set(new AssertionError("The occupied rate-limit slot was bypassed"));
            } catch (Throwable failure) {
                result.set(failure);
            }
        });
    }
}
