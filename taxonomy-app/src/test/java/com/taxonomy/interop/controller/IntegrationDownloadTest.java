package com.taxonomy.interop.controller;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.IntegrationService;
import com.taxonomy.interop.persistence.IntegrationStore.Operation;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntegrationDownloadTest {
    @Test void downloadEncodesUnicodeQuotesAndSeparatorsWhileRetainingTheFrozenAuthority() {
        var service = mock(IntegrationService.class); var resolver = mock(WorkspaceResolver.class);
        var context = RepositoryContext.workspace("repository", "workspace", "draft", "alice");
        UUID connection = UUID.randomUUID(), operation = UUID.randomUUID();
        byte[] bytes = "frozen reviewed bytes".getBytes(StandardCharsets.UTF_8);
        String filename = "Änderung \"A\"; reviewed.reqif";
        var record = mock(Operation.class);
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(context);
        when(service.file(context, connection, operation)).thenReturn(new ExchangeFile("application/reqif+xml", filename, bytes, List.of()));
        when(service.operation(context, connection, operation)).thenReturn(record);
        when(record.context()).thenReturn(new IntegrationContext(connection, AuthorityMode.BIDIRECTIONAL, new ExternalScope("Reference", "project", null),
                new InternalState("repository", "scope", "draft", "checkpoint", 7, 1L, "fingerprint"), "alice", "reqif-1.2", "1"));
        var response = new IntegrationController(service, resolver).file(connection, operation);
        assertEquals(filename, response.getHeaders().getContentDisposition().getFilename());
        assertEquals("attachment", response.getHeaders().getContentDisposition().getType());
        assertFalse(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).chars().anyMatch(Character::isISOControl));
        assertArrayEquals(bytes, response.getBody());
        assertEquals("7", response.getHeaders().getFirst("X-Taxonomy-Semantic-Revision"));
        assertEquals("checkpoint", response.getHeaders().getFirst("X-Taxonomy-Checkpoint"));
    }
}
