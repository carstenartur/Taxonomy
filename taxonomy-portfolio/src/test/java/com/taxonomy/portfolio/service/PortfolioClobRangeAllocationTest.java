package com.taxonomy.portfolio.service;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.ArchitectureRecommendation;
import com.taxonomy.dto.GapAnalysisView;
import com.taxonomy.dto.PatternDetectionView;
import com.taxonomy.dto.TaxonomyNodeDto;
import org.hsqldb.SessionInterface;
import org.hsqldb.jdbc.JDBCClobClient;
import org.hsqldb.jdbc.JDBCConnection;
import org.hsqldb.types.ClobDataID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.jdbc.proxy.WrappedClob;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Real compressed HSQL CLOBs: parser parity and driver arrays, not a mocked stream or JVM heap change. */
class PortfolioClobRangeAllocationTest {
    private static final int MAX_DRIVER_CHARACTERS = 262_144;

    enum ReadPath { COMPLETE_EVIDENCE, CONTINUATION_ANALYSIS, SELECTION }
    record Window(int requested, int returned) { }

    @ParameterizedTest @EnumSource(ReadPath.class)
    void completeScopedReadsAvoidTheDriversDefaultOneMiBOutputArrays(ReadPath path) throws Exception {
        try (var f = new Database()) {
            // Observe the actual getChars request and returned driver array, not a sampled event
            // that depends on Surefire's heap, TLAB size, or G1 region size.
            f.readDefaultStream();
            assertThat(f.windows).as("Positive control: the actual default HSQL stream allocates 524288-char output")
                    .anySatisfy(w -> {
                        assertThat(w.requested()).isEqualTo(524_288);
                        assertThat(w.returned()).isEqualTo(524_288);
                    });
            f.windows.clear();
            var value = f.read(path);
            f.assertCompleteParity(path, value);
            System.out.println("Actual HSQL " + path + ": windows=" + f.windows.size()
                    + ", maxReturnedCharacters=" + f.windows.stream().mapToInt(Window::returned).max().orElse(0)
                    + ", charArraysAtLeastOneMiB=" + f.windows.stream().filter(w -> 16L + w.returned() * 2L >= 1_048_576).count());
            assertThat(f.windows).as("Every production path must actually read the real compressed CLOB").isNotEmpty();
            assertThat(f.windows).as("%s must not request or allocate the driver's one-MiB output arrays", path)
                    .allSatisfy(w -> {
                        assertThat(w.requested()).isLessThanOrEqualTo(MAX_DRIVER_CHARACTERS);
                        assertThat(w.returned()).isLessThanOrEqualTo(MAX_DRIVER_CHARACTERS);
                    });
        }
    }

    /** Minimal physical projection schema: the production readers execute their unmodified SQL. */
    private static final class Database implements AutoCloseable {
        final SessionFactory factory;
        final PortfolioJsonCodec codec = new PortfolioJsonCodec(JsonMapper.builder().build());
        final JsonMapper json = JsonMapper.builder().build();
        final AnalysisResult analysis;
        final GapAnalysisView gaps = new GapAnalysisView();
        final PatternDetectionView patterns = new PatternDetectionView();
        final ArchitectureRecommendation recommendation = new ArchitectureRecommendation();
        final List<Window> windows = new ArrayList<>();

        Database() {
            // Includes non-ASCII, paired and unpaired surrogates; their code units must survive
            // arbitrary JDBC range boundaries unchanged, rather than undergo charset encoding.
            String evidence = "Original published evidence 水位 🌊 \uD800 X \uDC00\n".repeat(60_000);
            var node = new TaxonomyNodeDto(); node.setCode("BP-1000"); node.setNameEn("Historical root");
            node.setDescriptionEn(evidence); node.setUuid("historical-uuid");
            analysis = new AnalysisResult(Map.of("BP-1000", 12.5), List.of(node));
            analysis.setRawScores(Map.of("BP-1000", .125)); analysis.setReasons(Map.of("BP-1000", evidence));
            analysis.setWarnings(List.of("Recorded warning", "Second retained warning")); analysis.setStatus("PARTIAL");
            analysis.setProvider("Historical provider"); analysis.setAnalysisDurationMillis(123L);
            gaps.setBusinessText("Original requirement"); gaps.setNotes(List.of(evidence));
            patterns.setNotes(List.of(evidence)); recommendation.setReasoning(List.of(evidence));
            factory = new Configuration()
                    .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                    .setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:bounded-range-" + UUID.randomUUID()
                            + ";hsqldb.lob_compressed=true;hsqldb.lob_file_scale=1")
                    .setProperty("hibernate.search.enabled", "false").buildSessionFactory();
            try (var session = factory.openSession()) {
                session.doWork(connection -> {
                    try (var statement = connection.createStatement()) {
                        statement.execute("""
                                create table req_analysis_snapshot (
                                  id varchar(64), project_id bigint, scope_key varchar(64), job_id varchar(64),
                                  requirement_id bigint, requirement_version_id bigint, status varchar(32), duration_ms bigint,
                                  analysis_payload clob, gap_payload clob, pattern_payload clob, recommendation_payload clob)
                                """);
                        statement.execute("create table req_element_mapping(id bigint, snapshot_id varchar(64), scope_key varchar(64))");
                        statement.execute("create table req_relation_mapping(id bigint, snapshot_id varchar(64), scope_key varchar(64))");
                        statement.execute("insert into req_element_mapping values(1,'snapshot','scope')");
                        statement.execute("insert into req_relation_mapping values(1,'snapshot','scope')");
                    }
                    try (var insert = connection.prepareStatement("insert into req_analysis_snapshot values(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                        insert.setString(1, "snapshot"); insert.setLong(2, 7); insert.setString(3, "scope");
                        insert.setString(4, "job"); insert.setLong(5, 11); insert.setLong(6, 12);
                        insert.setString(7, "PARTIAL"); insert.setLong(8, 123);
                        Object[] payloads = {analysis, gaps, patterns, recommendation};
                        for (int p = 0; p < payloads.length; p++) {
                            String source = codec.write(payloads[p]);
                            insert.setCharacterStream(9 + p, new StringReader(source), source.length());
                        }
                        insert.executeUpdate();
                    }
                    connection.commit();
                });
            }
        }

