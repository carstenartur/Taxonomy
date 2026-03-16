package com.taxonomy;

import com.taxonomy.shared.service.AppInitializationStateService;
import com.taxonomy.shared.service.AppInitializationStateService.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AppInitializationStateService}.
 */
class AppInitializationStateServiceTest {

    private AppInitializationStateService service;

    @BeforeEach
    void setUp() {
        service = new AppInitializationStateService();
    }

    @Test
    void initialStateIsStarting() {
        assertThat(service.getState()).isEqualTo(State.STARTING);
        assertThat(service.getMessage()).isEqualTo("Starting application");
        assertThat(service.isReady()).isFalse();
        assertThat(service.getError()).isNull();
        assertThat(service.getUpdatedAt()).isNotNull();
    }

    @Test
    void happyPathTransitionsShouldSucceed() {
        service.update(State.LOADING_TAXONOMY, "Loading taxonomy from Excel\u2026");
        assertThat(service.getState()).isEqualTo(State.LOADING_TAXONOMY);
        assertThat(service.getMessage()).isEqualTo("Loading taxonomy from Excel\u2026");
        assertThat(service.isReady()).isFalse();
        assertThat(service.getError()).isNull();

        service.update(State.BUILDING_INDEX, "Building search index\u2026");
        assertThat(service.getState()).isEqualTo(State.BUILDING_INDEX);
        assertThat(service.getMessage()).isEqualTo("Building search index\u2026");
        assertThat(service.isReady()).isFalse();

        service.update(State.READY, "Application is ready");
        assertThat(service.getState()).isEqualTo(State.READY);
        assertThat(service.getMessage()).isEqualTo("Application is ready");
        assertThat(service.isReady()).isTrue();
        assertThat(service.getError()).isNull();
    }

    @Test
    void failTransitionsToFailed() {
        service.update(State.LOADING_TAXONOMY, "Loading taxonomy from Excel\u2026");

        RuntimeException cause = new RuntimeException("Excel file not found");
        service.fail("Initialization failed", cause);

        assertThat(service.getState()).isEqualTo(State.FAILED);
        assertThat(service.getMessage()).isEqualTo("Initialization failed");
        assertThat(service.isReady()).isFalse();
        assertThat(service.getError()).isEqualTo("Excel file not found");
    }

    @Test
    void failWithNullThrowableStoresNullError() {
        service.fail("Initialization failed", null);

        assertThat(service.getState()).isEqualTo(State.FAILED);
        assertThat(service.getError()).isNull();
    }

    @Test
    void updateClearsErrorFromPreviousFail() {
        service.fail("Something went wrong", new RuntimeException("oops"));
        assertThat(service.getError()).isNotNull();

        service.update(State.LOADING_TAXONOMY, "Retrying\u2026");
        assertThat(service.getError()).isNull();
    }

    @Test
    void updatedAtChangesAfterUpdate() throws InterruptedException {
        Instant before = service.getUpdatedAt();
        Thread.sleep(5);
        service.update(State.LOADING_TAXONOMY, "Loading\u2026");
        assertThat(service.getUpdatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void isReadyOnlyTrueForReadyState() {
        for (State s : State.values()) {
            service.update(s, "test");
            assertThat(service.isReady()).isEqualTo(s == State.READY);
        }
    }
}
