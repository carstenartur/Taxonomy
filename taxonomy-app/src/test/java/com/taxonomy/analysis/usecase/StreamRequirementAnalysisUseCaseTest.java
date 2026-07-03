package com.taxonomy.analysis.usecase;

import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.dto.LlmCallDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class StreamRequirementAnalysisUseCaseTest {

    @Mock
    private LlmService llmService;

    @InjectMocks
    private StreamRequirementAnalysisUseCase useCase;

    @Test
    void streamMapsCallbacksToTypedEventsAndClearsProviderOverride() {
        StreamRequirementAnalysisCommand command =
                new StreamRequirementAnalysisCommand("Need secure voice comms", "gemini", Locale.GERMAN);
        LlmCallDetail detail = new LlmCallDetail();
        detail.setPrompt("prompt");
        detail.setRawResponse("raw");
        detail.setProvider("GEMINI");
        detail.setDurationMs(42L);

        doAnswer(invocation -> {
            assertThat(LocaleContextHolder.getLocale()).isEqualTo(Locale.GERMAN);
            com.taxonomy.analysis.service.AnalysisEventCallback callback = invocation.getArgument(1);
            callback.onPhase("Working", 12);
            callback.onScores(Map.of("CP", 80), Map.of("CP", "reason"), "Capabilities scored 80/100", detail);
            callback.onExpanding("CP", List.of("CP-1023"));
            callback.onComplete("SUCCESS", Map.of("CP", 80, "CR", 0), List.of("warn"), List.of());
            return null;
        }).when(llmService).analyzeStreaming(eq(command.businessText()), any());

        List<AnalysisStreamEvent> events = new ArrayList<>();
        Locale previousLocale = LocaleContextHolder.getLocale();
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        try {
            useCase.stream(command, events::add);
            assertThat(LocaleContextHolder.getLocale()).isEqualTo(Locale.ENGLISH);
        } finally {
            LocaleContextHolder.setLocale(previousLocale);
        }

        assertThat(events).hasSize(4);
        assertThat(events.get(0)).isEqualTo(new AnalysisStreamEvent.Phase("Working", 12));
        AnalysisStreamEvent.Scores scores = (AnalysisStreamEvent.Scores) events.get(1);
        assertThat(scores.newScores()).isEqualTo(Map.of("CP", 80));
        assertThat(scores.reasons()).isEqualTo(Map.of("CP", "reason"));
        assertThat(scores.description()).isEqualTo("Capabilities scored 80/100");
        assertThat(scores.detail()).isSameAs(detail);
        assertThat(events.get(2)).isEqualTo(new AnalysisStreamEvent.Expanding("CP", List.of("CP-1023")));
        assertThat(events.get(3)).isEqualTo(new AnalysisStreamEvent.Complete(
                "SUCCESS",
                Map.of("CP", 80, "CR", 0),
                List.of("warn"),
                List.of()));

        verify(llmService).setRequestProvider(com.taxonomy.analysis.service.LlmProvider.GEMINI);
        verify(llmService).analyzeStreaming(eq(command.businessText()), any());
        verify(llmService).clearRequestProvider();
        verifyNoMoreInteractions(llmService);
    }

    @Test
    void streamMapsErrorCallbackToTypedErrorEvent() {
        StreamRequirementAnalysisCommand command =
                new StreamRequirementAnalysisCommand("Need secure voice comms", null, Locale.ENGLISH);

        doAnswer(invocation -> {
            com.taxonomy.analysis.service.AnalysisEventCallback callback = invocation.getArgument(1);
            callback.onError("PARTIAL", "Analysis failed", Map.of("CP", 80), List.of("warn"), List.of());
            return null;
        }).when(llmService).analyzeStreaming(eq(command.businessText()), any());

        List<AnalysisStreamEvent> events = new ArrayList<>();

        useCase.stream(command, events::add);

        assertThat(events).containsExactly(new AnalysisStreamEvent.Error(
                "PARTIAL",
                "Analysis failed",
                Map.of("CP", 80),
                List.of("warn"),
                List.of()));
        verify(llmService).analyzeStreaming(eq(command.businessText()), any());
        verify(llmService).clearRequestProvider();
    }

    @Test
    void streamClearsProviderOverrideWhenProviderIsUnknown() {
        StreamRequirementAnalysisCommand command =
                new StreamRequirementAnalysisCommand("Need secure voice comms", "unknown", Locale.ENGLISH);

        assertThatThrownBy(() -> useCase.stream(command, event -> { }))
                .isInstanceOf(UnknownAnalysisProviderException.class)
                .hasMessage("Unknown provider: unknown");

        verify(llmService).clearRequestProvider();
        verifyNoMoreInteractions(llmService);
    }
}
