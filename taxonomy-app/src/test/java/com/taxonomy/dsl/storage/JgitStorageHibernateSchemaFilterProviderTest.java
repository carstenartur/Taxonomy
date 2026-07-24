package com.taxonomy.dsl.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.junit.jupiter.api.Test;

class JgitStorageHibernateSchemaFilterProviderTest {

    private final JgitStorageHibernateSchemaFilterProvider provider =
            new JgitStorageHibernateSchemaFilterProvider();

    @Test
    void excludesOnlyCoreTablesFromEveryMutationFilter() {
        assertMutationBoundary(provider.getCreateFilter());
        assertMutationBoundary(provider.getDropFilter());
        assertMutationBoundary(provider.getTruncatorFilter());
        assertMutationBoundary(provider.getMigrateFilter());
    }

    @Test
    void keepsCoreTablesInSchemaValidation() {
        assertThat(provider.getValidateFilter().includeTable(table("git_packs"))).isTrue();
        assertThat(provider.getValidateFilter().includeTable(table("git_reflog"))).isTrue();
        assertThat(provider.getValidateFilter().includeTable(table("taxonomy_node"))).isTrue();
    }

    private static void assertMutationBoundary(SchemaFilter filter) {
        assertThat(filter.includeTable(table("git_packs"))).isFalse();
        assertThat(filter.includeTable(table("GIT_REFLOG"))).isFalse();
        assertThat(filter.includeTable(table("taxonomy_node"))).isTrue();
    }

    private static Table table(String name) {
        Table table = mock(Table.class);
        when(table.getName()).thenReturn(name);
        return table;
    }
}
