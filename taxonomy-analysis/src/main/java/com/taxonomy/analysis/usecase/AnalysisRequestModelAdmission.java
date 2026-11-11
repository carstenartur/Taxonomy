package com.taxonomy.analysis.usecase;

import com.taxonomy.dto.AnalysisModelBinding;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

/** Explicit client revisions are expectations. Only scoped server reads supply model evidence. */
public final class AnalysisRequestModelAdmission {
    private AnalysisRequestModelAdmission() {}

    public static AnalysisModelBinding capture(AnalysisAdmissionModelPort models, WorkspaceContext workspace,
            String expectedCommit, Long expectedRevision, AnalysisScope scope) {
        AnalysisModelBinding captured = models == null ? null : models.capture(workspace, expectedCommit, expectedRevision, scope);
        requireExpected(captured, expectedCommit, expectedRevision);
        if (captured != null) AdmittedCatalogue.revision(captured);
        return captured;
    }

    public static AnalysisModelBinding requireExpected(AnalysisModelBinding saved, String expectedCommit, Long expectedRevision) {
        if ((expectedCommit != null && (saved == null || !Objects.equals(expectedCommit, saved.commit())))
                || (expectedRevision != null && (saved == null || !Objects.equals(expectedRevision, saved.semanticRevision())))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The expected architecture revision differs from the admitted model");
        }
        return saved;
    }
}
