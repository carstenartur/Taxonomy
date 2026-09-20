package com.taxonomy.portfolio.reformulation;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDetail;
import com.taxonomy.workspace.service.WorkspaceContext;
import java.util.Map;
/** App composition captures context content through read-only feature boundaries. */
public interface ReformulationBaselineContextPort {
    Map<String,String> freeze(SnapshotDetail snapshot,String username,WorkspaceContext context);
}
