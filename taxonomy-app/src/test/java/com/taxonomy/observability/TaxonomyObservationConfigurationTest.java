package com.taxonomy.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaxonomyObservationConfigurationTest {

    @Test
    void recordsSuccessfulOperationWithBoundedTags() throws Throwable {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(
                new DefaultMeterObservationHandler(meterRegistry));

        var descriptor = new TaxonomyObservationConfiguration.TargetDescriptor(
                "taxonomy.test", "test-component", Set.of("work"));
        var interceptor = new TaxonomyObservationConfiguration.TaxonomyObservationInterceptor(
                observationRegistry, descriptor);

        Object result = interceptor.invoke(new StubInvocation(false));

        assertEquals("done", result);
        Timer timer = meterRegistry.get("taxonomy.test")
                .tag("taxonomy.component", "test-component")
                .tag("taxonomy.operation", "work")
                .tag("outcome", "success")
                .timer();
        assertEquals(1, timer.count());
    }

    @Test
    void recordsNormalizedErrorOutcomeWithoutExceptionContent() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(
                new DefaultMeterObservationHandler(meterRegistry));

        var descriptor = new TaxonomyObservationConfiguration.TargetDescriptor(
                "taxonomy.test", "test-component", Set.of("work"));
        var interceptor = new TaxonomyObservationConfiguration.TaxonomyObservationInterceptor(
                observationRegistry, descriptor);

        assertThrows(IllegalStateException.class,
                () -> interceptor.invoke(new StubInvocation(true)));

        Timer timer = meterRegistry.get("taxonomy.test")
                .tag("taxonomy.component", "test-component")
                .tag("taxonomy.operation", "work")
                .tag("outcome", "error")
                .timer();
        assertEquals(1, timer.count());
    }

    @Test
    void logsOnlyBoundedOperationMetadata() throws Throwable {
        Logger logger = (Logger) LoggerFactory.getLogger(
                TaxonomyObservationConfiguration.TaxonomyObservationInterceptor.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            var descriptor = new TaxonomyObservationConfiguration.TargetDescriptor(
                    "taxonomy.test", "test-component", Set.of("work"));
            var interceptor =
                    new TaxonomyObservationConfiguration.TaxonomyObservationInterceptor(
                            ObservationRegistry.NOOP, descriptor);

            interceptor.invoke(new StubInvocation(false));
            assertThrows(IllegalStateException.class,
                    () -> interceptor.invoke(new StubInvocation(true)));

            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(logged.contains(
                    "component=test-component operation=work outcome=success"));
            assertTrue(logged.contains(
                    "component=test-component operation=work outcome=error"));
            assertFalse(logged.contains("sensitive content"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    private static final class StubInvocation implements MethodInvocation {

        private final boolean fail;
        private final Method method;

        private StubInvocation(boolean fail) {
            this.fail = fail;
            try {
                method = StubTarget.class.getDeclaredMethod("work");
            } catch (NoSuchMethodException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public Object[] getArguments() {
            return new Object[0];
        }

        @Override
        public Object proceed() {
            if (fail) {
                throw new IllegalStateException("sensitive content must not become a tag");
            }
            return "done";
        }

        @Override
        public Object getThis() {
            return new StubTarget();
        }

        @Override
        public AccessibleObject getStaticPart() {
            return method;
        }
    }

    private static final class StubTarget {
        public String work() {
            return "done";
        }
    }
}
