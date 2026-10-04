package com.taxonomy.analysis.dispatch;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Savepoint;
import java.util.Optional;

/**
 * Insert on the caller's connection, containing only a duplicate-key failure.
 *
 * <p>Do not catch a failed JPA flush: Hibernate's persistence context and the
 * enclosing transaction would already be unusable. Flush caller-owned state
 * BEFORE the savepoint and perform only JDBC work after it. No entity is added
 * to the persistence context by the tentative insert. The caller must read and
 * validate the winning row when a duplicate is returned.</p>
 */
final class AnalysisInsertIfAbsent {
    @FunctionalInterface
    interface Parameters {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private AnalysisInsertIfAbsent() { }

    static Optional<SQLException> insert(EntityManager em, String sql, Parameters parameters) {
        em.flush();
        return em.unwrap(Session.class).doReturningWork(connection -> {
            if (connection.getAutoCommit()) throw new SQLException("An analysis insert requires a transaction");
            Savepoint savepoint = connection.setSavepoint();
            try {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    parameters.bind(statement);
                    if (statement.executeUpdate() != 1) throw new SQLException("Analysis insert affected no row");
                }
                release(connection, savepoint);
                return Optional.empty();
            } catch (SQLException failure) {
                try {
                    connection.rollback(savepoint);
                } catch (SQLException rollbackFailure) {
                    rollbackFailure.addSuppressed(failure);
                    throw rollbackFailure;
                }
                // HSQLDB invalidates the target on rollback; other databases retain
                // it until transaction completion. Do not release an invalidated point.
                if (!duplicateKey(failure)) throw failure;
                return Optional.of(failure);
            }
        });
    }

    /** Only unique-key violations, not arbitrary integrity/connection failures. */
    static boolean duplicateKey(SQLException failure) {
        String state = failure.getSQLState();
        int code = failure.getErrorCode();
        return "23505".equals(state) // PostgreSQL / HSQLDB
                || ("23000".equals(state)
                    && (code == 1 || code == 2601 || code == 2627)); // Oracle / SQL Server
    }

    private static void release(Connection connection, Savepoint savepoint) throws SQLException {
        // SQL Server supports SAVE/ROLLBACK TRANSACTION but not RELEASE SAVEPOINT.
        // Its savepoints are released by the enclosing transaction's completion.
        if ("Microsoft SQL Server".equals(connection.getMetaData().getDatabaseProductName())) return;
        try {
            connection.releaseSavepoint(savepoint);
        } catch (SQLFeatureNotSupportedException unsupported) {
            // Releasing early is optional; transaction completion still releases it.
        }
    }
}
