package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.dto.SnapshotSelection;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.type.StandardBasicTypes;
import org.springframework.stereotype.Repository;

import java.io.Reader;
import java.sql.Clob;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** Completes a scoped selection read while its caller's read-only transaction still owns the CLOB. */
@Repository
public class PortfolioSnapshotSelectionReader {
    // Bounds only sharing bookkeeping. Further distinct reads retain the ordinary read path.
    private static final int MAX_INFLIGHT_PARSES = 32;
    private static final String SELECT = """
            select s.id as snapshot_id, s.status as snapshot_status, s.duration_ms as duration_ms,
                   s.analysis_payload as analysis_payload,
                   (select count(e.id) from req_element_mapping e
                     where e.snapshot_id = s.id and e.scope_key = s.scope_key) as element_count,
                   (select count(r.id) from req_relation_mapping r
                     where r.snapshot_id = s.id and r.scope_key = s.scope_key) as relation_count
              from req_analysis_snapshot s
             where s.id = :id and s.project_id = :projectId and s.scope_key = :scopeKey
            """;

    private final EntityManager entityManager;
    private final PortfolioJsonCodec jsonCodec;
    private final Map<ParseKey, CompletableFuture<ParsedEvidence>> inFlight = new HashMap<>();

    public PortfolioSnapshotSelectionReader(EntityManager entityManager, PortfolioJsonCodec jsonCodec) {
        this.entityManager = entityManager;
        this.jsonCodec = jsonCodec;
    }

    public Optional<SnapshotSelection> read(Long projectId, String snapshotId, String scopeKey) {
        Session session = entityManager.unwrap(Session.class);
        SessionFactory factory = session.getSessionFactory();
        Object[] row = session.createNativeQuery(SELECT, Object[].class)
                .addScalar("snapshot_id", StandardBasicTypes.STRING)
                .addScalar("snapshot_status", StandardBasicTypes.STRING)
                .addScalar("duration_ms", StandardBasicTypes.LONG)
                .addScalar("analysis_payload", StandardBasicTypes.CLOB)
                .addScalar("element_count", StandardBasicTypes.LONG)
                .addScalar("relation_count", StandardBasicTypes.LONG)
                .setParameter("id", snapshotId)
                .setParameter("projectId", projectId)
                .setParameter("scopeKey", scopeKey)
                .getSingleResultOrNull();
        if (row == null) return Optional.empty();
        Clob clob = (Clob) row[3];
        try {
            var evidence = evidence(new ParseKey(factory, projectId, (String) row[0], scopeKey), clob);
            return Optional.of(new SnapshotSelection((String) row[0], AnalysisStatus.valueOf((String) row[1]),
                    (Long) row[2], evidence.scores(), evidence.warningCount(), (Long) row[4], (Long) row[5]));
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw PortfolioException.analysisFailed("Could not read portfolio selection evidence", exception);
        }
    }

    private ParsedEvidence evidence(ParseKey key, Clob clob) throws Exception {
        CompletableFuture<ParsedEvidence> flight;
        boolean owner = false;
        synchronized (inFlight) {
            flight = inFlight.get(key);
            if (flight == null && inFlight.size() < MAX_INFLIGHT_PARSES) {
                flight = new CompletableFuture<>();
                inFlight.put(key, flight);
                owner = true;
            }
        }
        if (flight == null) return parseAndClose(clob);
        if (!owner) return awaitAndClose(flight, clob);

        ParsedEvidence parsed;
        try {
            parsed = parseAndClose(clob);
        } catch (Exception | Error failure) {
            remove(key, flight);
            flight.completeExceptionally(failure);
            throw failure;
        }
        // Remove before publishing: later reads cannot reuse a completed parse or failure.
        remove(key, flight);
        flight.complete(parsed);
        return parsed;
    }

    private void remove(ParseKey key, CompletableFuture<ParsedEvidence> flight) {
        synchronized (inFlight) {
            inFlight.remove(key, flight);
        }
    }

    private ParsedEvidence parseAndClose(Clob clob) throws Exception {
        // Register the locator first so it is freed even if opening the stream fails.
        try (AutoCloseable locator = () -> { if (clob != null) clob.free(); };
             Reader input = clob == null ? null : JdbcClobReaders.open(clob)) {
            var analysis = jsonCodec.readSelectionAnalysis(input);
            return new ParsedEvidence(analysis == null ? Map.of() : analysis.getScores(),
                    analysis == null || analysis.getWarnings() == null ? 0 : analysis.getWarnings().size());
        }
    }

    private ParsedEvidence awaitAndClose(CompletableFuture<ParsedEvidence> flight, Clob clob) throws Exception {
        // The subscriber owns its locator, but never opens an unused reader or cancels the owner.
        try (AutoCloseable locator = () -> { if (clob != null) clob.free(); }) {
            try {
                return flight.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw PortfolioException.analysisFailed("Interrupted while reading portfolio selection evidence", interrupted);
            } catch (CancellationException cancelled) {
                // Future.get throws an owner's cancellation directly, without ExecutionException.
                throw PortfolioException.analysisFailed("Could not read portfolio selection evidence", cancelled);
            } catch (ExecutionException failed) {
                // Each caller needs its own exception before its own cleanup adds suppressed failures.
                Throwable cause = failed.getCause();
                if (cause instanceof PortfolioException failure) {
                    throw new PortfolioException(failure.getKind(), failure.getCode(), failure.getMessage(), failure);
                }
                throw PortfolioException.analysisFailed("Could not read portfolio selection evidence", cause);
            }
        }
    }

    private record ParsedEvidence(Map<String, Double> scores, int warningCount) {
        private ParsedEvidence {
            scores = scores == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(scores));
        }
    }

    private record ParseKey(SessionFactory factory, Long projectId, String snapshotId, String scopeKey) {
        @Override public boolean equals(Object value) {
            return value instanceof ParseKey other && factory == other.factory
                    && Objects.equals(projectId, other.projectId) && Objects.equals(snapshotId, other.snapshotId)
                    && Objects.equals(scopeKey, other.scopeKey);
        }
        @Override public int hashCode() {
            return Objects.hash(System.identityHashCode(factory), projectId, snapshotId, scopeKey);
        }
    }
}