        void readDefaultStream() {
            try (var session = factory.openSession()) {
                session.doWork(connection -> {
                    try (var query = connection.prepareStatement("select analysis_payload from req_analysis_snapshot where id='snapshot'");
                         var rows = query.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        Clob clob = observe(rows.getClob(1), ((JDBCConnection) connection).getSession());
                        try (Reader input = clob.getCharacterStream()) { input.transferTo(Writer.nullWriter()); }
                        catch (java.io.IOException failure) { throw new java.sql.SQLException(failure); }
                        finally { clob.free(); }
                    }
                });
            }
        }

        Object read(ReadPath path) {
            try (var session = factory.openSession()) {
                Session observed = observeQueries(session);
                var transaction = session.beginTransaction();
                try {
                    Object value = switch (path) {
                        case COMPLETE_EVIDENCE -> new PortfolioSnapshotEvidenceReader(observed, codec)
                                .read(7L, "snapshot", "scope").orElseThrow();
                        case CONTINUATION_ANALYSIS -> new PortfolioSnapshotEvidenceReader(observed, codec)
                                .readAnalysis(7L, "snapshot", "scope", "job", 11L, 12L).orElseThrow().analysis();
                        case SELECTION -> new PortfolioSnapshotSelectionReader(observed, codec)
                                .read(7L, "snapshot", "scope").orElseThrow();
                    };
                    transaction.commit(); return value;
                } catch (RuntimeException | Error failure) { transaction.rollback(); throw failure; }
            }
        }

        /** Only observes the returned real locator; SQL, binding, database reads and parsing stay real. */
        Session observeQueries(Session real) {
            SessionInterface jdbc = real.doReturningWork(connection -> ((JDBCConnection) connection).getSession());
            return (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[]{Session.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("unwrap") && args[0] == Session.class) return proxy;
                        Object result = invoke(method, real, args);
                        if (!(result instanceof NativeQuery<?> query)) return result;
                        return Proxy.newProxyInstance(NativeQuery.class.getClassLoader(), new Class<?>[]{NativeQuery.class},
                                (queryProxy, queryMethod, queryArgs) -> {
                                    Object answer = invoke(queryMethod, query, queryArgs);
                                    if (answer == query) return queryProxy;
                                    if (!queryMethod.getName().equals("getSingleResultOrNull") || answer == null) return answer;
                                    Object[] row = ((Object[]) answer).clone();
                                    for (int i = 0; i < row.length; i++) if (row[i] instanceof Clob clob) row[i] = observe(clob, jdbc);
                                    return row;
                                });
                    });
        }

        Clob observe(Clob acquired, SessionInterface session) throws SQLException {
            Clob underlying = acquired;
            while (underlying instanceof WrappedClob wrapped) underlying = wrapped.getWrappedClob();
            assertThat(underlying).isInstanceOf(JDBCClobClient.class);
            var original = ((JDBCClobClient) underlying).getClob();
            var observedId = new ClobDataID(original.getId()) {
                @Override public char[] getChars(SessionInterface owner, long position, int length) {
                    char[] actual = super.getChars(owner, position, length);
                    windows.add(new Window(length, actual.length));
                    return actual;
                }
            };
            var observed = new JDBCClobClient(session, observedId);
            return (Clob) Proxy.newProxyInstance(Clob.class.getClassLoader(), new Class<?>[]{Clob.class},
                    (proxy, method, args) -> {
                        if (!method.getName().equals("free")) return invoke(method, observed, args);
                        try { return invoke(method, observed, args); }
                        finally { acquired.free(); }
                    });
        }

        static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException failed) { throw failed.getCause(); }
        }

        void assertCompleteParity(ReadPath path, Object actual) {
            switch (path) {
                case COMPLETE_EVIDENCE -> {
                    var value = (PortfolioSnapshotEvidenceReader.Evidence) actual;
                    assertJsonParity(analysis, value.analysis()); assertJsonParity(gaps, value.gapAnalysis());
                    assertJsonParity(patterns, value.patternDetection()); assertJsonParity(recommendation, value.recommendation());
                }
                case CONTINUATION_ANALYSIS -> assertJsonParity(analysis, actual);
                case SELECTION -> {
                    var value = (com.taxonomy.portfolio.dto.SnapshotSelection) actual;
                    assertThat(value.scores()).containsExactlyEntriesOf(analysis.getScores());
                    assertThat(value.warningCount()).isEqualTo(analysis.getWarnings().size());
                    assertThat(value.id()).isEqualTo("snapshot"); assertThat(value.durationMs()).isEqualTo(123);
                    assertThat(value.elementMappingCount()).isEqualTo(1); assertThat(value.relationMappingCount()).isEqualTo(1);
                }
            }
        }

        void assertJsonParity(Object expected, Object actual) {
            tools.jackson.databind.JsonNode original = json.valueToTree(expected), restored = json.valueToTree(actual);
            assertThat(restored).isEqualTo(original);
        }

        @Override public void close() {
            try (var session = factory.openSession()) {
                session.doWork(connection -> { try (var statement = connection.createStatement()) { statement.execute("shutdown"); } });
            } finally { factory.close(); }
        }
    }
}
