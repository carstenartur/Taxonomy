package com.taxonomy.portfolio.service;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.ArchitectureRecommendation;
import com.taxonomy.dto.GapAnalysisView;
import com.taxonomy.dto.PatternDetectionView;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.type.StandardBasicTypes;
import org.springframework.stereotype.Repository;

import java.io.Reader;
import java.sql.Clob;
import java.util.Optional;

/** Reads complete immutable evidence while its caller's transaction still owns the CLOB locators. */
@Repository
public class PortfolioSnapshotEvidenceReader {
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

    private final EntityManager entityManager;
    private final PortfolioJsonCodec jsonCodec;

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
            return Optional.of(new Evidence(parse(analysis, AnalysisResult.class), parse(gap, GapAnalysisView.class),
                    parse(pattern, PatternDetectionView.class), parse(recommendation, ArchitectureRecommendation.class)));
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
            return Optional.of(new AnalysisEvidence(parse(clob, AnalysisResult.class)));
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw PortfolioException.analysisFailed("Could not read portfolio snapshot evidence", exception);
        }
    }

    private <T> T parse(Clob clob, Class<T> type) throws Exception {
        try (Reader input = clob == null ? null : JdbcClobReaders.open(clob)) {
            return jsonCodec.read(input, type);
        }
    }

    private static AutoCloseable locator(Clob clob) {
        return () -> { if (clob != null) clob.free(); };
    }
}
