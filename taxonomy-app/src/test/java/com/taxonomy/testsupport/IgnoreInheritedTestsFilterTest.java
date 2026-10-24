package com.taxonomy.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class IgnoreInheritedTestsFilterTest {

    @Test
    void serviceLoadedFilterRemovesInheritedMethodsAndTemplatesBeforeExecution() {
        var launcher = LauncherFactory.create();
        var plan = launcher.discover(LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(BaseContract.class), selectClass(OwnContract.class),
                        selectClass(IntentionalInheritance.class))
                .build());
        Set<String> discoveredMethods = plan.getRoots().stream()
                .flatMap(root -> plan.getDescendants(root).stream())
                .flatMap(test -> test.getSource().stream())
                .filter(MethodSource.class::isInstance)
                .map(MethodSource.class::cast)
                .map(source -> source.getClassName() + "#" + source.getMethodName())
                .collect(Collectors.toSet());
        assertThat(discoveredMethods).containsExactlyInAnyOrder(
                BaseContract.class.getName() + "#inheritedTest",
                BaseContract.class.getName() + "#inheritedTemplate",
                OwnContract.class.getName() + "#ownTest",
                OwnContract.class.getName() + "#ownTemplate",
                IntentionalInheritance.class.getName() + "#inheritedTest",
                IntentionalInheritance.class.getName() + "#inheritedTemplate",
                IntentionalInheritance.class.getName() + "#intentionalOwnTest");

        var listener = new SummaryGeneratingListener();
        launcher.execute(plan, listener);
        var summary = listener.getSummary();
        assertThat(summary.getTestsFoundCount()).isEqualTo(10);
        assertThat(summary.getTestsSucceededCount()).isEqualTo(10);
        assertThat(summary.getTestsSkippedCount()).isZero();
        assertThat(summary.getTestsAbortedCount()).isZero();
        assertThat(summary.getTotalFailureCount()).isZero();
    }

    static class BaseContract {
        @Test
        void inheritedTest() {
        }

        @ParameterizedTest
        @ValueSource(strings = {"first", "second"})
        void inheritedTemplate(String value) {
            assertThat(value).isNotBlank();
        }
    }

    @IgnoreInheritedTests
    static class OwnContract extends BaseContract {
        @Test
        void ownTest() {
        }

        @ParameterizedTest
        @ValueSource(strings = {"first", "second"})
        void ownTemplate(String value) {
            assertThat(value).isNotBlank();
        }
    }

    static class IntentionalInheritance extends BaseContract {
        @Test
        void intentionalOwnTest() {
        }
    }
}
