package com.taxonomy.shared.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveLlmPromptTurboFilterTest {

    private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    private final SensitiveLlmPromptTurboFilter filter = new SensitiveLlmPromptTurboFilter();

    @Test
    void deniesRenderedPromptEvenAtDebugLevel() {
        var logger = context.getLogger(SensitiveLlmPromptTurboFilter.LLM_SERVICE_LOGGER);

        FilterReply reply = filter.decide(
                null,
                logger,
                Level.DEBUG,
                "Full LLM prompt:\n{}",
                new Object[]{"sensitive requirement text"},
                null);

        assertThat(reply).isEqualTo(FilterReply.DENY);
    }

    @Test
    void preservesNonSensitiveLlmDiagnostics() {
        var logger = context.getLogger(SensitiveLlmPromptTurboFilter.LLM_SERVICE_LOGGER);

        FilterReply reply = filter.decide(
                null,
                logger,
                Level.INFO,
                "LLM Request [{}] — sending prompt for {} nodes",
                new Object[]{"GEMINI", 3},
                null);

        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void doesNotSuppressMessagesFromOtherLoggers() {
        var logger = context.getLogger("com.taxonomy.other.Service");

        FilterReply reply = filter.decide(
                null,
                logger,
                Level.DEBUG,
                "Full LLM prompt:\n{}",
                new Object[]{"not emitted by LlmService"},
                null);

        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }
}
