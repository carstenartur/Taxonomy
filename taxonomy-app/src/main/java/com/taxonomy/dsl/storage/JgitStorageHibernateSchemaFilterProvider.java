package com.taxonomy.dsl.storage;

import java.util.Locale;
import java.util.Set;

import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilterProvider;

/**
 * Keeps the Flyway-owned JGit Core tables outside Hibernate schema mutation.
 *
 * <p>The entities remain mapped and are still included in schema validation. Only
 * create, migrate, truncate and drop operations exclude the two Core tables.</p>
 */
public final class JgitStorageHibernateSchemaFilterProvider implements SchemaFilterProvider {

    private static final Set<String> FLYWAY_OWNED_TABLES = Set.of("git_packs", "git_reflog");

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
