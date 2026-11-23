package com.taxonomy.portfolio.service;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.ArchitectureRecommendation;
import com.taxonomy.dto.GapAnalysisView;
import com.taxonomy.dto.PatternDetectionView;
import com.taxonomy.exchange.backup.PortableJsonPayloads.PayloadReader;
import com.taxonomy.portfolio.payload.PortfolioAnalysisPayloads;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.type.StandardBasicTypes;
import org.springframework.stereotype.Repository;

import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Clob;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;

/** Reads complete immutable evidence while its caller's transaction still owns the CLOB locators. */
@Repository
public class PortfolioSnapshotEvidenceReader {
    private static final int MAX_CONTINUATION_ENTRIES = 16;
    private static final long MAX_CONTINUATION_BYTES = 2L * 1024 * 1024;
    private static final int FINGERPRINT_BUFFER_CHARACTERS = 8192;
    private static final String SELECT = """
            select s.analysis_payload as analysis_payload, s.gap_payload as gap_payload,
                   s.pattern_payload as pattern_payload, s.recommendation_payload as recommendation_payload
              from req_analysis_snapshot s
             where s.id = :id and s.project_id = :projectId and s.scope_key = :scopeKey
            """;
    private static final String SELECT_ANALYSIS = """
            select s.id as snapshot_id, s.analysis_payload as analysis_payload
              from req_analysis_snapshot s
             where s.id = :id and s.project_id = :projectId and s.scope_key = :scopeKey
               and s.job_id = :jobId and s.requirement_id = :requirementId
               and s.requirement_version_id = :requirementVersionId
            """;

    public record Evidence(AnalysisResult analysis, GapAnalysisView gapAnalysis,
                           PatternDetectionView patternDetection, ArchitectureRecommendation recommendation) { }
    /** The wrapper preserves an existing row whose recorded analysis payload is null. */
    public record AnalysisEvidence(AnalysisResult analysis) { }
    record ContinuationEvidence(ContinuationPolicyInputs inputs) { }

    private final EntityManager entityManager;
    private final PortfolioJsonCodec jsonCodec;
    private final LinkedHashMap<ContinuationKey, MemoEntry> validatedInputs = new LinkedHashMap<>(16, 0.75f, true);
    private long retainedContinuationBytes;

    public PortfolioSnapshotEvidenceReader(EntityManager entityManager, PortfolioJsonCodec jsonCodec) {
        this.entityManager = entityManager;
        this.jsonCodec = jsonCodec;
    }

    public Optional<Evidence> read(Long projectId, String snapshotId, String scopeKey) {
        Object[] row = entityManager.unwrap(Session.class).createNativeQuery(SELECT, Object[].class)
                .addScalar("analysis_payload", StandardBasicTypes.CLOB)
                .addScalar("gap_payload", StandardBasicTypes.CLOB)
                .addScalar("pattern_payload", StandardBasicTypes.CLOB)
                .addScalar("recommendation_payload", StandardBasicTypes.CLOB)
                .setParameter("id", snapshotId).setParameter("projectId", projectId).setParameter("scopeKey", scopeKey)
                .getSingleResultOrNull();
        if (row == null) return Optional.empty();
        Clob analysis = (Clob) row[0], gap = (Clob) row[1], pattern = (Clob) row[2], recommendation = (Clob) row[3];
        // Register every locator before opening any stream: even unopened payloads must be freed on failure.
        try (AutoCloseable analysisLocator = locator(analysis); AutoCloseable gapLocator = locator(gap);
             AutoCloseable patternLocator = locator(pattern); AutoCloseable recommendationLocator = locator(recommendation)) {
            return Optional.of(new Evidence(parse(analysis, this::parseAnalysis),
                    parse(gap, input -> jsonCodec.read(input, GapAnalysisView.class)),
                    parse(pattern, input -> jsonCodec.read(input, PatternDetectionView.class)),
                    parse(recommendation, input -> jsonCodec.read(input, ArchitectureRecommendation.class))));
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw PortfolioException.analysisFailed("Could not read portfolio snapshot evidence", exception);
        }
    }

