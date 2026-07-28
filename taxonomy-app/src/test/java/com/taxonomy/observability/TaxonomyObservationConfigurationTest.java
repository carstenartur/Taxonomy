package com.taxonomy.observability;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
