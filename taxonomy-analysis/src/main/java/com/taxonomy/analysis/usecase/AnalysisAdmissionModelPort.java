package com.taxonomy.analysis.usecase;

import com.taxonomy.dto.AnalysisModelBinding;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.workspace.service.WorkspaceContext;

/** Read at admission under the authenticated request. Workers replay the operation's saved binding. */
@FunctionalInterface
public interface AnalysisAdmissionModelPort {
    AnalysisModelBinding capture(WorkspaceContext authorizedWorkspace, String expectedCommit,
                                 Long expectedSemanticRevision, AnalysisScope scope);
}
