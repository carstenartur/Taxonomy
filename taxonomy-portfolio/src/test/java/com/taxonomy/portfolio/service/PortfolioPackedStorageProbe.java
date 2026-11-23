package com.taxonomy.portfolio.service;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.model.PortfolioTenantIdentity;
import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.ProjectRequirementVersion;
import com.taxonomy.portfolio.model.RequirementAnalysisJob;
import com.taxonomy.portfolio.model.RequirementAnalysisSnapshot;
import com.taxonomy.portfolio.model.SnapshotReference;
import com.taxonomy.portfolio.payload.PortfolioAnalysisPayloads;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hsqldb.jdbc.JDBCDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import static com.taxonomy.portfolio.model.PortfolioTypes.*;

/** Opt-in file HSQL A/B. JDBC counters and the paired fixture rotation derive from HsqlContinuationBench. */
public final class PortfolioPackedStorageProbe {
    private static final String JOB = "d29253a7-6896-42e9-9760-959c462638fa";
    private static final String SCOPE = new PortfolioTenantIdentity("a3ac19a9-ca35-4dc3-9a6c-294fc85950a3",
            "WORKSPACE:benchmark", "draft").scopeKey();
    private static final String FRAME = "taxonomy-json-payload:v1:";
    private static final Instant NOW = Instant.parse("2026-11-23T00:00:00Z");
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private final JdbcCounters jdbc = new JdbcCounters();
    private final PortfolioJsonCodec codec = new PortfolioJsonCodec(JsonMapper.builder().build()) {
        @Override public <T> T read(Reader input, Class<T> type) {
            if (type == AnalysisResult.class) jdbc.binds++;
            return super.read(input, type);
        }
    };

    private record Parents(ArchitectureProject project, ProjectRequirement requirement,
                           ProjectRequirementVersion version, RequirementAnalysisJob job) {
        RequirementAnalysisSnapshot snapshot(String id, String payload) {
            return new RequirementAnalysisSnapshot(id, project, requirement, version, job, AnalysisStatus.PARTIAL,
                    "portfolio:" + id, "LOCAL", "fixture", null, null, "benchmark", "draft", null, "owner", NOW,
                    10, 0, null, payload, null, null, null);
        }
    }

    private record Sample(long ns, long cpu, long alloc, long selects, long dml, long chars, long binds,
                          long locators, long streams, long closes, long frees, long commits, long commitNs) {
        Sample plus(Sample other) {
            return new Sample(ns + other.ns, cpu + other.cpu, alloc + other.alloc, selects + other.selects,
                    dml + other.dml, chars + other.chars, binds + other.binds, locators + other.locators, streams + other.streams,
                    closes + other.closes, frees + other.frees, commits + other.commits, commitNs + other.commitNs);
        }
    }

    private record Timed<T>(T value, Sample sample) { }

    private static final class Trial {
        final boolean packed;
        final int variant;
        final PortfolioSnapshotEvidenceReader reader;
        final int chars;
        final int changedChars;
        final int writeChars;
        final LinkedHashMap<String, List<Sample>> samples = new LinkedHashMap<>();
        String previous = newId();

        Trial(boolean packed, int variant, PortfolioSnapshotEvidenceReader reader,
              String base, String changed, String writerJson) {
            this.packed = packed;
            this.variant = variant;
            this.reader = reader;
            chars = base.length();
            changedChars = changed.length();
            writeChars = PortfolioAnalysisPayloads.store(writerJson, packed).length();
        }

        void record(String mode, Sample sample, boolean measured) {
            if (measured) samples.computeIfAbsent(mode, ignored -> new ArrayList<>()).add(sample);
        }

