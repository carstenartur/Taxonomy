#!/usr/bin/env python3
"""Apply the final persisted-catalog restart fix for PR #406 exactly once."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = ROOT / path
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one {label}, found {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


service = "taxonomy-app/src/main/java/com/taxonomy/catalog/service/TaxonomyService.java"
replace_once(
    service,
    '''    /** Whether to load taxonomy asynchronously after the server starts. */
    @Value("${taxonomy.init.async:false}")
    private boolean asyncInit;
''',
    '''    /** Whether to load taxonomy asynchronously after the server starts. */
    @Value("${taxonomy.init.async:false}")
    private boolean asyncInit;

    /**
     * Explicit maintenance switch for replacing an already persisted catalogue.
     * Normal restarts reuse the database and its Hibernate Search index.
     */
    @Value("${taxonomy.init.reload-existing:false}")
    private boolean reloadExisting;
''',
    "taxonomy startup configuration block",
)

replace_once(
    service,
    '''    private void doLoadTaxonomy() throws Exception {
        relationRepository.deleteAll();
        repository.deleteAll();

        ClassPathResource resource = new ClassPathResource(CATALOGUE_PATH);
''',
    '''    private void doLoadTaxonomy() throws Exception {
        long persistedNodeCount = repository.count();
        if (persistedNodeCount > 0 && !reloadExisting) {
            log.info("Found {} persisted taxonomy nodes — reusing the existing catalogue and search index.",
                    persistedNodeCount);
            return;
        }

        if (persistedNodeCount > 0) {
            log.warn("Forced taxonomy catalogue reload requested — replacing {} persisted nodes.",
                    persistedNodeCount);
            // Flush deletes before any identity-generated inserts. Without this explicit
            // ordering, HSQLDB can observe a new root insert before the old unique-key row
            // has been removed.
            relationRepository.deleteAll();
            entityManager.flush();
            repository.deleteAll();
            entityManager.flush();
            entityManager.clear();
        }

        ClassPathResource resource = new ClassPathResource(CATALOGUE_PATH);
''',
    "doLoadTaxonomy prologue",
)

properties = "taxonomy-app/src/main/resources/application.properties"
replace_once(
    properties,
    '''taxonomy.init.async=${TAXONOMY_INIT_ASYNC:false}

# create = rebuild schema on each start (safe for in-memory default); override to
''',
    '''taxonomy.init.async=${TAXONOMY_INIT_ASYNC:false}
# Persistent deployments reuse an existing taxonomy catalogue by default. Set
# TAXONOMY_INIT_RELOAD_EXISTING=true only for an intentional destructive catalogue refresh.
taxonomy.init.reload-existing=${TAXONOMY_INIT_RELOAD_EXISTING:false}

# create = rebuild schema on each start (safe for in-memory default); override to
''',
    "taxonomy initialization properties",
)

english = "docs/en/CONFIGURATION_REFERENCE.md"
replace_once(
    english,
    '''---

## LLM Provider Configuration
''',
    '''---

## Startup and Catalogue Initialization

| Variable | Property | Type | Default | Description |
|---|---|---|---|---|
| `TAXONOMY_INIT_ASYNC` | `taxonomy.init.async` | Boolean | `false` | Load the taxonomy after the HTTP server has opened its port. Useful on constrained PaaS platforms. |
| `TAXONOMY_INIT_RELOAD_EXISTING` | `taxonomy.init.reload-existing` | Boolean | `false` | Reuse a persisted catalogue on normal restarts. Set to `true` only for an intentional destructive refresh from the bundled Excel catalogue. Relations are removed before nodes and deletes are flushed before inserts. |

Persistent production deployments should leave `TAXONOMY_INIT_RELOAD_EXISTING=false`. Catalogue replacement is an explicit maintenance operation and should be preceded by a database and index backup.

---

## LLM Provider Configuration
''',
    "English startup configuration section",
)

# The German reference may use a translated heading but has the same first divider.
german = ROOT / "docs/de/CONFIGURATION_REFERENCE.md"
if german.exists():
    text = german.read_text(encoding="utf-8")
    marker = "---\n\n"
    addition = '''---

## Start und Kataloginitialisierung

| Umgebungsvariable | Property | Typ | Standard | Beschreibung |
|---|---|---|---|---|
| `TAXONOMY_INIT_ASYNC` | `taxonomy.init.async` | Boolean | `false` | Lädt die Taxonomie erst, nachdem der HTTP-Server seinen Port geöffnet hat. |
| `TAXONOMY_INIT_RELOAD_EXISTING` | `taxonomy.init.reload-existing` | Boolean | `false` | Verwendet einen persistierten Katalog bei normalen Neustarts weiter. Nur für einen bewusst destruktiven Neuimport aus der mitgelieferten Excel-Datei auf `true` setzen. |

Produktivinstallationen sollten `TAXONOMY_INIT_RELOAD_EXISTING=false` beibehalten. Vor einem erzwungenen Neuimport müssen Datenbank und Suchindex gesichert werden.

'''
    if "TAXONOMY_INIT_RELOAD_EXISTING" not in text:
        index = text.find(marker)
        if index < 0:
            raise SystemExit("German configuration reference divider not found")
        text = text[:index] + addition + text[index + len(marker):]
        german.write_text(text, encoding="utf-8")

print("Applied persisted taxonomy restart fix")
