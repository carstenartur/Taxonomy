package com.taxonomy.workspace.controller;

import com.taxonomy.dto.ContextComparison;
import com.taxonomy.versioning.service.ContextCompareService;
import com.taxonomy.workspace.service.*;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceCompareRepositoryTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void comparisonsReadSemanticAndRawChangesFromSelectedRepository(boolean commitSelection) throws Exception {
        try (var repositories = new DslGitRepositoryFactory(null)) {
            var primary = repositories.getSystemRepository();
            primary.commitDsl("left", dsl("PRIMARY LEFT"), "owner", "seed");
            primary.commitDsl("right", dsl("PRIMARY RIGHT"), "owner", "seed");
            var selected = repositories.openWorkspaceRepository("selected-copy");
            String left = selected.commitDsl("left", dsl("SELECTED LEFT"), "alice", "seed");
            selected.commitDsl("right", dsl("SELECTED RIGHT"), "alice", "seed");
            var resolver = mock(WorkspaceResolver.class);
            when(resolver.resolveCurrentContext()).thenReturn(new WorkspaceContext("alice", "selected-copy", "main", "selected-source"));
            var controller = new WorkspaceController(mock(WorkspaceManager.class), resolver,
                    new ContextCompareService(repositories), null, null, null, null, null);

            var response = controller.compare("left", "right", commitSelection ? left : null, null, null);

            assertEquals(200, response.getStatusCode().value());
            var comparison = assertInstanceOf(ContextComparison.class, response.getBody());
            assertEquals("SELECTED LEFT", comparison.changes().getFirst().beforeValue());
            assertEquals("SELECTED RIGHT", comparison.changes().getFirst().afterValue());
            assertTrue(comparison.rawDslDiff().contains("SELECTED LEFT"));
            assertFalse(comparison.rawDslDiff().contains("PRIMARY"));
        }
    }
    private static String dsl(String title) { return "element A type System {\n  title: \"" + title + "\";\n}\n"; }
}
