from pathlib import Path

ROOT = Path('taxonomy-app/src/main')
def write(path, text):
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    assert not p.exists(), f'already exists: {p}'
    p.write_text(text, encoding='utf-8')
def replace(path, old, new):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    assert text.count(old) == 1, (path, old[:100], text.count(old))
    p.write_text(text.replace(old, new), encoding='utf-8')
def method(path, signature, replacement):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    start = text.index(signature)
    pos = text.index('{', start)
    depth = 1
    end = pos + 1
    while depth:
        if text[end] == '{': depth += 1
        elif text[end] == '}': depth -= 1
        end += 1
    p.write_text(text[:start] + replacement + text[end:], encoding='utf-8')

write(ROOT / 'resources/application-hsqldb-file.properties', '''# Disk-backed HSQLDB for local analysis without an external database server.
# Select this database profile instead of hsqldb; production may be combined with it.
# This creates a separate database: export an existing in-memory database BEFORE stopping it.
# Existing MEMORY tables are not converted by default_table_type; migrate them explicitly.
spring.datasource.url=${TAXONOMY_DATASOURCE_URL:jdbc:hsqldb:file:${TAXONOMY_HSQLDB_FILE_PATH:./data/taxonomydb};hsqldb.default_table_type=cached;hsqldb.cache_size=${TAXONOMY_HSQLDB_CACHE_SIZE_KB:4096};hsqldb.cache_rows=${TAXONOMY_HSQLDB_CACHE_ROWS:10000};hsqldb.write_delay_millis=0;shutdown=true}
spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver
spring.datasource.type=com.zaxxer.hikari.HikariDataSource
spring.datasource.username=sa
spring.datasource.password=
# Keep a connection open until application shutdown (required by shutdown=true).
spring.datasource.hikari.minimum-idle=${TAXONOMY_DB_MIN_IDLE:1}
spring.datasource.hikari.maximum-pool-size=${TAXONOMY_DB_MAX_POOL_SIZE:4}
spring.datasource.hikari.connection-timeout=${TAXONOMY_DB_CONNECTION_TIMEOUT_MS:30000}
spring.datasource.hikari.pool-name=taxonomy-hsqldb-file
spring.jpa.database-platform=org.hibernate.dialect.HSQLDialect
spring.jpa.hibernate.ddl-auto=${TAXONOMY_DDL_AUTO:update}
# Both growing stores must leave the Java heap, not only the database.
spring.jpa.properties.hibernate.search.backend.directory.type=${TAXONOMY_SEARCH_DIRECTORY_TYPE:local-filesystem}
spring.jpa.properties.hibernate.search.backend.directory.root=${TAXONOMY_SEARCH_DIRECTORY_ROOT:./data/lucene-index}
# Preserve the released JGit library migration ownership boundary.
spring.flyway.enabled=true
spring.jpa.properties.hibernate.hbm2ddl.schema_filter_provider=com.taxonomy.workspace.storage.JgitStorageHibernateSchemaFilterProvider
taxonomy.jgit-storage.legacy-adoption=${TAXONOMY_JGIT_STORAGE_LEGACY_ADOPTION:false}
''')

