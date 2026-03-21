package com.taxonomy.shared.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory ring buffer that captures the last N log entries for the admin log viewer.
 */
@Service
public class LogRingBufferService {

    private static final String APPENDER_NAME = "admin-ring-buffer";
    private static final int MAX_ENTRIES = 500;
    private final Deque<LogEntry> entries = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();

    @PostConstruct
    public void init() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);

        // Guard against duplicate appenders on Spring context refresh (e.g. in tests)
        if (rootLogger.getAppender(APPENDER_NAME) != null) {
            return;
        }

        var appender = new AppenderBase<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                var entry = new LogEntry(
                    Instant.ofEpochMilli(event.getTimeStamp()),
                    event.getLevel().toString(),
                    event.getLoggerName(),
                    event.getFormattedMessage()
                );
                entries.addLast(entry);
                if (size.incrementAndGet() > MAX_ENTRIES) {
                    entries.pollFirst();
                    size.decrementAndGet();
                }
            }
        };
        appender.setContext(loggerContext);
        appender.setName(APPENDER_NAME);
        appender.start();
        rootLogger.addAppender(appender);
    }

    public List<LogEntry> getEntries(String level, String component, int limit) {
        var result = new ArrayList<LogEntry>();
        String componentLower = (component != null && !component.isEmpty())
                ? component.toLowerCase(Locale.ROOT) : null;
        // Iterate newest-first to collect the most recent matching entries
        var iter = entries.descendingIterator();
        int count = 0;
        while (iter.hasNext() && count < limit) {
            var entry = iter.next();
            if (level != null && !level.isEmpty() && !entry.level().equalsIgnoreCase(level)) continue;
            if (componentLower != null
                    && !entry.logger().toLowerCase(Locale.ROOT).contains(componentLower)) continue;
            result.add(entry);
            count++;
        }
        Collections.reverse(result);
        return result;
    }

    public record LogEntry(Instant timestamp, String level, String logger, String message) {}
}
