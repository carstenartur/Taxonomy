package com.taxonomy.search;

import com.taxonomy.shared.service.AppInitializationStateService;
import com.taxonomy.shared.service.LocalEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Builds local embedding indexes after the Spring context and taxonomy catalogue
 * are ready when the configured analysis provider is {@code LOCAL_ONNX}.
 *
 * <p>Node vectors form the user-facing semantic-search readiness boundary and are
 * therefore rebuilt before relation vectors. The former {@code Object.class}
 * mass-index scope could process unrelated or relation-heavy indexes first while
 * the semantic-search endpoint kept returning an unexplained empty array.</p>
 *
 * <p>The application remains globally ready while this optional index is built:
 * catalogue browsing, full-text search, DSL editing and other deterministic
 * functions are already usable. Semantic readiness is exposed independently by
 * {@link #getState()}, {@link #getDetail()} and {@link #isNodeSearchReady()}.</p>
 */
@Service
@Lazy(false)
@DependsOn("springContextHolder")
public class LocalOnnxIndexInitializer {

    public enum State {
        DISABLED,
        WAITING_FOR_TAXONOMY,
        LOADING_MODEL,
        INDEXING_NODES,
        INDEXING_RELATIONS,
        READY,
        PARTIAL,
        FAILED
    }

    private static final Logger log =
            LoggerFactory.getLogger(LocalOnnxIndexInitializer.class);
    private static final Duration TAXONOMY_READY_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration READY_POLL_INTERVAL = Duration.ofMillis(250);
    private static final String READINESS_PROBE = "taxonomy architecture";

    private final LocalEmbeddingService embeddingService;
    private final AppInitializationStateService initializationState;
    private final LocalEmbeddingIndexRebuilder indexRebuilder;
    private final String provider;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicReference<State> state =
            new AtomicReference<>(State.DISABLED);

    private volatile String detail = "Local embedding indexing has not started";
    private volatile int indexedNodesAtReadiness;

    public LocalOnnxIndexInitializer(
            LocalEmbeddingService embeddingService,
            AppInitializationStateService initializationState,
            LocalEmbeddingIndexRebuilder indexRebuilder,
            @Value("${llm.provider:}") String provider) {
        this.embeddingService = embeddingService;
        this.initializationState = initializationState;
        this.indexRebuilder = indexRebuilder;
        this.provider = provider;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void initializeLocalOnnxIndex() {
        if (!embeddingService.isEnabled()) {
            update(State.DISABLED, "Local embeddings are disabled");
            return;
        }
        if (!"LOCAL_ONNX".equalsIgnoreCase(provider)) {
            update(State.DISABLED,
                    "Automatic local embedding indexing requires LLM_PROVIDER=LOCAL_ONNX");
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }

        update(State.WAITING_FOR_TAXONOMY,
                "Waiting for the taxonomy catalogue to become ready");
        if (!awaitTaxonomyReady()) {
            return;
        }

        try {
            update(State.LOADING_MODEL, "Loading and warming the local embedding model");
            embeddingService.embed("Taxonomy embedding index warm-up");

            update(State.INDEXING_NODES,
                    "Building taxonomy-node vectors required by semantic search");
            indexRebuilder.rebuildNodeIndex();
            verifyNodeSearchReadiness();

            update(State.INDEXING_RELATIONS,
                    "Node semantic search is ready; building relation vectors");
            try {
                indexRebuilder.rebuildRelationIndex();
                update(State.READY,
                        "Local node and relation embedding indexes are ready");
                log.info("Local embedding indexes completed with {} indexed taxonomy nodes",
                        indexedNodesAtReadiness);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                update(State.PARTIAL,
                        "Node semantic search is ready; relation indexing was interrupted");
                log.warn("Relation vector indexing was interrupted after node search became ready",
                        exception);
            } catch (Exception | LinkageError exception) {
                update(State.PARTIAL,
                        "Node semantic search is ready; relation indexing failed: "
                                + rootMessage(exception));
                log.error("Relation vector indexing failed after node search became ready",
                        exception);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            update(State.FAILED, "Local embedding indexing was interrupted");
            log.warn("Local embedding indexing was interrupted", exception);
        } catch (Exception | LinkageError exception) {
            update(State.FAILED,
                    "Local embedding indexing failed: " + rootMessage(exception));
            log.error("Local embedding indexing failed; semantic search is unavailable",
                    exception);
        }
    }

    public State getState() {
        return state.get();
    }

    public String getDetail() {
        return detail;
    }

    public boolean hasStarted() {
        return started.get();
    }

    public int getIndexedNodesAtReadiness() {
        return indexedNodesAtReadiness;
    }

    public boolean isNodeSearchReady() {
        return switch (state.get()) {
            case INDEXING_RELATIONS, READY, PARTIAL -> indexedNodesAtReadiness > 0;
            default -> false;
        };
    }

    private void verifyNodeSearchReadiness() {
        int indexedNodes = embeddingService.indexedNodeCount();
        if (indexedNodes <= 0) {
            throw new IllegalStateException(
                    "Node mass indexing completed without searchable taxonomy documents");
        }
        if (embeddingService.semanticSearch(READINESS_PROBE, 1).isEmpty()) {
            throw new IllegalStateException(
                    "Node mass indexing completed without a searchable embedding vector");
        }
        indexedNodesAtReadiness = indexedNodes;
    }

    private boolean awaitTaxonomyReady() {
        long deadline = System.nanoTime() + TAXONOMY_READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            AppInitializationStateService.State current = initializationState.getState();
            if (current == AppInitializationStateService.State.READY) {
                return true;
            }
            if (current == AppInitializationStateService.State.FAILED) {
                update(State.FAILED,
                        "Taxonomy initialization failed before local embedding indexing");
                log.warn("Skipping local embedding indexing because taxonomy initialization failed");
                return false;
            }
            try {
                Thread.sleep(READY_POLL_INTERVAL.toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                update(State.FAILED,
                        "Interrupted while waiting for taxonomy initialization");
                log.warn("Interrupted while waiting for taxonomy initialization", exception);
                return false;
            }
        }
        update(State.FAILED,
                "Taxonomy was not ready within " + TAXONOMY_READY_TIMEOUT);
        log.error("Skipping local embedding indexing because taxonomy was not ready within {}",
                TAXONOMY_READY_TIMEOUT);
        return false;
    }

    private void update(State nextState, String nextDetail) {
        state.set(nextState);
        detail = nextDetail;
        log.info("Local embedding index state {}: {}", nextState, nextDetail);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }
}
