package com.taxonomy.composition.persistence;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Application composition runs retained catalogue source migrations before Hibernate validation. */
public final class CatalogueSourceSchemaMigration {
    private CatalogueSourceSchemaMigration() { }
    public static void migrate(DataSource database) {
        Flyway.configure().dataSource(database).table("catalogue_source_schema_history")
                .locations("classpath:db/catalogue-source-java-migrations")
                .baselineOnMigrate(true).baselineVersion("0")
                .javaMigrations(new V1__RetainedCatalogueSources()).load().migrate();
    }

    public static final class V1__RetainedCatalogueSources extends BaseJavaMigration {
        private static final Map<String, StorageTypes> TYPES = Map.of(
                "postgresql", new StorageTypes("bytea", "bigint"),
                "microsoft sql server", new StorageTypes("varbinary(max)", "bigint"),
                "oracle", new StorageTypes("blob", "number(19)"),
                "hsql database engine", new StorageTypes("blob", "bigint"));
        @Override public void migrate(Context context) throws Exception {
            var connection = context.getConnection();
            var types = TYPES.get(connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT));
            if (types == null) throw new SQLException("Unsupported retained catalogue source database");
            try (var statement = connection.createStatement()) {
                statement.execute("create table catalogue_source_blob (sha256 varchar(64) primary key, byte_length " + types.number()
                        + " not null, payload " + types.binary() + " not null, constraint ck_cat_source_length check (byte_length>=0 and byte_length<=67108864))");
                var revision = new StringBuilder("create table catalogue_source_revision (id varchar(36) primary key, created_at varchar(40) not null");
                for (String role : List.of("workbook", "overlay", "relations")) {
                    revision.append(", ").append(role).append("_sha256 varchar(64), ").append(role).append("_use varchar(24) not null")
                            .append(", constraint fk_cat_source_").append(role).append(" foreign key (").append(role).append("_sha256) references catalogue_source_blob(sha256)")
                            .append(", constraint ck_cat_source_").append(role).append(" check ((").append(role)
                            .append("_use in ('APPLIED','PARSE_FAILED') and ").append(role).append("_sha256 is not null) or (")
                            .append(role).append("_use in ('NOT_USED','NOT_RETAINED') and ").append(role).append("_sha256 is null))");
                }
                statement.execute(revision.append(")").toString());
                statement.execute("create table catalogue_source_state (id varchar(32) primary key, current_revision varchar(36), row_version "
                        + types.number() + " default 0 not null, constraint fk_cat_source_current foreign key (current_revision) references catalogue_source_revision(id))");
            }
        }
        private record StorageTypes(String binary, String number) { }
    }
}
