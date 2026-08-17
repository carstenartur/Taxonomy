#!/usr/bin/env python3
"""Apply the one-off review fix to the JGit schema migration guard."""

from pathlib import Path
import re

PATH = Path(
    "taxonomy-app/src/main/java/com/taxonomy/dsl/storage/"
    "JgitStorageSchemaMigrationConfig.java"
)


def replace_regex_once(text: str, label: str, pattern: str, replacement: str) -> str:
    updated, count = re.subn(
        pattern,
        replacement,
        text,
        count=1,
        flags=re.MULTILINE | re.DOTALL,
    )
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return updated


def main() -> None:
    text = PATH.read_text(encoding="utf-8")

    text = replace_regex_once(
        text,
        "managed schema/history validation",
        r'^            requireMigratableCoreShape\(schema\);\n'
        r'^            log\.info\("Migrating managed JGit Core schema for \{\}", '
        r'family\.displayName\(\)\);\n'
        r'^            flyway\.migrate\(\);\n'
        r'^            requireCurrentCoreShape\(SchemaSnapshot\.inspect\(dataSource\)\);\n'
        r'^            return;$',
        '''            requireMigratableCoreShape(schema);
            requireManagedReflogHistoryConsistency(flyway, schema);
            log.info("Migrating managed JGit Core schema for {}", family.displayName());
            flyway.migrate();
            SchemaSnapshot migratedSchema = SchemaSnapshot.inspect(dataSource);
            requireCurrentCoreShape(migratedSchema);
            requireManagedReflogHistoryConsistency(flyway, migratedSchema);
            return;''',
    )

    helper_marker = (
        "    /** Accept only exact released shapes that Flyway can safely advance "
        "to the pinned release. */\n"
    )
    marker_count = text.count(helper_marker)
    if marker_count != 1:
        raise SystemExit(
            "history consistency helper insertion: expected exactly one marker, "
            f"found {marker_count}"
        )
    helper = '''    private static void requireManagedReflogHistoryConsistency(
            Flyway flyway, SchemaSnapshot schema) {
        boolean migrationApplied =
                isCoreMigrationApplied(flyway, LATEST_CORE_SCHEMA_VERSION);
        boolean referenceKeyPresent = hasReflogReferenceKey(schema);
        if (migrationApplied == referenceKeyPresent) {
            return;
        }
        throw unsafeSchema(
                "Core migration " + LATEST_CORE_SCHEMA_VERSION
                        + " and git_reflog.REF_NAME_KEY must appear together; "
                        + "migration applied=" + migrationApplied
                        + ", reflog columns=" + schema.reflogColumns()
                        + ", reflog indexes=" + schema.reflogIndexes());
    }

    private static boolean isCoreMigrationApplied(Flyway flyway, String version) {
        for (var migration : flyway.info().applied()) {
            if (migration.getVersion() != null
                    && version.equals(migration.getVersion().toString())) {
                return true;
            }
        }
        return false;
    }

'''
    text = text.replace(helper_marker, helper + helper_marker, 1)

    text = replace_regex_once(
        text,
        "reflog-to-pack shape coupling",
        r'^    private static void requireSupportedReflogColumns\(SchemaSnapshot schema\) '
        r'\{\n.*?^    \}\n\n(?=^    private static void requireExactColumns)',
        '''    private static void requireSupportedReflogColumns(SchemaSnapshot schema) {
        if (schema.reflogColumns().equals(PRE_REFLOG_KEY_COLUMNS)) {
            return;
        }
        if (schema.reflogColumns().equals(CURRENT_REFLOG_COLUMNS)) {
            if (!schema.packColumns().equals(CURRENT_PACK_COLUMNS)) {
                throw unsafeSchema(
                        "git_reflog carries the 0.9.1 REF_NAME_KEY shape, but git_packs "
                                + "does not match the current Core pack schema; expected "
                                + "git_packs columns=" + CURRENT_PACK_COLUMNS
                                + ", actual git_packs columns=" + schema.packColumns());
            }
            return;
        }
        throw unsafeSchema(
                "git_reflog is neither the exact pre-0.9.1 shape nor the exact 0.9.1 "
                        + "shape; actual=" + schema.reflogColumns());
    }

''',
    )

    PATH.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
