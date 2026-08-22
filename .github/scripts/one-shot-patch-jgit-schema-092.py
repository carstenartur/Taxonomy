#!/usr/bin/env python3
from pathlib import Path
import re


def sub_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(
        pattern,
        replacement,
        text,
        count=1,
        flags=re.DOTALL | re.MULTILINE,
    )
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return updated


source = Path(
    "taxonomy-app/src/main/java/com/taxonomy/dsl/storage/"
    "JgitStorageSchemaMigrationConfig.java"
)
text = source.read_text(encoding="utf-8")

text = sub_once(
    text,
    r'^    private static final String LATEST_CORE_SCHEMA_VERSION = "0\.9\.1";\n'
    r"    private static final String LATEST_CORE_BASELINE_DESCRIPTION =\n"
    r'            "jgit-storage-hibernate-core 0\.9\.1";',
    '''    private static final String LATEST_CORE_SCHEMA_VERSION = "0.9.1";
    private static final String LATEST_CORE_BASELINE_DESCRIPTION =
            "jgit-storage-hibernate-core 0.9.1";
    private static final String DELIVERY_ID_SCHEMA_VERSION = "0.9.2";
    private static final String DELIVERY_ID_BASELINE_DESCRIPTION =
            "jgit-storage-hibernate-core 0.9.2";''',
    "schema version constants",
)

text = sub_once(
    text,
    r"^    /\*\* Exact physical reflog shape released by Core 0\.9\.1\. \*/\n"
    r"    private static final Set<String> CURRENT_REFLOG_COLUMNS = Set\.of\(\n"
    r'.*?^            "MESSAGE"\);',
    '''    /** Exact physical reflog shape released by Core 0.9.1. */
    private static final Set<String> REFLOG_KEY_COLUMNS = Set.of(
            "ID",
            "VERSION",
            "REPOSITORY_NAME",
            "REF_NAME",
            "REF_NAME_KEY",
            "OLD_ID",
            "NEW_ID",
            "WHO_NAME",
            "WHO_EMAIL",
            "WHO_WHEN",
            "MESSAGE");

    /** Exact physical reflog shape released by Core 0.9.2. */
    private static final Set<String> CURRENT_REFLOG_COLUMNS = Set.of(
            "ID",
            "VERSION",
            "REPOSITORY_NAME",
            "REF_NAME",
            "REF_NAME_KEY",
            "OLD_ID",
            "NEW_ID",
            "WHO_NAME",
            "WHO_EMAIL",
            "WHO_WHEN",
            "MESSAGE",
            "DELIVERY_ID");''',
    "reflog column contracts",
)

text = sub_once(
    text,
    r"^        boolean currentReflogShape = hasReflogReferenceKey\(schema\);\n"
    r"        String baselineVersion = currentReflogShape\n"
    r"                \? LATEST_CORE_SCHEMA_VERSION\n"
    r"                : PRE_REFLOG_KEY_SCHEMA_VERSION;\n"
    r"        String baselineDescription = currentReflogShape\n"
    r"                \? LATEST_CORE_BASELINE_DESCRIPTION\n"
    r"                : PRE_REFLOG_KEY_BASELINE_DESCRIPTION;",
    '''        String baselineVersion;
        String baselineDescription;
        if (hasReflogDeliveryId(schema)) {
            baselineVersion = DELIVERY_ID_SCHEMA_VERSION;
            baselineDescription = DELIVERY_ID_BASELINE_DESCRIPTION;
        } else if (hasReflogReferenceKey(schema)) {
            baselineVersion = LATEST_CORE_SCHEMA_VERSION;
            baselineDescription = LATEST_CORE_BASELINE_DESCRIPTION;
        } else {
            baselineVersion = PRE_REFLOG_KEY_SCHEMA_VERSION;
            baselineDescription = PRE_REFLOG_KEY_BASELINE_DESCRIPTION;
        }''',
    "unversioned schema baseline selection",
)

text = sub_once(
    text,
    r"^    private static void requireManagedReflogHistoryConsistency\(\n"
    r"            Flyway flyway, SchemaSnapshot schema\) \{\n"
    r".*?^    \}\n\n"
    r"(?=    private static boolean isCoreMigrationApplied)",
    '''    private static void requireManagedReflogHistoryConsistency(
            Flyway flyway, SchemaSnapshot schema) {
        boolean deliveryIdMigrationApplied =
                isCoreMigrationApplied(flyway, DELIVERY_ID_SCHEMA_VERSION);
        boolean referenceKeyMigrationApplied =
                isCoreMigrationApplied(flyway, LATEST_CORE_SCHEMA_VERSION)
                        || deliveryIdMigrationApplied;
        requireManagedReflogHistoryConsistency(
                schema,
                LATEST_CORE_SCHEMA_VERSION + " or later",
                "REF_NAME_KEY",
                referenceKeyMigrationApplied,
                hasReflogReferenceKey(schema));
        requireManagedReflogHistoryConsistency(
                schema,
                DELIVERY_ID_SCHEMA_VERSION,
                "DELIVERY_ID",
                deliveryIdMigrationApplied,
                hasReflogDeliveryId(schema));
    }

    private static void requireManagedReflogHistoryConsistency(
            SchemaSnapshot schema,
            String migrationVersion,
            String column,
            boolean migrationApplied,
            boolean columnPresent) {
        if (migrationApplied == columnPresent) {
            return;
        }
        throw unsafeSchema(
                "Core migration " + migrationVersion
                        + " and git_reflog." + column + " must appear together; "
                        + "migration applied=" + migrationApplied
                        + ", reflog columns=" + schema.reflogColumns()
                        + ", reflog indexes=" + schema.reflogIndexes());
    }

''',
    "managed reflog history consistency",
)

