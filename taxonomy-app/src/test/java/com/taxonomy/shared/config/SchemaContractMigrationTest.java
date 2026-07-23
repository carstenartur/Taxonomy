package com.taxonomy.shared.config;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.model.RelationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class SchemaContractMigrationTest {

    private static final Set<String> EXPECTED_SCOPE_COLUMNS = Set.of(
            "source_node_id", "target_node_id", "relation_type", "workspace_scope_key");

    @Autowired private SchemaContractMigration migration;
    @Autowired private DataSource dataSource;
    @Autowired private TaxonomyNodeRepository nodeRepository;
    @Autowired private TaxonomyRelationRepository relationRepository;

    @Test
    void migrationIsIdempotentAndRequiredColumnsExist() throws Exception {
        migration.migrate();
        migration.migrate();

        try (Connection connection = dataSource.getConnection()) {
            assertThat(columnExists(connection, "relation_proposal", "workspace_scope_key")).isTrue();
            assertThat(columnExists(connection, "taxonomy_relation", "workspace_scope_key")).isTrue();
            assertThat(columnExists(connection, "app_user", "must_change_password")).isTrue();
            assertThat(uniqueColumnSets(connection, "relation_proposal"))
                    .contains(EXPECTED_SCOPE_COLUMNS);
            assertThat(uniqueColumnSets(connection, "taxonomy_relation"))
                    .contains(EXPECTED_SCOPE_COLUMNS);
        }
    }

    @Test
    void sharedRelationDuplicatesAreRejectedByDatabaseConstraint() {
        TaxonomyNode source = nodeRepository.findByCode("BP").orElseThrow();
        TaxonomyNode target = nodeRepository.findByCode("BR").orElseThrow();
        relationRepository.deleteAll(
                relationRepository.findBySourceNodeCodeAndTargetNodeCodeAndRelationType(
                        "BP", "BR", RelationType.CONTAINS));
        relationRepository.flush();

        relationRepository.saveAndFlush(relation(source, target, "schema-contract-first"));

        assertThatThrownBy(() -> relationRepository.saveAndFlush(
                relation(source, target, "schema-contract-duplicate")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static TaxonomyRelation relation(TaxonomyNode source,
                                              TaxonomyNode target,
                                              String description) {
        TaxonomyRelation relation = new TaxonomyRelation();
        relation.setSourceNode(source);
        relation.setTargetNode(target);
        relation.setRelationType(RelationType.CONTAINS);
        relation.setDescription(description);
        relation.setProvenance("schema-contract-test");
        relation.setWorkspaceId(null);
        return relation;
    }

    private static boolean columnExists(Connection connection,
                                        String tableName,
                                        String columnName) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet tables = metadata.getTables(
                connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String actualTable = tables.getString("TABLE_NAME");
                if (!tableName.equalsIgnoreCase(actualTable)) continue;
                try (ResultSet columns = metadata.getColumns(
                        tables.getString("TABLE_CAT"), tables.getString("TABLE_SCHEM"),
                        actualTable, "%")) {
                    while (columns.next()) {
                        if (columnName.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static Set<Set<String>> uniqueColumnSets(Connection connection,
                                                      String tableName) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        String actualTable = null;
        String catalog = null;
        String schema = null;
        try (ResultSet tables = metadata.getTables(
                connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                if (tableName.equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                    actualTable = tables.getString("TABLE_NAME");
                    catalog = tables.getString("TABLE_CAT");
                    schema = tables.getString("TABLE_SCHEM");
                    break;
                }
            }
        }
        assertThat(actualTable).isNotNull();

        Map<String, Map<Short, String>> indexes = new LinkedHashMap<>();
        try (ResultSet result = metadata.getIndexInfo(catalog, schema, actualTable, true, false)) {
            while (result.next()) {
                String indexName = result.getString("INDEX_NAME");
                String columnName = result.getString("COLUMN_NAME");
                if (indexName == null || columnName == null) continue;
                indexes.computeIfAbsent(indexName, ignored -> new TreeMap<>())
                        .put(result.getShort("ORDINAL_POSITION"),
                                columnName.toLowerCase(Locale.ROOT));
            }
        }

        Set<Set<String>> result = new LinkedHashSet<>();
        indexes.values().forEach(columns ->
                result.add(new LinkedHashSet<>(columns.values())));
        return result;
    }
}