        void report() {
            String label = packed ? "v1" : "raw";
            System.out.printf("fixture=%s stored_chars=%d changed_chars=%d write_stored_chars=%d%n",
                    label, chars, changedChars, writeChars);
            samples.forEach((mode, rows) -> PortfolioPackedStorageProbe.report(label + "/" + mode, rows));
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected <absolute-json> <warmup-pairs> <measured-pairs>");
        Path input = Path.of(args[0]);
        int warmups = Integer.parseInt(args[1]);
        int measured = Integer.parseInt(args[2]);
        if (!input.isAbsolute() || warmups < 0 || warmups > 100 || measured < 1 || measured > 100
                || Files.size(input) > 4L * 24_000_000) {
            throw new IllegalArgumentException("Expected a bounded absolute input, 0–100 warmups and 1–100 measured pairs");
        }
        THREADS.setThreadAllocatedMemoryEnabled(true);
        THREADS.setThreadCpuTimeEnabled(true);
        new PortfolioPackedStorageProbe().run(input, warmups, measured);
    }

    private void run(Path input, int warmups, int measured) throws Exception {
        String raw = Files.readString(input);
        String changed = changedStatus(raw);
        String packed = PortfolioAnalysisPayloads.store(raw, true);
        String changedPacked = PortfolioAnalysisPayloads.store(changed, true);
        require(packed.startsWith(FRAME) && changedPacked.startsWith(FRAME), "probe requires compressible v1 inputs");
        require(raw.equals(PortfolioAnalysisPayloads.plain(packed))
                && changed.equals(PortfolioAnalysisPayloads.plain(changedPacked)), "exact original JSON parity");
        AnalysisResult writerAnalysis = codec.read(raw, AnalysisResult.class);
        String writerJson = codec.write(writerAnalysis);
        var baseline = ContinuationPolicyInputs.from(writerAnalysis);
        var replacement = ContinuationPolicyInputs.from(codec.read(changed, AnalysisResult.class));
        require(!Objects.equals(decision(baseline), decision(replacement)), "replacement must change the policy decision");
        System.out.printf("jvm=%s os=%s/%s processors=%d max_heap_bytes=%d bytes_utf8=%d warmup_pairs=%d measured_pairs=%d%n",
                Runtime.version(), System.getProperty("os.name"), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory(), Files.size(input), warmups, measured);

        Path directory = Files.createTempDirectory(Path.of("target"), "portfolio-packed-hsql-");
        var source = new JDBCDataSource();
        source.setUrl("jdbc:hsqldb:file:" + directory.toAbsolutePath().resolve("database")
                + ";hsqldb.default_table_type=cached;hsqldb.cache_size=4096;hsqldb.cache_rows=10000"
                + ";hsqldb.write_delay_millis=0;shutdown=true");
        source.setUser("SA");
        source.setPassword("");
        try (Connection anchor = source.getConnection(); SessionFactory factory = factory(source)) {
            var tx = new TransactionTemplate(new JpaTransactionManager(factory));
            EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(factory);
            Parents parents = factory.fromTransaction(PortfolioPackedStorageProbe::seedParents);
            var rawTrial = new Trial(false, 0, new PortfolioSnapshotEvidenceReader(shared, codec), raw, changed, writerJson);
            var packedTrial = new Trial(true, 3, new PortfolioSnapshotEvidenceReader(shared, codec), packed, changedPacked, writerJson);
            seedPayloads(anchor, List.of(raw, changed, corruptRaw(raw), packed, changedPacked, corruptFrame(packed)));
            factory.inTransaction(session -> {
                session.persist(parents.snapshot(rawTrial.previous, "{}"));
                session.persist(parents.snapshot(packedTrial.previous, "{}"));
            });
            for (var trial : List.of(rawTrial, packedTrial)) {
                verifyOutsideTimings(anchor, tx, parents, trial, writerJson, baseline, replacement);
            }
            System.out.println("exact_text_and_full_binding_parity=true same_size_corrupt_raw_rejected=true same_size_corrupt_v1_rejected=true");
            for (int pair = 0; pair < warmups + measured; pair++) {
                boolean keep = pair >= warmups;
                List<Trial> order = pair % 2 == 0 ? List.of(rawTrial, packedTrial) : List.of(packedTrial, rawTrial);
                for (Trial trial : order) {
                    runReadCases(anchor, factory, tx, parents, trial, baseline, replacement, pair, keep);
                    runWriteAndReadCases(anchor, factory, tx, shared, parents, trial, writerAnalysis, baseline, keep);
                }
            }
            rawTrial.report();
            packedTrial.report();
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private SessionFactory factory(DataSource source) {
        var configuration = new Configuration().setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.generate_statistics", "true").setProperty("hibernate.session.events.log", "false")
                .setProperty("hibernate.search.enabled", "false");
        configuration.getProperties().put("hibernate.connection.datasource", jdbc.source(source));
        for (var entity : List.of(ArchitectureProject.class, ProjectRequirement.class, ProjectRequirementVersion.class,
                RequirementAnalysisJob.class, RequirementAnalysisSnapshot.class, SnapshotReference.class)) {
            configuration.addAnnotatedClass(entity);
        }
        return configuration.buildSessionFactory();
    }

    private static Parents seedParents(EntityManager em) {
        var project = new ArchitectureProject(SCOPE, "benchmark", "owner", "BENCH", "Benchmark project", null,
                ProjectStatus.ACTIVE, NOW);
        em.persist(project);
        em.flush();
        var requirement = new ProjectRequirement(project, "BENCH-REQ", "Requirement", RequirementStatus.DRAFT,
                50, Criticality.MEDIUM, RequirementType.FUNCTIONAL, ReviewStatus.PROPOSED, "owner", NOW);
        em.persist(requirement);
        em.flush();
        var version = new ProjectRequirementVersion(requirement, 1, "Original requirement", "b".repeat(64),
                null, "owner", NOW, null, null, null, null, null, null);
        em.persist(version);
        em.flush();
        var job = new RequirementAnalysisJob(JOB, project, "BENCH", "LOCAL", 30, "owner", "benchmark", 1, NOW);
        em.persist(job);
        em.flush();
        return new Parents(project, requirement, version, job);
    }

    private void verifyOutsideTimings(Connection anchor, TransactionTemplate tx, Parents p, Trial trial,
                                      String normalized, ContinuationPolicyInputs original,
                                      ContinuationPolicyInputs changed) throws Exception {
        rotate(anchor, trial.previous, trial.previous, trial.variant);
        require(storedLength(anchor, trial.previous) == trial.chars, "actual initial fixture CLOB length");
        require(tx.execute(state -> codec.write(full(trial, p, trial.previous))).equals(normalized), "full DTO parity");
        var baseline = tx.execute(state -> continuation(trial, p, trial.previous));
        verifyInputs(baseline, original);
        rotate(anchor, trial.previous, trial.previous, trial.variant + 2);
        require(storedLength(anchor, trial.previous) == trial.chars, "corruption fixture must match original physical length");
        try {
            tx.execute(state -> continuation(trial, p, trial.previous));
            throw new AssertionError("corrupted fixture returned continuation inputs");
        } catch (PortfolioException invalid) {
            require(invalid.getKind() == PortfolioException.Kind.ANALYSIS_FAILED, "corruption failure kind");
        }
        rotate(anchor, trial.previous, trial.previous, trial.variant + 1);
        require(storedLength(anchor, trial.previous) == trial.changedChars, "actual changed fixture CLOB length");
        verifyInputs(tx.execute(state -> continuation(trial, p, trial.previous)), changed);
    }

    private void runReadCases(Connection anchor, SessionFactory factory, TransactionTemplate tx, Parents p, Trial trial,
                              ContinuationPolicyInputs baseline, ContinuationPolicyInputs replacement,
                              int pair, boolean measured) throws Exception {
        String id = newId();
        rotate(anchor, trial.previous, id, trial.variant);
        trial.previous = id;
        long before = factory.getStatistics().getSessionOpenCount();
        Timed<AnalysisResult> full = pair % 2 == 0 ? time(() -> tx.execute(state -> full(trial, p, id))) : null;
        var cold = time(() -> tx.execute(state -> continuation(trial, p, id)));
        var hit = time(() -> tx.execute(state -> continuation(trial, p, id)));
        if (full == null) full = time(() -> tx.execute(state -> full(trial, p, id)));
        rotate(anchor, id, id, trial.variant + 1);
        var changed = time(() -> tx.execute(state -> continuation(trial, p, id)));
        require(factory.getStatistics().getSessionOpenCount() - before == 4, "fresh entity manager per read");
        require(cold.value == hit.value && cold.value != changed.value, "memo ownership after hit or replacement");
        verifyInputs(ContinuationPolicyInputs.from(full.value), baseline);
        verifyInputs(cold.value, baseline);
        verifyInputs(changed.value, replacement);
        verifyRead("full", full.sample, 1, trial.chars, 1);
        verifyRead("cold", cold.sample, 1, trial.chars, 1);
        verifyRead("hit", hit.sample, 1, trial.chars, 0);
        verifyRead("changed", changed.sample, 2, 2L * trial.changedChars, 1);
        trial.record("full", full.sample, measured);
        trial.record("cold", cold.sample, measured);
        trial.record("hit", hit.sample, measured);
        trial.record("changed", changed.sample, measured);
    }

    private void runWriteAndReadCases(Connection anchor, SessionFactory factory, TransactionTemplate tx,
                                      EntityManager shared, Parents p, Trial trial, AnalysisResult analysis,
                                      ContinuationPolicyInputs baseline, boolean measured) throws Exception {
        String id = newId();
        long before = factory.getStatistics().getSessionOpenCount();
        var encoding = time(() -> PortfolioAnalysisPayloads.store(codec.write(analysis), trial.packed));
        // Spring Data's save uses merge for these snapshots because their IDs are assigned.
        var write = time(() -> tx.execute(state -> shared.merge(p.snapshot(id, encoding.value))));
        require(encoding.value.length() == trial.writeChars && storedLength(anchor, id) == trial.writeChars,
                "committed physical CLOB length matches normal writer output");
        require(write.sample.dml == 1 && write.sample.commits == 1, "snapshot INSERT and commit count");
        Sample total = encoding.sample.plus(write.sample);
        trial.record("write-encode", encoding.sample, measured);
        trial.record("write-tx", write.sample, measured);
        trial.record("write-total", total, measured);

        ContinuationPolicyInputs cold = null;
        for (int read = 1; read <= 3; read++) {
            var result = time(() -> tx.execute(state -> continuation(trial, p, id)));
            if (read == 1) cold = result.value;
            require(cold == result.value, "reads of newly committed snapshot reuse memo after cold read");
            verifyInputs(result.value, baseline);
            verifyRead("write+r" + read, result.sample, 1, trial.writeChars, read == 1 ? 1 : 0);
            total = total.plus(result.sample);
            trial.record("write+r" + read, total, measured);
        }
        require(factory.getStatistics().getSessionOpenCount() - before == 4, "fresh entity managers for write and each read");
        try (var delete = anchor.prepareStatement("delete from req_analysis_snapshot where id=? and scope_key=?")) {
            delete.setString(1, id);
            delete.setString(2, SCOPE);
            require(delete.executeUpdate() == 1, "committed fixture removal");
        }
    }

    private static AnalysisResult full(Trial trial, Parents p, String id) {
        return trial.reader.readAnalysis(p.project.getId(), id, SCOPE, JOB, p.requirement.getId(), p.version.getId())
                .orElseThrow().analysis();
    }

    private static ContinuationPolicyInputs continuation(Trial trial, Parents p, String id) {
        return trial.reader.readContinuation(p.project.getId(), id, SCOPE, JOB, p.requirement.getId(), p.version.getId())
                .orElseThrow().inputs();
    }

    private static void verifyInputs(ContinuationPolicyInputs actual, ContinuationPolicyInputs expected) {
        require(expected.equals(actual) && Objects.equals(decision(expected), decision(actual)), "fresh DTO and policy parity");
    }

    private static String decision(ContinuationPolicyInputs inputs) {
        return ProjectAnalysisContinuationService.budgetProblemForInputs(inputs);
    }

    private <T> Timed<T> time(Supplier<T> work) {
        long q = jdbc.selects, d = jdbc.dml, c = jdbc.chars, b = jdbc.binds, l = jdbc.locators;
        long streams = jdbc.streams, closed = jdbc.closes, f = jdbc.frees, commits = jdbc.commits, commitNs = jdbc.commitNs;
        long allocated = THREADS.getCurrentThreadAllocatedBytes(), cpu = THREADS.getCurrentThreadCpuTime();
        long start = System.nanoTime();
        T result = work.get();
        return new Timed<>(result, new Sample(System.nanoTime() - start, THREADS.getCurrentThreadCpuTime() - cpu,
                THREADS.getCurrentThreadAllocatedBytes() - allocated, jdbc.selects - q, jdbc.dml - d, jdbc.chars - c,
                jdbc.binds - b, jdbc.locators - l, jdbc.streams - streams, jdbc.closes - closed, jdbc.frees - f, jdbc.commits - commits,
                jdbc.commitNs - commitNs));
    }

    private static void verifyRead(String mode, Sample sample, long passes, long chars, long bindings) {
        require(sample.selects == passes && sample.dml == 0 && sample.chars == chars && sample.binds == bindings
                && sample.locators == passes && sample.streams >= passes && sample.closes == sample.streams
                && sample.frees == passes && sample.commits == 1,
                mode + " unexpected read counters: " + sample);
    }

    private static void report(String mode, List<Sample> rows) {
        long[] ns = rows.stream().mapToLong(Sample::ns).sorted().toArray();
        long[] cpu = rows.stream().mapToLong(Sample::cpu).sorted().toArray();
        long[] alloc = rows.stream().mapToLong(Sample::alloc).sorted().toArray();
        long[] commit = rows.stream().mapToLong(Sample::commitNs).sorted().toArray();
        int median = (rows.size() - 1) / 2;
        int p95 = (int) Math.ceil(rows.size() * .95) - 1;
        Sample s = rows.getFirst();
        System.out.printf(Locale.ROOT, "%s N=%d p50_ms=%.3f p95_ms=%.3f p50_cpu_ms=%.3f p95_cpu_ms=%.3f "
                        + "p50_alloc_bytes=%d p95_alloc_bytes=%d p50_jdbc_commit_ms=%.3f p95_jdbc_commit_ms=%.3f "
                        + "selects=%d dml=%d chars=%d binds=%d locators=%d streams=%d closes=%d frees=%d commits=%d%n",
                mode, rows.size(), ns[median] / 1e6, ns[p95] / 1e6, cpu[median] / 1e6, cpu[p95] / 1e6,
                alloc[median], alloc[p95], commit[median] / 1e6, commit[p95] / 1e6, s.selects, s.dml, s.chars,
                s.binds, s.locators, s.streams, s.closes, s.frees, s.commits);
    }

    private static void seedPayloads(Connection db, List<String> values) throws Exception {
        try (var sql = db.createStatement()) {
            sql.execute("create cached table fixture_content(variant integer primary key,payload clob)");
        }
        try (var insert = db.prepareStatement("insert into fixture_content values(?,?)")) {
            for (int i = 0; i < values.size(); i++) {
                String value = values.get(i);
                insert.setInt(1, i);
                insert.setCharacterStream(2, new StringReader(value), value.length());
                require(insert.executeUpdate() == 1, "seed CLOB");
            }
        }
    }

    private static void rotate(Connection db, String previous, String next, int variant) throws Exception {
        try (var update = db.prepareStatement("update req_analysis_snapshot set id=?, "
                + "analysis_payload=(select payload from fixture_content where variant=?) where id=? and scope_key=?")) {
            update.setString(1, next);
            update.setInt(2, variant);
            update.setString(3, previous);
            update.setString(4, SCOPE);
            require(update.executeUpdate() == 1, "scoped fixture update");
        }
    }

    private static long storedLength(Connection db, String id) throws Exception {
        try (var select = db.prepareStatement("select character_length(analysis_payload) "
                + "from req_analysis_snapshot where id=? and scope_key=?")) {
            select.setString(1, id);
            select.setString(2, SCOPE);
            try (var row = select.executeQuery()) {
                require(row.next(), "stored CLOB row");
                return row.getLong(1);
            }
        }
    }

    private static String changedStatus(String input) {
        try (var parser = JsonMapper.builder().build().createParser(input)) {
            require(parser.nextToken() == JsonToken.START_OBJECT, "expected analysis object");
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                parser.nextToken();
                if ("status".equals(field)) {
                    int at = Math.toIntExact(parser.currentTokenLocation().getCharOffset());
                    require(input.startsWith("\"PARTIAL\"", at), "expected final PARTIAL fixture");
                    return input.substring(0, at + 1) + "SUCCESS" + input.substring(at + 8);
                }
                parser.skipChildren();
            }
        }
        throw new AssertionError("missing status");
    }

    private static String corruptRaw(String original) {
        int opening = original.indexOf('{');
        require(opening >= 0, "analysis JSON has an opening brace");
        return original.substring(0, opening) + "!" + original.substring(opening + 1);
    }

    private static String corruptFrame(String original) {
        int digest = original.indexOf(':', FRAME.length()) + 1;
        char different = original.charAt(digest) == 'a' ? 'b' : 'a';
        return original.substring(0, digest) + different + original.substring(digest + 1);
    }

    private static String newId() { return UUID.randomUUID().toString(); }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }

