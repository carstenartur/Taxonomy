package com.taxonomy.relations.service;

import com.taxonomy.catalog.snapshot.RootEmbeddingSnapshot;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.relations.service.RelationProjectionReadService.RelationIdentity;
import com.taxonomy.workspace.service.RepositoryContext;
import java.util.Comparator;
import java.util.Set;

record BranchGraphVectorSource(String repositoryId, String workspaceId, String branch, String commit,
                               String catalogueRevision, String catalogueDefinition, String modelKey,
                               String relationsHash, int relationCount) {
    static final String TEXT_FORMAT = RootEmbeddingSnapshot.TEXT_VERSION + "|projected-relation-binder:v1";
    static final Comparator<RelationIdentity> ORDER = Comparator.comparing(RelationIdentity::sourceCode)
            .thenComparing(item -> item.relationType().name()).thenComparing(RelationIdentity::targetCode);

    static BranchGraphVectorSource from(RepositoryContext context, String commit, String catalogueRevision,
                                        String definitionHash, String modelKey, Set<RelationIdentity> relations) {
        var text = new StringBuilder();
        relations.stream().sorted(ORDER).forEach(item -> frame(text, item.sourceCode(), item.relationType().name(), item.targetCode()));
        return new BranchGraphVectorSource(context.repositoryId(), context.workspaceId(), context.branch(), commit,
                catalogueRevision, definitionHash, modelKey, StableIdentityHash.sha256(text.toString()), relations.size());
    }

    String key() {
        var text = new StringBuilder();
        frame(text, repositoryId, workspaceId, branch, commit, catalogueRevision, catalogueDefinition, modelKey,
                relationsHash, String.valueOf(relationCount), TEXT_FORMAT, BranchGraphVectorIndex.FORMAT);
        return StableIdentityHash.sha256(text.toString());
    }

    private static void frame(StringBuilder target, String... fields) {
        for (var value : fields) {
            if (value == null) target.append("-1:");
            else target.append(value.length()).append(':').append(value);
        }
    }
}
