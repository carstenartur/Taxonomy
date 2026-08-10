package com.taxonomy.shared.config;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.service.SystemRepositoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.Instant;
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

    private static final Set<String> EXPECTED_PROPOSAL_SCOPE_COLUMNS = Set.of(
            "repository_id", "source_node_id", "target_node_id",
            "relation_type", "workspace_scope_key");
    private static final Set<String> EXPECTED_RELATION_SCOPE_COLUMNS = Set.of(
            "repository_id", "source_node_id", "target_node_id",
            "relation_type", "workspace_scope_key");

    @Autowired private SchemaContractMigration migration;
    @Autowired private DataSource dataSource;
    @Autowired private TaxonomyNodeRepository nodeRepository;
    @Autowired private TaxonomyRelationRepository relationRepository;
    @Autowired private RelationProposalRepository proposalRepository;
    @Autowired private SystemRepositoryRepository systemRepositoryRepository;
    @Autowired private SystemRepositoryService systemRepositoryService;

    @Test
    void migrationIsIdempotentAndRequiredColumnsExist() throws Exception {
        migration.migrate();
        migration.migrate();

        try (Connection connection = dataSource.getConnection()) {
            assertThat(columnExists(connection, "relation_proposal", "workspace_scope_key")).isTrue();
            assertThat(columnExists(connection, "relation_proposal", "repository_id")).isTrue();
            assertThat(columnExists(connection, "taxonomy_relation", "workspace_scope_key")).isTrue();
            assertThat(columnExists(connection, "taxonomy_relation", "repository_id")).isTrue();
            assertThat(columnExists(connection, "app_user", "must_change_password")).isTrue();
            assertThat(uniqueColumnSets(connection, "relation_proposal"))
                    .contains(EXPECTED_PROPOSAL_SCOPE_COLUMNS);
            assertThat(uniqueColumnSets(connection, "taxonomy_relation"))
                    .contains(EXPECTED_RELATION_SCOPE_COLUMNS);
        }
    }

    @Test
    void identicalCentralProposalIdentityCanExistInSeparateRepositories() {
        TaxonomyNode source = nodeRepository.findByCode("BP").orElseThrow();
        TaxonomyNode target = nodeRepository.findByCode("BR").orElseThrow();
        SystemRepository primary = systemRepositoryService.getPrimaryRepository();
        SystemRepository secondary = systemRepositoryRepository.saveAndFlush(secondaryRepository());

        proposalRepository.deleteAll(proposalRepository.findAll().stream()
                .filter(proposal -> proposal.getSourceNode().getCode().equals("BP"))
                .filter(proposal -> proposal.getTargetNode().getCode().equals("BR"))
                .filter(proposal -> proposal.getRelationType() == RelationType.RELATED_TO)
                .toList());
        proposalRepository.flush();

        proposalRepository.saveAndFlush(proposal(
                primary.getRepositoryId(), source, target, "schema-contract-primary"));
        proposalRepository.saveAndFlush(proposal(
                secondary.getRepositoryId(), source, target, "schema-contract-secondary"));

        assertThat(proposalRepository.existsInRepositoryWorkspace(
                primary.getRepositoryId(),
                "BP",
                "BR",
                RelationType.RELATED_TO,
                null)).isTrue();
        assertThat(proposalRepository.existsInRepositoryWorkspace(
                secondary.getRepositoryId(),
                "BP",
                "BR",
                RelationType.RELATED_TO,
                null)).isTrue();
    }

    @Test
    void sharedRelationDuplicatesAreRejectedWithinOneRepository() {
        TaxonomyNode source = nodeRepository.findByCode("BP").orElseThrow();
        TaxonomyNode target = nodeRepository.findByCode("BR").orElseThrow();
        relationRepository.deleteAll(
                relationRepository.findBySourceNodeCodeAndTargetNodeCodeAndRelationType(
                        "BP", "BR", RelationType.CONTAINS));
        relationRepository.flush();

        relationRepository.saveAndFlush(relation(
                source, target, "schema-contract-first"));

        assertThatThrownBy(() -> relationRepository.saveAndFlush(
                relation(source, target, "schema-contract-duplicate")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private RelationProposal proposal(
            String repositoryId,
            TaxonomyNode source,
            TaxonomyNode target,
            String provenance) {
        RelationProposal proposal = new RelationProposal();
        proposal.setRepositoryId(repositoryId);
        proposal.setSourceNode(source);
        proposal.setTargetNode(target);
        proposal.setRelationType(RelationType.RELATED_TO);
        proposal.setStatus(ProposalStatus.PENDING);
        proposal.setConfidence(0.75);
        proposal.setProvenance(provenance);
        proposal.setWorkspaceId(null);
        return proposal;
    }

    private static SystemRepository secondaryRepository() {
        SystemRepository repository = new SystemRepository();
        repository.setRepositoryId("schema-contract-secondary");
        repository.setStorageRepositoryName("schema-contract-secondary-storage");
        repository.setSlug("schema-contract-secondary");
        repository.setDisplayName("Schema contract secondary");
        repository.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        repository.setDefaultBranch("draft");
        repository.setPrimaryRepo(false);
        repository.setOwnerId("schema-contract-test");
        repository.setCreatedBy("schema-contract-test");
        repository.setCreatedAt(Instant.now());
        return repository;
    }

    private TaxonomyRelation relation(
            TaxonomyNode source,
            TaxonomyNode target,
            String description) {
        TaxonomyRelation relation = new TaxonomyRelation();
        relation.setRepositoryId(
                systemRepositoryService.getPrimaryRepository().getRepositoryId());
        relation.setSourceNode(source);
        relation.setTargetNode(target);
        relation.setRelationType(RelationType.CONTAINS);
        relation.setDescription(description);
        relation.setProvenance("schema-contract-test");
        relation.setWorkspaceId(null);
        return relation;
    }

    private static boolean columnExists(
            Connection connection,
            String tableName,
            String columnName) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet tables = metadata.getTables(
                connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String actualTable = tables.getString("TABLE_NAME");
                if (!tableName.equalsIgnoreCase(actualTable)) {
                    continue;
                }
                try (ResultSet columns = metadata.getColumns(
                        tables.getString("TABLE_CAT"),
                        tables.getString("TABLE_SCHEM"),
                        actualTable,
                        "%")) {
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

    private static Set<Set<String>> uniqueColumnSets(
            Connection connection,
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
                if (indexName == null || columnName == null) {
                    continue;
                }
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