write(ROOT / 'java/com/taxonomy/analysis/service/LlmDetailAccumulator.java', '''package com.taxonomy.analysis.service;

import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.TaxonomyDiscrepancy;

import java.util.LinkedHashMap;
import java.util.Map;

/** Incremental diagnostic aggregation; scores and explanations are never truncated. */
final class LlmDetailAccumulator {
    private static final int TEXT_LIMIT = 32_768;
    private static final String TRUNCATED = "\\n[diagnostic transcript truncated]";
    private final Map<String, Integer> scores = new LinkedHashMap<>();
    private final Map<String, String> reasons = new LinkedHashMap<>();
    private final StringBuilder prompts = new StringBuilder();
    private final StringBuilder responses = new StringBuilder();
    private final StringBuilder errors = new StringBuilder();
    private String provider;
    private TaxonomyDiscrepancy discrepancy;
    private long duration;
    private int count;

    void add(LlmCallDetail detail) {
        count++;
        if (detail.getScores() != null) scores.putAll(detail.getScores());
        if (detail.getReasons() != null) reasons.putAll(detail.getReasons());
        if (provider == null) provider = detail.getProvider();
        if (discrepancy == null) discrepancy = detail.getDiscrepancy();
        duration += detail.getDurationMs();
        if (detail.getPrompt() != null) {
            append(prompts, "--- call " + count + " ---\\n");
            append(prompts, detail.getPrompt());
            append(prompts, "\\n");
        }
        if (detail.getRawResponse() != null) {
            append(responses, "--- call " + count + " ---\\n");
            append(responses, detail.getRawResponse());
            append(responses, "\\n");
        }
        if (detail.getError() != null) {
            if (!errors.isEmpty()) append(errors, "; ");
            append(errors, detail.getError());
        }
    }

    LlmCallDetail result() {
        LlmCallDetail result = new LlmCallDetail();
        result.setScores(scores);
        result.setReasons(reasons);
        result.setProvider(provider);
        result.setPrompt(prompts.toString());
        result.setRawResponse(responses.toString());
        result.setError(errors.isEmpty() ? null : errors.toString());
        result.setDurationMs(duration);
        result.setDiscrepancy(discrepancy);
        return result;
    }

    private static void append(StringBuilder target, String value) {
        if (target.length() >= TEXT_LIMIT) return;
        if (value.length() <= TEXT_LIMIT - target.length()) {
            target.append(value);
            return;
        }
        int contentLimit = TEXT_LIMIT - TRUNCATED.length();
        if (target.length() > contentLimit) target.setLength(contentLimit);
        target.append(value, 0, Math.min(value.length(), contentLimit - target.length()));
        target.append(TRUNCATED);
    }
}
''')
llm = ROOT / 'java/com/taxonomy/analysis/service/LlmService.java'
method(llm, '    private LlmCallDetail mergeDetails(List<LlmCallDetail> details)', '''    private LlmCallDetail mergeDetails(List<LlmCallDetail> details) {
        LlmDetailAccumulator accumulator = new LlmDetailAccumulator();
        details.forEach(accumulator::add);
        return accumulator.result();
    }''')
method(llm, '    private LlmCallDetail callProductBatchesDetailed(', '''    private LlmCallDetail callProductBatchesDetailed(String businessText,
                                                      List<TaxonomyNode> products) {
        if (products.isEmpty()) return detailFromScores(Map.of(), Map.of(), null);
        List<TaxonomyNode> ordered = products.stream()
                .sorted(Comparator.comparing(TaxonomyNode::getCode,
                        Comparator.nullsLast(String::compareTo)))
                .toList();
        int batchSize = Math.max(1, config.getProductBatchSize());
        LlmDetailAccumulator accumulator = new LlmDetailAccumulator();
        for (int from = 0; from < ordered.size(); from += batchSize) {
            List<TaxonomyNode> batch = ordered.subList(from, Math.min(from + batchSize, ordered.size()));
            accumulator.add(callProductBatchDetailed(businessText, batch));
        }
        return accumulator.result();
    }''')