    public Optional<AnalysisEvidence> readAnalysis(Long projectId, String snapshotId, String scopeKey,
                                                 String jobId, Long requirementId, Long requirementVersionId) {
        Object[] row = entityManager.unwrap(Session.class).createNativeQuery(SELECT_ANALYSIS, Object[].class)
                .addScalar("snapshot_id", StandardBasicTypes.STRING)
                .addScalar("analysis_payload", StandardBasicTypes.CLOB)
                .setParameter("id", snapshotId).setParameter("projectId", projectId).setParameter("scopeKey", scopeKey)
                .setParameter("jobId", jobId).setParameter("requirementId", requirementId)
                .setParameter("requirementVersionId", requirementVersionId).getSingleResultOrNull();
        if (row == null) return Optional.empty();
        Clob clob = (Clob) row[1];
        try (AutoCloseable locator = locator(clob)) {
            return Optional.of(new AnalysisEvidence(parse(clob, this::parseAnalysis)));
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw PortfolioException.analysisFailed("Could not read portfolio snapshot evidence", exception);
        }
    }

    Optional<ContinuationEvidence> readContinuation(Long projectId, String snapshotId, String scopeKey,
                                                    String jobId, Long requirementId, Long requirementVersionId) {
        Session session = entityManager.unwrap(Session.class);
        var key = new ContinuationKey(session.getSessionFactory(), projectId, snapshotId, scopeKey, jobId,
                requirementId, requirementVersionId);
        MemoEntry cached;
        synchronized (validatedInputs) {
            cached = validatedInputs.get(key);
        }
        Object[] row = selectContinuation(session, key);
        if (row == null) return Optional.empty();
        Clob clob = (Clob) row[1];
        if (clob == null) return Optional.of(new ContinuationEvidence(null));
        try {
            if (cached != null) {
                var comparison = readContinuationPass(clob, false);
                if (cached.validated().fingerprint().matches(comparison.fingerprint())) {
                    return Optional.of(new ContinuationEvidence(cached.validated().inputs()));
                }
                // Read a fresh scoped locator after releasing the comparison locator. Only the
                // full validation pass can associate its fingerprint with newly bound inputs.
                row = selectContinuation(session, key);
                if (row == null) return Optional.empty();
                clob = (Clob) row[1];
                if (clob == null) return Optional.of(new ContinuationEvidence(null));
            }
            var validated = readContinuationPass(clob, true);
            remember(key, validated);
            return Optional.of(new ContinuationEvidence(validated.inputs()));
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw PortfolioException.analysisFailed("Could not read portfolio snapshot evidence", exception);
        }
    }

    private static Object[] selectContinuation(Session session, ContinuationKey key) {
        return session.createNativeQuery(SELECT_ANALYSIS, Object[].class)
                .addScalar("snapshot_id", StandardBasicTypes.STRING)
                .addScalar("analysis_payload", StandardBasicTypes.CLOB)
                .setParameter("id", key.snapshotId()).setParameter("projectId", key.projectId())
                .setParameter("scopeKey", key.scopeKey()).setParameter("jobId", key.jobId())
                .setParameter("requirementId", key.requirementId())
                .setParameter("requirementVersionId", key.requirementVersionId()).getSingleResultOrNull();
    }

    private ValidatedPass readContinuationPass(Clob clob, boolean bind) throws Exception {
        try (AutoCloseable locator = locator(clob);
             var input = new FingerprintingReader(sha256(), JdbcClobReaders.open(clob))) {
            // The memo compares the stored characters. Only a validation pass decodes for binding.
            AnalysisResult analysis = bind ? parseAnalysis(input) : null;
            return new ValidatedPass(input.finish(), ContinuationPolicyInputs.from(analysis));
        }
    }

