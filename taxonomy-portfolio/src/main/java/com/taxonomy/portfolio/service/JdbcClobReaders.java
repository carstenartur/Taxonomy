package com.taxonomy.portfolio.service;

import java.io.IOException;
import java.io.Reader;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;

/** Complete JDBC text with bounded driver windows; the caller still owns and frees the locator. */
final class JdbcClobReaders {
    // Aligns HSQL's compressed 512-KiB parts without its default one-MiB output arrays.
    private static final int RANGE_CHARACTERS = 262_144;
    private JdbcClobReaders() { }

    static Reader open(Clob clob) throws SQLException {
        long length = clob.length();
        if (length < 0) throw new SQLException("Negative JDBC CLOB length");
        if (length <= RANGE_CHARACTERS) return clob.getCharacterStream();
        Reader first;
        try { first = clob.getCharacterStream(1, RANGE_CHARACTERS); }
        catch (SQLFeatureNotSupportedException unsupported) {
            // Some drivers implement only the ordinary stream. No text has been consumed yet.
            return clob.getCharacterStream();
        }
        if (first == null) throw new SQLException("JDBC CLOB range returned no stream");
        return new Ranges(clob, length, first);
    }

    private static final class Ranges extends Reader {
        private final Clob clob;
        private final long length;
        private final char[] single = new char[1];
        private Reader current;
        private long consumed;
        private int remaining = RANGE_CHARACTERS;
        private boolean closed;
        private Throwable failure;

        Ranges(Clob clob, long length, Reader first) {
            this.clob = clob; this.length = length; current = first;
        }

        @Override public int read() throws IOException {
            return read(single, 0, 1) < 0 ? -1 : single[0];
        }

        @Override public int read(char[] buffer, int offset, int count) throws IOException {
            Objects.checkFromIndexSize(offset, count, buffer.length);
            checkOpen();
            if (count == 0) return 0;
            if (consumed == length) return -1;
            try {
                if (current == null) {
                    remaining = (int) Math.min(RANGE_CHARACTERS, length - consumed);
                    try { current = clob.getCharacterStream(consumed + 1, remaining); }
                    catch (SQLException exception) { throw new IOException("Could not open JDBC CLOB range", exception); }
                    if (current == null) throw new IOException("JDBC CLOB range returned no stream");
                }
                int requested = Math.min(count, remaining);
                int actual = current.read(buffer, offset, requested);
                if (actual <= 0 || actual > requested) throw new IOException("Incomplete JDBC CLOB range");
                consumed += actual; remaining -= actual;
                if (remaining == 0) {
                    // Validate and close before publishing even the final chars of a complete JSON
                    // root: a parser may otherwise close without observing an EOF/close failure.
                    if (current.read() != -1) throw new IOException("JDBC CLOB range exceeds its declared length");
                    Reader finished = current; current = null; finished.close();
                }
                return actual;
            } catch (IOException | RuntimeException | Error exception) {
                remember(exception); throw exception;
            }
        }

        private void checkOpen() throws IOException {
            if (failure instanceof IOException exception) throw exception;
            if (failure instanceof RuntimeException exception) throw exception;
            if (failure instanceof Error exception) throw exception;
            if (closed) throw new IOException("JDBC CLOB reader is closed");
        }

        private void remember(Throwable exception) {
            failure = exception;
            Reader failed = current; current = null;
            if (failed != null) {
                try { failed.close(); }
                catch (Exception | Error cleanup) { if (cleanup != exception) exception.addSuppressed(cleanup); }
            }
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            Reader closing = current; current = null;
            // Never rethrow a latched read failure from close (including the same exception).
            if (closing != null) closing.close();
        }
    }
}