    private static final class JdbcCounters {
        long selects, dml, chars, binds, locators, streams, closes, frees, commits, commitNs;

        DataSource source(DataSource delegate) {
            return wrap(DataSource.class, delegate, (method, args, result) -> result instanceof Connection connection
                    ? connection(connection) : result);
        }

        private Connection connection(Connection delegate) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        boolean commit = method.getName().equals("commit");
                        long start = commit ? System.nanoTime() : 0;
                        Object result = invoke(delegate, method, args);
                        if (commit) {
                            commitNs += System.nanoTime() - start;
                            commits++;
                        }
                        if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement statement) {
                            return statement(statement, ((String) args[0]).stripLeading().toLowerCase(Locale.ROOT));
                        }
                        return result;
                    });
        }

        private PreparedStatement statement(PreparedStatement delegate, String sql) {
            boolean select = sql.startsWith("select");
            boolean mutation = sql.startsWith("insert") || sql.startsWith("update") || sql.startsWith("delete");
            return wrap(PreparedStatement.class, delegate, (method, args, result) -> {
                if (method.getName().startsWith("execute")) {
                    if (select) selects++;
                    if (mutation) dml++;
                }
                if (result instanceof ResultSet rows && sql.contains("analysis_payload")) {
                    return wrap(ResultSet.class, rows, (column, key, value) -> value instanceof Clob clob ? clob(clob) : value);
                }
                return result;
            });
        }

        private Clob clob(Clob delegate) {
            locators++;
            return wrap(Clob.class, delegate, (method, args, result) -> {
                if (method.getName().equals("free")) frees++;
                return method.getName().equals("getCharacterStream") && result instanceof Reader reader ? reader(reader) : result;
            });
        }

        private Reader reader(Reader delegate) {
            streams++;
            return new FilterReader(delegate) {
                @Override public int read() throws IOException {
                    int unit = super.read();
                    if (unit != -1) chars++;
                    return unit;
                }
                @Override public int read(char[] units, int start, int count) throws IOException {
                    int read = super.read(units, start, count);
                    if (read > 0) chars += read;
                    return read;
                }
                @Override public void close() throws IOException {
                    super.close();
                    closes++;
                }
            };
        }
    }

    private interface Around {
        Object after(Method method, Object[] args, Object result) throws Throwable;
    }

    private static <T> T wrap(Class<T> type, T delegate, Around around) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> around.after(method, args, invoke(delegate, method, args))));
    }

    private static Object invoke(Object delegate, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }
}