    private void remember(ContinuationKey key, ValidatedPass validated) {
        long bytes = key.retainedBytes() + (validated.inputs() == null ? 0 : validated.inputs().retainedBytes());
        synchronized (validatedInputs) {
            var previous = validatedInputs.remove(key);
            if (previous != null) retainedContinuationBytes -= previous.retainedBytes();
            if (bytes > MAX_CONTINUATION_BYTES) return;
            while (validatedInputs.size() >= MAX_CONTINUATION_ENTRIES
                    || retainedContinuationBytes + bytes > MAX_CONTINUATION_BYTES) {
                retainedContinuationBytes -= validatedInputs.pollFirstEntry().getValue().retainedBytes();
            }
            validatedInputs.put(key, new MemoEntry(validated, bytes));
            retainedContinuationBytes += bytes;
        }
    }

    private record ContinuationKey(SessionFactory factory, Long projectId, String snapshotId, String scopeKey,
                                   String jobId, Long requirementId, Long requirementVersionId) {
        @Override public boolean equals(Object value) {
            return value instanceof ContinuationKey other && factory == other.factory
                    && Objects.equals(projectId, other.projectId) && Objects.equals(snapshotId, other.snapshotId)
                    && Objects.equals(scopeKey, other.scopeKey) && Objects.equals(jobId, other.jobId)
                    && Objects.equals(requirementId, other.requirementId)
                    && Objects.equals(requirementVersionId, other.requirementVersionId);
        }

        @Override public int hashCode() {
            return Objects.hash(System.identityHashCode(factory), projectId, snapshotId, scopeKey, jobId,
                    requirementId, requirementVersionId);
        }

        long retainedBytes() {
            // Includes map entry, key, boxed IDs, memo records and digest storage.
            return 512L + ContinuationPolicyInputs.stringBytes(snapshotId)
                    + ContinuationPolicyInputs.stringBytes(scopeKey) + ContinuationPolicyInputs.stringBytes(jobId);
        }
    }

    private record MemoEntry(ValidatedPass validated, long retainedBytes) { }
    private record ValidatedPass(Fingerprint fingerprint, ContinuationPolicyInputs inputs) { }
    private record Fingerprint(long characters, byte[] digest) {
        boolean matches(Fingerprint other) {
            return characters == other.characters && MessageDigest.isEqual(digest, other.digest);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    /** Hashes raw UTF-16 code units, including unpaired surrogates, as the parser reads them. */
    private static final class FingerprintingReader extends FilterReader {
        private final MessageDigest digest;
        private final byte[] bytes = new byte[2 * FINGERPRINT_BUFFER_CHARACTERS];
        private final CharBuffer codeUnits = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asCharBuffer();
        private long characters;
        private boolean exhausted;

        FingerprintingReader(MessageDigest digest, Reader input) {
            super(input);
            this.digest = digest;
        }

        @Override public int read() throws IOException {
            if (exhausted) return -1;
            int unit = super.read();
            if (unit < 0) exhausted = true;
            else {
                digest.update((byte) unit);
                digest.update((byte) (unit >>> 8));
                characters++;
            }
            return unit;
        }

        @Override public int read(char[] buffer, int offset, int count) throws IOException {
            if (count == 0) return 0;
            if (exhausted) return -1;
            int length = super.read(buffer, offset, count);
            if (length < 0) exhausted = true;
            else {
                for (int copied = 0; copied < length;) {
                    int batch = Math.min(length - copied, codeUnits.capacity());
                    codeUnits.clear().put(buffer, offset + copied, batch);
                    digest.update(bytes, 0, 2 * batch);
                    copied += batch;
                }
                characters += length;
            }
            return length;
        }

        Fingerprint finish() throws IOException {
            if (!exhausted) {
                char[] tail = new char[FINGERPRINT_BUFFER_CHARACTERS];
                while (read(tail) != -1) { }
            }
            return new Fingerprint(characters, digest.digest());
        }
    }

    private AnalysisResult parseAnalysis(Reader input) throws IOException {
        return PortfolioAnalysisPayloads.read(input, json -> jsonCodec.read(json, AnalysisResult.class));
    }

    private static <T> T parse(Clob clob, PayloadReader<T> parser) throws Exception {
        try (Reader input = clob == null ? null : JdbcClobReaders.open(clob)) {
            return parser.read(input);
        }
    }

    private static AutoCloseable locator(Clob clob) {
        return () -> { if (clob != null) clob.free(); };
    }
}
