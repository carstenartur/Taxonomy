package com.taxonomy.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProposalReviewAuthoritySchemaMigratorTest {

    @Test
    void addsAuthorityColumnsAndIndexIdempotentlyWithoutChangingRows()
            throws Exception {
        DataSource dataSource = dataSource();
        execute(dataSource, """
                create table relation_proposal (
                    id bigint primary key,
                    repository_id varchar(255) not null)
                """);
        execute(dataSource, """
                insert into relation_proposal (id, repository_id)
                values (17, 'repo-a')
                """);
        ProposalReviewAuthoritySchemaMigrator migrator =
                new ProposalReviewAuthoritySchemaMigrator(dataSource);

        migrator.migrate();
        migrator.migrate();

        assertThat(columnExists(dataSource, "review_branch")).isTrue();
        assertThat(columnExists(dataSource, "review_commit_id")).isTrue();
        assertThat(columnExists(dataSource, "review_causation_id")).isTrue();
        assertThat(indexExists(dataSource, "idx_proposal_review_commit")).isTrue();
        assertThat(singleLong(dataSource, "select count(*) from relation_proposal"))
                .isEqualTo(1L);
        assertThat(singleString(dataSource, """
                select review_commit_id
                from relation_proposal
                where id = 17
                """))
                .isNull();
    }

    private static DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.hsqldb.jdbc.JDBCDriver");
        dataSource.setUrl("jdbc:hsqldb:mem:proposal-authority-"
                + UUID.randomUUID().toString().replace("-", ""));
        dataSource.setUsername("SA");
        dataSource.setPassword("");
        return dataSource;
    }

    private static boolean columnExists(
            DataSource dataSource,
            String column) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             ResultSet columns = connection.getMetaData().getColumns(
                     connection.getCatalog(), null, "%", "%")) {
            while (columns.next()) {
                if ("relation_proposal".equalsIgnoreCase(
                        columns.getString("TABLE_NAME"))
                        && column.equalsIgnoreCase(
                                columns.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean indexExists(
            DataSource dataSource,
            String index) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             ResultSet indexes = connection.getMetaData().getIndexInfo(
                     connection.getCatalog(), null, "RELATION_PROPOSAL", false, false)) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                if (name != null && index.equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long singleLong(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String singleString(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static void execute(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
