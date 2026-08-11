package com.taxonomy.relations.command;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationIdentity;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.MissingAuthoritativeHeadException;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.RemoveRelation;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureRelationGitCommandMissingHeadTest {

    @Test
    void absentRelationOnAbsentBranchIsNotReportedAsAuthoritativeSuccess()
            throws Exception {
        try (DslGitRepositoryFactory repositoryFactory =
                     new DslGitRepositoryFactory(null)) {
            ArchitectureRelationGitCommandService service =
                    new ArchitectureRelationGitCommandService(
                            repositoryFactory,
                            new ArchitectureRelationDslTransformer(),
                            new ExpectedHeadDslCommitter());
            RepositoryContext context = RepositoryContext.workspace(
                    "repo-a", "workspace-a", "draft", "alice");

            assertThatThrownBy(() -> service.execute(
                    context,
                    null,
                    new RemoveRelation(
                            new RelationIdentity("APP-1", "USES", "SVC-1"),
                            new CommandMetadata("remove-missing"))))
                    .isInstanceOf(MissingAuthoritativeHeadException.class)
                    .hasMessageContaining("no existing Git commit");

            assertThat(repositoryFactory.resolveRepository(context)
                    .getHeadCommit("draft")).isNull();
        }
    }
}
