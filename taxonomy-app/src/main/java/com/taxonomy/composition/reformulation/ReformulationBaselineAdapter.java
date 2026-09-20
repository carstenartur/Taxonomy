package com.taxonomy.composition.reformulation;

import com.taxonomy.analysis.service.PromptTemplateService;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDetail;
import com.taxonomy.portfolio.reformulation.ReformulationBaselineContextPort;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.workspace.service.*;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;

/** Read-only composition: snapshot catalogue/edges plus separately labelled current workspace/prompt content. */
@Component
public class ReformulationBaselineAdapter implements ReformulationBaselineContextPort {
    private final WorkspaceArchitectureReadPort architecture;
    private final PromptTemplateService prompts;
    private final PortfolioJsonCodec json;
    public ReformulationBaselineAdapter(WorkspaceArchitectureReadPort architecture,PromptTemplateService prompts,PortfolioJsonCodec json) {
        this.architecture=architecture;this.prompts=prompts;this.json=json;
    }
    @Override public Map<String,String> freeze(SnapshotDetail snapshot,String actor,WorkspaceContext context) {
        Map<String,String> result=new TreeMap<>();
        // Historical tree/descriptions and directed reviewed mappings come from this exact snapshot.
        result.put("snapshotDetail",json.write(snapshot));
        result.put("catalogue",json.write(snapshot.analysis().getTree()==null?List.of():snapshot.analysis().getTree()));
        result.put("elementMappings",json.write(snapshot.elementMappings()));
        result.put("relationMappings",json.write(snapshot.relationMappings()));
        Map<String,String> promptContent=new TreeMap<>();
        for(String code:prompts.getAllTemplateCodes()) promptContent.put(code,prompts.getTemplate(code));
        result.put("promptTemplatesAtCapture",json.write(promptContent));
        var repositoryContext=context.workspaceId()==null
                ?RepositoryContext.centralRead(context.repositoryId(),context.currentBranch(),actor)
                :RepositoryContext.workspace(context.repositoryId(),context.workspaceId(),context.currentBranch(),actor);
        try {
            var document=architecture.read(repositoryContext,null);
            result.put("workspaceDsl",document.dsl());
            result.put("workspaceSemanticRevision",Long.toString(document.state().semanticRevision()));
            result.put("workspaceCommit",Objects.toString(document.state().commitId(),""));
            result.put("workspaceStateScope",document.state().workspaceScopeKey());
        } catch(IOException failure) {
            throw PortfolioException.unavailable("Could not freeze workspace architecture context",failure);
        }
        // The read port makes no dirty/conflict claim. Never infer CLEAN from absent metadata.
        result.put("workspaceDirtyState","UNKNOWN");result.put("workspaceConflictState","UNKNOWN");
        return Map.copyOf(result);
    }
}
