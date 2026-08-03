package com.taxonomy.shared.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.springframework.stereotype.Component;

/**
 * Prevents rendered LLM prompts from entering application logs.
 *
 * <p>Rendered prompts can contain requirement text, document excerpts and other
 * project data. The filter is deliberately independent of the configured log
 * level so enabling DEBUG for diagnostics cannot expose those values. Structured
 * request metadata and provider diagnostics remain available.</p>
 */
@Component
public final class SensitiveLlmPromptTurboFilter extends TurboFilter {

    static final String LLM_SERVICE_LOGGER = "com.taxonomy.analysis.service.LlmService";
    static final String FULL_PROMPT_MESSAGE_PREFIX = "Full LLM prompt:";

    private LoggerContext loggerContext;

    @PostConstruct
    void install() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            loggerContext = context;
            start();
            context.addTurboFilter(this);
        }
    }

    @PreDestroy
    void uninstall() {
        if (loggerContext != null) {
            loggerContext.getTurboFilterList().remove(this);
            stop();
            loggerContext = null;
        }
    }

    @Override
    public FilterReply decide(Marker marker,
                              Logger logger,
                              Level level,
                              String format,
                              Object[] parameters,
                              Throwable throwable) {
        if (logger != null
                && LLM_SERVICE_LOGGER.equals(logger.getName())
                && format != null
                && format.startsWith(FULL_PROMPT_MESSAGE_PREFIX)) {
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }
}
