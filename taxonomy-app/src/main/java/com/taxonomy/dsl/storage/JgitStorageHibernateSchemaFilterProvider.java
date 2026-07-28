package com.taxonomy.dsl.storage;

import java.util.Locale;
import java.util.Set;

import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilterProvider;

/**
 * Keeps every Flyway-owned JGit Core table outside Hibernate schema mutation.
 *
 * <p>The entities remain mapped and are still included in schema validation. Only
 * create, migrate, truncate and drop operations exclude the complete released Core
 * schema. This is deliberately a table allow-list rather than a {@code git_*}
 * wildcard so an unrelated application table cannot silently escape Hibernate's
 * lifecycle.</p>
 */
public final class JgitStorageHibernateSchemaFilterProvider implements SchemaFilterProvider {

    private static final Set<String> FLYWAY_OWNED_TABLES = Set.of(
            "git_packs",
            "git_reflog",
            "git_repository_lock",
            "git_pack_chunks");

    private static final SchemaFilter MUTATION_FILTER = new SchemaFilter() {
        @Override
        public boolean includeNamespace(Namespace namespace) {
            return true;
        }

        @Override
        public boolean includeTable(Table table) {
            return !FLYWAY_OWNED_TABLES.contains(table.getName().toLowerCase(Locale.ROOT));
        }

        @Override
        public boolean includeSequence(Sequence sequence) {
            return true;
        }
    };

    @Override
    public SchemaFilter getCreateFilter() {
        return MUTATION_FILTER;
    }

    @Override
    public SchemaFilter getDropFilter() {
        return MUTATION_FILTER;
    }

    @Override
    public SchemaFilter getTruncatorFilter() {
        return MUTATION_FILTER;
    }

    @Override
    public SchemaFilter getMigrateFilter() {
        return MUTATION_FILTER;
    }

    @Override
    public SchemaFilter getValidateFilter() {
        return SchemaFilter.ALL;
    }
}
