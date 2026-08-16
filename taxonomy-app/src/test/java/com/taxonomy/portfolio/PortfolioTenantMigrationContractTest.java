package com.taxonomy.portfolio;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioTenantMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/taxonomy/postgresql/"
                    + "V12__scope_portfolio_by_repository_branch.sql");

    @Test
    void migrationBackfillsEveryPortfolioRootAndFailsClosedOnUnknownWorkspaces()
            throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("alter table arch_project")
                .contains("alter table solution_definition")
                .contains("alter table product_catalog")
                .contains("repository_id varchar(255)")
                .contains("workspace_scope varchar(320)")
                .contains("branch_name varchar(255)")
                .contains("Portfolio tenancy migration found an unknown workspace_id")
                .contains("Portfolio tenancy migration found incomplete workspace repository provenance")
                .contains("Portfolio tenancy migration found ambiguous central project keys")
                .contains("Portfolio tenancy migration found ambiguous central solution keys")
                .contains("Portfolio tenancy migration found ambiguous central product keys")
                .contains("workspace_scope = 'CENTRAL'")
                .contains("foreign key (repository_id)")
                .contains("char_length(repository_id)::text")
                .contains("char_length(workspace_scope)::text")
                .contains("char_length(branch_name)::text")
                .contains("alter column repository_id set not null")
                .doesNotContain("drop table")
                .doesNotContain("delete from");
    }
}
