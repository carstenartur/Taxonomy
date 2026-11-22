package com.taxonomy.testsupport;

import org.hsqldb.DatabaseManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HsqldbFixtureTest {
    @Test
    void releasesOnlyDatabasesOpenedByItsOwner() throws Exception {
        try (var other = new HsqldbFixture()) {
            var retained = other.create("another-test-", "");
            try (var connection = retained.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE retained (id INT)");
            }
            String closedName;
            String unusedName;
            try (var fixture = new HsqldbFixture()) {
                var database = fixture.create("this-test-", ";hsqldb.tx=mvcc");
                closedName = database.getUrl().substring("jdbc:hsqldb:".length()).split(";", 2)[0];
                unusedName = fixture.create("not-opened-", "").getUrl().substring("jdbc:hsqldb:".length());
                try (var connection = database.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE example (id INT)");
                }
                assertThat(DatabaseManager.getDatabaseURIs().contains(closedName)).isTrue();
            }
            assertThat(DatabaseManager.getDatabaseURIs().contains(closedName)).isFalse();
            assertThat(DatabaseManager.getDatabaseURIs().contains(unusedName)).isFalse();
            try (var connection = retained.getConnection();
                 var statement = connection.createStatement()) {
                assertThat(statement.execute("SELECT * FROM retained")).isTrue();
            }
        }
    }
}