app = ROOT / 'java/com/taxonomy/AppConfig.java'
replace(app, 'import java.util.concurrent.Executors;', 'import java.util.concurrent.ArrayBlockingQueue;\nimport java.util.concurrent.ThreadPoolExecutor;\nimport java.util.concurrent.TimeUnit;')
replace(app, 'return Executors.newFixedThreadPool(poolSize);', 'return new ThreadPoolExecutor(poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,\n                new ArrayBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy());')
for lang, text in {
 'en': '''\n## Disk-backed local analysis (`hsqldb-file`)\n\nSelect `SPRING_PROFILES_ACTIVE=hsqldb-file` instead of `hsqldb`. Both HSQLDB and\nLucene then use files; application DDL defaults to `update`, while the JGit library\nkeeps ownership of its released migrations. Do not combine two database profiles.\n\n| Environment variable | Default | Purpose |\n|---|---|---|\n| `TAXONOMY_HSQLDB_FILE_PATH` | `./data/taxonomydb` | Writable database file prefix; use a persistent volume in containers. |\n| `TAXONOMY_HSQLDB_CACHE_SIZE_KB` | `4096` | Cached-table serialized-data cache budget in KiB; not a total JVM memory limit. |\n| `TAXONOMY_HSQLDB_CACHE_ROWS` | `10000` | Maximum cached table rows. |\n\n`TAXONOMY_DATASOURCE_URL` can still override the full URL. The defaults select\n`CACHED` tables and disable delayed log synchronization. Existing `MEMORY` tables\nare not converted automatically. Export needed in-memory data **before shutdown**;\nswitching profiles does not migrate it. Back up persistent data before schema or\ntable-type migration. Never use `TAXONOMY_DDL_AUTO=create` against retained data.\n\n`TAXONOMY_SEARCH_DIRECTORY_TYPE` defaults to `local-filesystem` in this profile;\n`TAXONOMY_SEARCH_DIRECTORY_ROOT` defaults to `./data/lucene-index`. Filesystem\nbacking reduces heap residency but is not a guarantee against out-of-memory errors.\n''',
 'de': '''\n## Dateibasierte lokale Analyse (`hsqldb-file`)\n\nMit `SPRING_PROFILES_ACTIVE=hsqldb-file` statt `hsqldb` verwenden sowohl HSQLDB\nals auch Lucene Dateien. Anwendungs-DDL verwendet standardmäßig `update`; die\nveröffentlichten JGit-Migrationen bleiben Eigentum der Bibliothek. Nicht mehrere\nDatenbankprofile kombinieren.\n\n| Umgebungsvariable | Standard | Bedeutung |\n|---|---|---|\n| `TAXONOMY_HSQLDB_FILE_PATH` | `./data/taxonomydb` | Beschreibbares Datenbank-Dateipräfix; in Containern ein dauerhaftes Volume verwenden. |\n| `TAXONOMY_HSQLDB_CACHE_SIZE_KB` | `4096` | Cachebudget serialisierter Tabellendaten in KiB; keine Gesamtgrenze des JVM-Speichers. |\n| `TAXONOMY_HSQLDB_CACHE_ROWS` | `10000` | Höchstzahl zwischengespeicherter Tabellenzeilen. |\n\n`TAXONOMY_DATASOURCE_URL` kann weiterhin die ganze URL überschreiben. Standardmäßig\nwerden `CACHED`-Tabellen ohne verzögerte Log-Synchronisierung verwendet. Bestehende\n`MEMORY`-Tabellen werden nicht automatisch konvertiert. Benötigte In-Memory-Daten\n**vor dem Herunterfahren exportieren**; der Profilwechsel migriert sie nicht.\nVor Schema- oder Tabellentypänderungen persistente Daten sichern. Für aufzubewahrende\nDaten niemals `TAXONOMY_DDL_AUTO=create` verwenden.\n\n`TAXONOMY_SEARCH_DIRECTORY_TYPE` ist in diesem Profil standardmäßig `local-filesystem`,\n`TAXONOMY_SEARCH_DIRECTORY_ROOT` ist `./data/lucene-index`. Dateispeicherung reduziert\ndie Heap-Belegung, garantiert aber nicht, dass jeder Speichermangel verhindert wird.\n'''
}.items():
    p = Path('docs') / lang / 'CONFIGURATION_REFERENCE.md'
    with p.open('a', encoding='utf-8') as f: f.write(text)
with Path('.env.example').open('a', encoding='utf-8') as f:
    f.write('\n# Disk-backed local analysis: select hsqldb-file instead of hsqldb.\n# Export in-memory data before stopping; switching profiles does not migrate it.\n# SPRING_PROFILES_ACTIVE=hsqldb-file\n# TAXONOMY_HSQLDB_FILE_PATH=./data/taxonomydb\n# TAXONOMY_HSQLDB_CACHE_SIZE_KB=4096\n# TAXONOMY_HSQLDB_CACHE_ROWS=10000\n')
print('Applied disk profile, bounded executor and incremental diagnostic aggregation')
