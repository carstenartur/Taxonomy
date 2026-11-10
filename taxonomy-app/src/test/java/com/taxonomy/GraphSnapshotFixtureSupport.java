package com.taxonomy;

import com.taxonomy.model.RelationType;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Authored Git graph fixture using actual catalogue root identifiers. */
abstract class GraphSnapshotFixtureSupport {
    @Autowired private SystemRepositoryService graphRepositories;
    @Autowired private DslGitRepositoryFactory graphGit;
    @Autowired private RelationBranchProjectionRebuildService graphProjections;
    protected RepositoryContext graphContext;
    protected String graphCommit;
    private StringBuilder graphDsl;

    @BeforeEach void initializeGraphSnapshot() {
        var repository = graphRepositories.getPrimaryRepository();
        graphContext = RepositoryContext.centralRead(repository.getRepositoryId(),
                "graph-test-" + java.util.UUID.randomUUID(), "user");
        graphDsl = new StringBuilder();
        publishGraph();
    }

    protected void graphRelation(String source, String target, RelationType type, String description, String provenance) {
        graphDsl.append("relation ").append(source).append(" ").append(type.name()).append(" ").append(target)
                .append(" {\n  status: accepted;\n  provenance: \"").append(provenance).append("\";\n}\n");
        publishGraph();
    }

    protected void graphElement(String id, String type, java.util.Map<String,String> extensions) {
        graphDsl.append("element ").append(id).append(" type ").append(type).append(" {\n title: \"")
                .append(id).append("\";\n");
        extensions.forEach((key,value) -> graphDsl.append(" ").append(key).append(": \"").append(value).append("\";\n"));
        graphDsl.append("}\n");
        publishGraph();
    }

    protected void graphMapping(String requirement, String element) {
        graphDsl.append("requirement ").append(requirement).append(" {\n title: \"Authored requirement fixture\";\n}\n")
                .append("mapping ").append(requirement).append(" -> ").append(element).append(" {\n source: \"authored fixture\";\n}\n");
        publishGraph();
    }

    private void publishGraph() {
        try {
            graphCommit = graphGit.getCentralRepository(graphContext.repositoryId()).commitDsl(
                    graphContext.branch(), graphDsl.toString(), "user", "Authored graph test fixture");
            graphProjections.rebuild(RepositoryContext.centralWrite(graphContext.repositoryId(), graphContext.branch(), "user"));
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }

    protected MockHttpServletRequestBuilder graphRequest(MockHttpServletRequestBuilder request) {
        return request.param("repositoryId", graphContext.repositoryId()).param("workspaceId", "")
                .param("branch", graphContext.branch()).param("commitId", graphCommit);
    }
}
