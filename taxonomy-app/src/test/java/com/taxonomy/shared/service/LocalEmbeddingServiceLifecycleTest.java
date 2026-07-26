package com.taxonomy.shared.service;

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
