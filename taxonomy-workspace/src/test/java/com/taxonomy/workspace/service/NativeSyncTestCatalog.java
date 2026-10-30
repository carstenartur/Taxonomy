package com.taxonomy.workspace.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;

/** In-memory fixtures still execute the owner authorization callback against their current metadata lookup. */
final class NativeSyncTestCatalog {
    private NativeSyncTestCatalog() { }

    static SystemRepositoryService create() {
        var catalog = mock(SystemRepositoryService.class);
        doCallRealMethod().when(catalog).readCurrentAuthorization(anyString(), any());
        return catalog;
    }
}