text = sub_once(
    text,
    r"^    private static boolean hasReflogReferenceKey\(SchemaSnapshot schema\) \{\n"
    r".*?^    \}\n\n"
    r"(?=    private static void requireExactColumns)",
    '''    private static boolean hasReflogReferenceKey(SchemaSnapshot schema) {
        return schema.reflogColumns().equals(REFLOG_KEY_COLUMNS)
                || schema.reflogColumns().equals(CURRENT_REFLOG_COLUMNS);
    }

    private static boolean hasReflogDeliveryId(SchemaSnapshot schema) {
        return schema.reflogColumns().equals(CURRENT_REFLOG_COLUMNS);
    }

    private static void requirePreReflogKeyColumns(SchemaSnapshot schema) {
        requireExactColumns(
                "git_reflog", schema.reflogColumns(), PRE_REFLOG_KEY_COLUMNS);
    }

    private static void requireSupportedReflogColumns(SchemaSnapshot schema) {
        if (schema.reflogColumns().equals(PRE_REFLOG_KEY_COLUMNS)) {
            return;
        }
        if (schema.reflogColumns().equals(REFLOG_KEY_COLUMNS)
                || schema.reflogColumns().equals(CURRENT_REFLOG_COLUMNS)) {
            if (!schema.packColumns().equals(CURRENT_PACK_COLUMNS)) {
                throw unsafeSchema(
                        "git_reflog carries a released REF_NAME_KEY shape, but git_packs "
                                + "does not match the current Core pack schema; expected "
                                + "git_packs columns=" + CURRENT_PACK_COLUMNS
                                + ", actual git_packs columns=" + schema.packColumns());
            }
            return;
        }
        throw unsafeSchema(
                "git_reflog is neither the exact pre-0.9.1, 0.9.1 nor 0.9.2 shape; "
                        + "actual=" + schema.reflogColumns());
    }

''',
    "supported reflog shapes",
)

source.write_text(text, encoding="utf-8")

test = Path(
    "taxonomy-app/src/test/java/com/taxonomy/dsl/storage/"
    "JgitStorageSchemaMigrationConfigTest.java"
)
text = test.read_text(encoding="utf-8")
text = sub_once(
    text,
    r'^        boolean hasReflogKey = columns\(dataSource, "git_reflog"\)'
    r'\.contains\("REF_NAME_KEY"\);\n'
    r"        dropTable\(dataSource, CoreSchemaMigrations\.SCHEMA_HISTORY_TABLE\);\n\n"
    r"        JgitStorageSchemaMigrationConfig\.migrateCoreSchema"
    r"\(flyway\(dataSource\), false\);\n\n"
    r"        assertVersionPrefix\(\n"
    r'                List\.of\(hasReflogKey \? "0\.9\.1" : "0\.1\.18"\),\n'
    r"                successfulVersions\(dataSource, "
    r"CoreSchemaMigrations\.SCHEMA_HISTORY_TABLE\)\);",
    '''        Set<String> reflogColumns = columns(dataSource, "git_reflog");
        boolean hasReflogKey = reflogColumns.contains("REF_NAME_KEY");
        boolean hasDeliveryId = reflogColumns.contains("DELIVERY_ID");
        dropTable(dataSource, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE);

        JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway(dataSource), false);

        String expectedBaseline;
        if (hasDeliveryId) {
            expectedBaseline = "0.9.2";
        } else if (hasReflogKey) {
            expectedBaseline = "0.9.1";
        } else {
            expectedBaseline = "0.1.18";
        }
        assertVersionPrefix(
                List.of(expectedBaseline),
                successfulVersions(dataSource, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE));''',
    "unversioned current schema test",
)

text = sub_once(
    text,
    r"^    private static void assertReflogKeyMatchesMigrationHistory"
    r"\(DataSource dataSource\)\n"
    r"            throws SQLException \{\n"
    r".*?^    \}\n\n"
    r"(?=    private static void assertVersionPrefix)",
    '''    private static void assertReflogKeyMatchesMigrationHistory(DataSource dataSource)
            throws SQLException {
        List<String> versions = successfulVersions(
                dataSource,
                CoreSchemaMigrations.SCHEMA_HISTORY_TABLE);
        Set<String> reflogColumns = columns(dataSource, "git_reflog");
        assertEquals(
                versions.contains("0.9.1") || versions.contains("0.9.2"),
                reflogColumns.contains("REF_NAME_KEY"),
                "Core migration 0.9.1 or later and git_reflog.ref_name_key must appear together");
        assertEquals(
                versions.contains("0.9.2"),
                reflogColumns.contains("DELIVERY_ID"),
                "Core migration 0.9.2 and git_reflog.delivery_id must appear together");
    }

''',
    "reflog migration history test",
)
test.write_text(text, encoding="utf-8")
