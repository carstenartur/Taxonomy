package com.taxonomy.versioning.service;

import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Behavioral coverage for semantic merge-preview fallback decisions. */
class SemanticConflictDetectionServiceTest {

    private static final String BASE = "line one\nshared line\nline three\n";
    private static final String SOURCE = "line one\nsource edit\nline three\n";
    private static final String TARGET = "line one\ntarget edit\nline three\n";

    private DslGitRepositoryFactory factory;
    private DslGitRepository repository;
    private SemanticGitMergeService semanticMergeService;
    private SemanticConflictDetectionService service;

    @BeforeEach
    void setUp() throws IOException {
        factory = new DslGitRepositoryFactory(null);
        repository = factory.getSystemRepository();
        semanticMergeService = mock(SemanticGitMergeService.class);
        service = new SemanticConflictDetectionService(factory, semanticMergeService);

        repository.commitDsl("source", BASE, "tester", "base");
        repository.createBranch("target", "source");
        repository.commitDsl("source", SOURCE, "tester", "source edit");
        repository.commitDsl("target", TARGET, "tester", "target edit");
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    @Test
    void semanticSuccessConvertsTextConflictToMergeablePreview() throws Exception {
        when(semanticMergeService.preview(repository, "source", "target"))
                .thenReturn(new SemanticGitMergeService.MergeOutcome(
                        true, null, true, List.of(), null));

        var preview = service.previewMerge("source", "target", WorkspaceContext.SHARED);

        assertTrue(preview.canMerge());
        assertTrue(preview.warnings().contains(
                "The textual conflict is block-semantically mergeable"));
    }

    @Test
    void semanticConflictPreservesIdentifiersForReview() throws Exception {
        when(semanticMergeService.preview(repository, "source", "target"))
                .thenReturn(new SemanticGitMergeService.MergeOutcome(
                        false, null, true, List.of("element CP-1", "relation R-2"), null));

        var preview = service.previewMerge("source", "target", WorkspaceContext.SHARED);

        assertFalse(preview.canMerge());
        assertTrue(preview.warnings().contains("Semantic conflicts require review"));
        assertTrue(preview.warnings().contains("Conflict: element CP-1"));
        assertTrue(preview.warnings().contains("Conflict: relation R-2"));
    }

    @Test
    void semanticPreviewFailureReturnsOriginalConflictWithDiagnostic() throws Exception {
        when(semanticMergeService.preview(repository, "source", "target"))
                .thenThrow(new IOException("semantic parser unavailable"));

        var preview = service.previewMerge("source", "target", WorkspaceContext.SHARED);

        assertFalse(preview.canMerge());
        assertTrue(preview.warnings().contains("Merge would result in conflicts"));
        assertTrue(preview.warnings().contains(
                "Semantic preview failed: semantic parser unavailable"));
    }

    @Test
    void ordinaryPreviewOutcomesDoNotInvokeSemanticFallback() throws Exception {
        var preview = service.previewMerge("missing", "target", WorkspaceContext.SHARED);

        assertFalse(preview.canMerge());
        assertTrue(preview.warnings().stream().anyMatch(message -> message.contains("not found")));
        verify(semanticMergeService, never()).preview(repository, "missing", "target");
    }
}
