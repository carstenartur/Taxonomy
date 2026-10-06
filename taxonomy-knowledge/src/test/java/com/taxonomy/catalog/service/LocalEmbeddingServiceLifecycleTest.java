package com.taxonomy.catalog.service;


import ai.djl.repository.zoo.ZooModel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class LocalEmbeddingServiceLifecycleTest {

    @Test
    void closingNeverLoadedServiceIsIdempotent() {
        LocalEmbeddingService service = configuredService();

        service.closeModel();
        service.closeModel();

        assertFalse(service.isAvailable());
        assertThrows(IllegalStateException.class, service::getModel);
    }

    @Test
    void loadedModelIsClosedExactlyOnce() {
        LocalEmbeddingService service = configuredService();
        @SuppressWarnings("unchecked")
        ZooModel<String, float[]> model = mock(ZooModel.class);
        ReflectionTestUtils.setField(service, "model", model);

        service.closeModel();
        service.closeModel();

        verify(model, times(1)).close();
        assertFalse(service.isAvailable());
        assertThrows(IllegalStateException.class, service::getModel);
    }

    @Test
    void failedLoadStateCanStillShutDownSafely() {
        LocalEmbeddingService service = configuredService();
        ReflectionTestUtils.setField(service, "modelLoadFailed", true);

        service.closeModel();
        service.closeModel();

        assertFalse(service.isAvailable());
        assertThrows(IllegalStateException.class, service::getModel);
    }

    @Test
    void overlappingRequestsCannotAllocateMoreThanTwoNativePredictors() throws Exception {
        @SuppressWarnings("unchecked")
        ZooModel<String, float[]> model = mock(ZooModel.class);
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var peak = new java.util.concurrent.atomic.AtomicInteger();
        var entered = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(model.newPredictor()).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ai.djl.inference.Predictor<String, float[]> predictor = mock(ai.djl.inference.Predictor.class);
            org.mockito.Mockito.when(predictor.predict(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
                int current = active.incrementAndGet(); peak.accumulateAndGet(current, Math::max); entered.countDown();
                try {
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Fixture release deadline");
                    return new float[384];
                } finally { active.decrementAndGet(); }
            });
            return predictor;
        });
        LocalEmbeddingService service = new LocalEmbeddingService() {
            @Override ZooModel<String, float[]> getModel() { return model; }
        };
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var first = executor.submit(() -> service.embed("first"));
            var second = executor.submit(() -> service.embed("second"));
            org.junit.jupiter.api.Assertions.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var thirdStarted = new java.util.concurrent.CountDownLatch(1);
            var third = executor.submit(() -> { thirdStarted.countDown(); return service.embed("third"); });
            org.junit.jupiter.api.Assertions.assertTrue(thirdStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            try {
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> third.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
                org.junit.jupiter.api.Assertions.assertEquals(2, active.get());
            } finally { release.countDown(); }
            org.junit.jupiter.api.Assertions.assertEquals(384, first.get().length);
            org.junit.jupiter.api.Assertions.assertEquals(384, second.get().length);
            org.junit.jupiter.api.Assertions.assertEquals(384, third.get().length);
            org.junit.jupiter.api.Assertions.assertEquals(2, peak.get());
        } finally { release.countDown(); }
    }

    private static LocalEmbeddingService configuredService() {
        LocalEmbeddingService service = new LocalEmbeddingService();
        ReflectionTestUtils.setField(service, "embeddingEnabled", true);
        ReflectionTestUtils.setField(service, "allowDownload", false);
        ReflectionTestUtils.setField(service, "modelDir", "/unused/test/model");
        ReflectionTestUtils.setField(
                service,
                "modelName",
                LocalEmbeddingService.DEFAULT_MODEL_URL);
        return service;
    }
}
