package com.taxonomy.search;

import com.taxonomy.shared.service.AppInitializationStateService;
import com.taxonomy.shared.service.LocalEmbeddingService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.search.mapper.orm.Search;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds the Hibernate Search vector index after the Spring context and taxonomy
 * catalogue are ready when the configured provider is {@code LOCAL_ONNX}.
 *
 * <p>Taxonomy persistence starts from an eager {@code @PostConstruct} service,
 * while most other beans are intentionally lazy. Consequently the original
 * listener-triggered indexing can run before the static bridge-to-Spring adapter
 * is available and create Lucene documents without vectors. Reindexing from an
 * application-ready listener gives Hibernate Search bridges access to the fully
 * initialized Spring context and the local embedding model.</p>
 */
@Service
@Lazy(false)
@DependsOn("springContextHolder")
public class LocalOnnxIndexInitializer {

    private static final Logger log =
            LoggerFactory.getLogger(LocalOnnxIndexInitializer.class);
    private static final Duration TAXONOMY_READY_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration READY_POLL_INTERVAL = Duration.ofMillis(250);

    private final EntityManagerFactory entityManagerFactory;
    private final LocalEmbeddingService embeddingService;
    private final AppInitializationStateService initializationState;
    private final AtomicBoolean started = new AtomicBoolean();

    @Value("${llm.provider:}")
    private String provider;

    public LocalOnnxIndexInitializer(EntityManagerFactory entityManagerFactory,
                                     LocalEmbeddingService embeddingService,
                                     AppInitializationStateService initializationState) {
        this.entityManagerFactory = entityManagerFactory;
        this.embeddingService = embeddingService;
        this.initializationState = initializationState;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void initializeLocalOnnxIndex() {
        if (!embeddingService.isEnabled()
                || !"LOCAL_ONNX".equalsIgnoreCase(provider)
                || !started.compareAndSet(false, true)) {
            return;
        }

        if (!awaitTaxonomyReady()) {
            return;
        }

        initializationState.update(
                AppInitializationStateService.State.BUILDING_INDEX,
                "Building local semantic-search index…");
        log.info("Building Hibernate Search vector index for LOCAL_ONNX");

        try {
            embeddingService.embed("Taxonomy embedding index warm-up");
            Search.mapping(entityManagerFactory)
                    .scope(Object.class)
                    .massIndexer()
                    .typesToIndexInParallel(1)
                    .threadsToLoadObjects(1)
                    .batchSizeToLoadObjects(16)
                    .startAndWait();

            initializationState.update(
                    AppInitializationStateService.State.READY,
                    "Application and local semantic-search index are ready");
            log.info("LOCAL_ONNX vector index completed");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("LOCAL_ONNX vector indexing was interrupted", exception);
        } catch (Exception | LinkageError exception) {
            log.error("LOCAL_ONNX vector indexing failed; semantic search is unavailable",
                    exception);
            initializationState.update(
                    AppInitializationStateService.State.READY,
                    "Application is ready; local semantic search is unavailable");
        }
    }

    private boolean awaitTaxonomyReady() {
        long deadline = System.nanoTime() + TAXONOMY_READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            AppInitializationStateService.State state = initializationState.getState();
            if (state == AppInitializationStateService.State.READY) {
                return true;
            }
            if (state == AppInitializationStateService.State.FAILED) {
                log.warn("Skipping LOCAL_ONNX vector indexing because taxonomy initialization failed");
                return false;
            }
            try {
                Thread.sleep(READY_POLL_INTERVAL.toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for taxonomy initialization", exception);
                return false;
            }
        }
        log.error("Skipping LOCAL_ONNX vector indexing because taxonomy was not ready within {}",
                TAXONOMY_READY_TIMEOUT);
        return false;
    }
}
