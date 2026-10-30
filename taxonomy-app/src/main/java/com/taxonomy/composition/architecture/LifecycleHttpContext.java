package com.taxonomy.composition.architecture;

import com.taxonomy.editor.ArchitectureCommandPort.Context;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.Set;
import static com.taxonomy.composition.architecture.ArchitectureLifecycleService.problem;

/** Query pins are assertions about the selected context, never an alternative authorization mechanism. */
@Component
public class LifecycleHttpContext {
    private final WorkspaceResolver resolver;
    public LifecycleHttpContext(WorkspaceResolver resolver){this.resolver=resolver;}
    public RepositoryContext resolve(String repositoryId,String workspaceScopeKey,String branch) {
        var resolved=resolver.resolveCurrentRepositoryContext();var actual=Context.of(resolved,null);
        if((repositoryId!=null&&!repositoryId.equals(actual.repositoryId()))
                ||(workspaceScopeKey!=null&&!workspaceScopeKey.equals(actual.workspaceScopeKey()))
                ||(branch!=null&&!branch.equals(actual.branch()))) throw problem("NOT_FOUND","The exact selected context is unavailable");
        return resolved;
    }
    public RepositoryContext resolve(){return resolve(null,null,null);}
    public static void requireRevision(Context context,String ifMatch,String ifNoneMatch) {
        if(ifMatch==null) throw new PreconditionRequired();
        if(context==null || ifNoneMatch!=null || !("\"workspace-revision-"+context.revision()+"\"").equals(ifMatch))
            throw new IllegalArgumentException("HTTP precondition must match the exact semantic revision");
    }
    public static boolean mayEdit() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        return auth!=null && auth.getAuthorities().stream().anyMatch(a->Set.of("ROLE_ARCHITECT","ROLE_ADMIN").contains(a.getAuthority()));
    }
    public static ResponseEntity.BodyBuilder response(Context context) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\"workspace-revision-"+context.revision()+"\"")
                .header("X-Taxonomy-Semantic-Revision",Long.toString(context.revision()));
    }
    static final class PreconditionRequired extends IllegalArgumentException {}
}
