package com.taxonomy.portfolio.reformulation;

import com.taxonomy.portfolio.service.JdbcClobReaders;
import com.taxonomy.portfolio.service.PortfolioException;
import jakarta.persistence.EntityManager;
import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.io.Reader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Reads the exact proposal baseline without hydrating its analysis snapshot or other associations. */
@Repository
public class ReformulationAncestryRepository {
    record ProposalBaseline<T>(Long projectId, Long requirementId, Long sourceVersionId,
                              String snapshotId, T baseline) { }

    private final EntityManager entities;

    public ReformulationAncestryRepository(EntityManager entities) {
        this.entities = entities;
    }

    @Transactional(readOnly = true)
    public <T> Optional<ProposalBaseline<T>> read(String proposalId, String scopeKey, Long requirementId,
                                                  Long previewSourceVersionId, Function<Reader, T> decode) {
        if (requirementId == null) return Optional.empty();
        Session session = entities.unwrap(Session.class);
        // JDBC work does not auto-flush. A joined writer must see its own pending proposal insert.
        if (session.isJoinedToTransaction() && session.getHibernateFlushMode() != FlushMode.MANUAL && session.isDirty()) {
            session.flush();
        }
        return session.doReturningWork(connection -> {
            try (var statement = connection.prepareStatement("""
                    select project_id, requirement_id, source_version_id, snapshot_id, baseline_payload
                    from reformulation_proposal
                    where id = ? and scope_key = ? and requirement_id = ?
                    """)) {
                statement.setString(1, proposalId);
                statement.setString(2, scopeKey);
                statement.setLong(3, requirementId);
                try (var row = statement.executeQuery()) {
                    if (!row.next()) return Optional.empty();
                    var sourceVersionId = row.getObject(3, Long.class);
                    if (!Objects.equals(sourceVersionId, previewSourceVersionId)) {
                        throw PortfolioException.conflict("Adopted source baseline does not match preview");
                    }
                    return Optional.of(new ProposalBaseline<>(row.getObject(1, Long.class),
                            row.getObject(2, Long.class), sourceVersionId, row.getString(4), decodeBaseline(row, decode)));
                }
            }
        });
    }

    private static <T> T decodeBaseline(ResultSet row, Function<Reader, T> decode) throws SQLException {
        int type = row.getMetaData().getColumnType(5);
        // PostgreSQL stores this column as TEXT, for which getClob would try to read a large-object OID.
        if (type != Types.CLOB && type != Types.NCLOB) {
            try (Reader input = row.getCharacterStream(5)) {
                return decode.apply(input);
            } catch (java.io.IOException failure) {
                throw new SQLException("Could not close reformulation baseline reader", failure);
            }
        }
        var clob = type == Types.NCLOB ? row.getNClob(5) : row.getClob(5);
        if (clob == null) return decode.apply(null);
        try (AutoCloseable locator = clob::free; Reader input = JdbcClobReaders.open(clob)) {
            return decode.apply(input);
        } catch (SQLException | RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SQLException("Could not close reformulation baseline reader", failure);
        }
    }
}
