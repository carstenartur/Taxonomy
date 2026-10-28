package com.taxonomy.search;

import com.taxonomy.catalog.service.AppInitializationStateService;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.catalog.snapshot.CatalogueRuntimePolicy;
import com.taxonomy.dto.TaxonomyNodeDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.AopTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LocalOnnxIndexInitializerLifecycleTest {

    enum Stage { TAXONOMY, MODEL, NODES, RELATIONS }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void closingOwningContextStopsStartupBeforeExecutorShutdown(Stage stage) throws Exception {
        try (var fixture = new Fixture(stage);
             var closer = Executors.newSingleThreadExecutor()) {
            fixture.start();
            assertThat(fixture.entered.await(2, TimeUnit.SECONDS)).isTrue();

            try {
                // The production lifecycle budget is unchanged. Cancellation must let the
                // real Spring executor stop without consuming that budget.
                closer.submit(fixture.context::close).get(2, TimeUnit.SECONDS);
                assertThat(fixture.executor.getThreadPoolExecutor().awaitTermination(2, TimeUnit.SECONDS)).isTrue();
                assertThat(fixture.initializer.getState()).isEqualTo(stage == Stage.RELATIONS
                        ? LocalOnnxIndexInitializer.State.PARTIAL : LocalOnnxIndexInitializer.State.FAILED);
                assertThat(fixture.initializer.getDetail()).contains("application shutdown");
                if (stage != Stage.TAXONOMY) {
                    assertThat(fixture.interrupted).isTrue();
                }
            } finally {
                // Also releases an unfixed initializer after the regression assertion fails.
                fixture.release.countDown();
            }
        }
    }

    @Test
    void closingChildContextDoesNotCancelParentStartup() throws Exception {
        try (var fixture = new Fixture(Stage.NODES)) {
            fixture.start();
            assertThat(fixture.entered.await(2, TimeUnit.SECONDS)).isTrue();
            try (var child = new AnnotationConfigApplicationContext()) {
                child.setParent(fixture.context);
                child.refresh();
            }
            fixture.release.countDown();
            fixture.awaitWorker();

            assertThat(fixture.interrupted).isFalse();
            assertThat(fixture.initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.READY);
        }
    }

    @Test
    void closingAfterStartupDoesNotInterruptReusedExecutorThread() throws Exception {
        try (var fixture = new Fixture(Stage.NODES)) {
            fixture.release.countDown();
            fixture.start();
            fixture.awaitWorker();
            assertThat(fixture.initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.READY);

            var otherEntered = new CountDownLatch(1);
            var otherRelease = new CountDownLatch(1);
            var otherInterrupted = new AtomicBoolean();
            var otherTask = fixture.executor.submit(() -> {
                otherEntered.countDown();
                try {
                    otherRelease.await();
                } catch (InterruptedException exception) {
                    otherInterrupted.set(true);
                    Thread.currentThread().interrupt();
                }
            });
            try {
                assertThat(otherEntered.await(2, TimeUnit.SECONDS)).isTrue();
                fixture.context.publishEvent(new ContextClosedEvent(fixture.context));
            } finally {
                otherRelease.countDown();
            }
            otherTask.get(2, TimeUnit.SECONDS);
            assertThat(otherInterrupted).isFalse();
        }
    }

    @Test
    void startupThatReachesInitializerAfterCloseDoesNotLoadModel() {
        try (var fixture = new Fixture(Stage.NODES)) {
            LocalOnnxIndexInitializer target = AopTestUtils.getUltimateTargetObject(fixture.initializer);
            fixture.context.close();
            fixture.release.countDown();

            // A task already handed to a worker can reach its method after close wins the race.
            target.initializeLocalOnnxIndex();

            assertThat(target.hasStarted()).isFalse();
            assertThat(fixture.modelCalls).hasValue(0);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync(proxyTargetClass = true)
    static class AsyncConfiguration {
        @Bean
        ThreadPoolTaskExecutor taskExecutor() {
            var executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setThreadNamePrefix("onnx-lifecycle-test-");
            return executor;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AtomicInteger modelCalls = new AtomicInteger();
        final Stage stage;
        final LocalOnnxIndexInitializer initializer;
        final ThreadPoolTaskExecutor executor;

        Fixture(Stage stage) {
            this.stage = stage;
            var initialization = new AppInitializationStateService() {
                @Override
                public State getState() {
                    if (stage == Stage.TAXONOMY && release.getCount() > 0) {
                        entered.countDown();
                        return State.STARTING;
                    }
                    return State.READY;
                }
            };
            // Only native inference and persistence are replaced. The initializer,
            // Spring events, async proxy, executor and shutdown lifecycle are real.
            var model = new LocalEmbeddingService() {
                @Override public boolean isEnabled() { return true; }
                @Override public float[] embed(String text) throws InterruptedException {
                    modelCalls.incrementAndGet();
                    blockAt(Stage.MODEL);
                    return new float[]{1};
                }
                @Override public String embeddingIndexKey() { return "lifecycle-test-model"; }
                @Override public int indexedNodeCount() { return 1; }
                @Override public List<TaxonomyNodeDto> semanticSearch(String query, int limit) {
                    return List.of(new TaxonomyNodeDto());
                }
            };
            var rebuilder = new LocalEmbeddingIndexRebuilder(null, 2, 16) {
                @Override public void rebuildNodeIndex() throws InterruptedException { blockAt(Stage.NODES); }
                @Override public void rebuildRelationIndex() throws InterruptedException { blockAt(Stage.RELATIONS); }
                @Override public void verifyNodeEmbeddingCoverage(String modelKey) { }
                @Override public void verifyRelationEmbeddingCoverage(String modelKey) { }
            };
            var beans = context.getBeanFactory();
            beans.registerSingleton("springContextHolder", new Object());
            beans.registerSingleton("localEmbeddingService", model);
            beans.registerSingleton("initializationStateService", initialization);
            beans.registerSingleton("localEmbeddingIndexRebuilder", rebuilder);
            beans.registerSingleton("embeddingIndexHealth", new EmbeddingIndexHealth());
            beans.registerSingleton("catalogueRuntimePolicy", CatalogueRuntimePolicy.fullCatalogue());
            context.register(AsyncConfiguration.class, LocalOnnxIndexInitializer.class);
            context.refresh();
            initializer = context.getBean(LocalOnnxIndexInitializer.class);
            executor = context.getBean(ThreadPoolTaskExecutor.class);
        }

        void start() {
            context.publishEvent(new ApplicationReadyEvent(new SpringApplication(AsyncConfiguration.class),
                    new String[0], context, Duration.ZERO));
        }

        void awaitWorker() throws Exception {
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
        }

        private void blockAt(Stage requested) throws InterruptedException {
            if (stage != requested) return;
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                interrupted.set(true);
                throw exception;
            }
        }

        @Override
        public void close() {
            release.countDown();
            context.close();
        }
    }
}
