package com.taxonomy.portfolio.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.sql.Clob;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.*;

class JdbcClobReadersTest {
    private static final int RANGE = 262_144;

    @Test void skipKeepsLongJdbcPositionsPastTwoGiBWithoutMaterializingItsContents() throws Exception {
        long skipped = (long) Integer.MAX_VALUE + 131_072 + 17;
        var clob = new SyntheticClob(skipped + 23);
        try (var reader = JdbcClobReaders.open(clob)) {
            assertThat(reader.skip(skipped)).isEqualTo(skipped);
            assertThat(clob.lastPosition).isGreaterThan(Integer.MAX_VALUE)
                    .isEqualTo(skipped / RANGE * RANGE + 1);
            assertThat(reader.read(new char[32])).isEqualTo(23);
            assertThat(reader.read()).isEqualTo(-1);
            assertThat(reader.read(new char[3], 1, 0)).isZero();
        }
        assertThat(clob.readCharacters).isEqualTo(clob.length);
        assertThat(clob.maximumWindow).isEqualTo(RANGE);
        assertThat(clob.lengthCalls).isEqualTo(1);
        assertThat(clob.closedRanges).isEqualTo(clob.openedRanges);
        assertThat(clob.freed).isZero();
    }

    @Test void theLargestJdbcLengthDoesNotBecomeAnIntLimitOrOverflowTheFirstRange() throws Exception {
        var clob = new SyntheticClob(Long.MAX_VALUE);
        var reader = JdbcClobReaders.open(clob);
        assertThat(reader.read(new char[17])).isEqualTo(17);
        assertThat(clob.lastPosition).isEqualTo(1);
        assertThat(clob.maximumWindow).isEqualTo(RANGE);
        reader.close(); reader.close();
        assertThat(clob.closedRanges).isEqualTo(1);
        assertThat(clob.freed).isZero();
        assertThatThrownBy(reader::read).isInstanceOf(IOException.class);
    }

    @Test void aConsumerCannotRetryPastTheOriginalRangeReadFailure() throws Exception {
        var clob = new SyntheticClob(RANGE + 1L);
        var failure = new IOException("Original range read failure"); clob.readFailure = failure;
        var reader = JdbcClobReaders.open(clob);
        assertThatThrownBy(reader::read).isSameAs(failure);
        assertThatThrownBy(() -> reader.read(new char[3])).isSameAs(failure);
        reader.close(); reader.close();
        assertThat(clob.openedRanges).isEqualTo(1);
        assertThat(clob.closedRanges).isEqualTo(1);
        assertThat(clob.freed).isZero();
    }

    @Test void repeatedSingleCharacterReadsUseFixedScratchSpace() throws Exception {
        var allocation = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(allocation.isThreadAllocatedMemorySupported()).isTrue();
        boolean enabled = allocation.isThreadAllocatedMemoryEnabled();
        if (!enabled) allocation.setThreadAllocatedMemoryEnabled(true);
        var clob = new SyntheticClob(600_000);
        try (var reader = JdbcClobReaders.open(clob)) {
            for (int warm = 0; warm < 1000; warm++) reader.read();
            long before = allocation.getCurrentThreadAllocatedBytes();
            long count = 1000;
            for (int next; (next = reader.read()) != -1; count++) {
                if (next != ' ') throw new AssertionError("Changed whitespace code unit");
            }
            long bytes = allocation.getCurrentThreadAllocatedBytes() - before;
            assertThat(count).isEqualTo(clob.length);
            assertThat(bytes).as("Leading-whitespace scans must not allocate char[1] per code unit")
                    .isLessThan(1_000_000);
        } finally { if (!enabled) allocation.setThreadAllocatedMemoryEnabled(false); }
    }

    /** O(1)-memory logical data, with no mock invocation history or multi-gigabyte allocation. */
    private static final class SyntheticClob implements Clob {
        final long length;
        long lastPosition, maximumWindow, readCharacters;
        int lengthCalls, openedRanges, closedRanges, freed;
        IOException readFailure;
        SyntheticClob(long length) { this.length = length; }
        @Override public long length() { lengthCalls++; return length; }
        @Override public Reader getCharacterStream(long position, long count) {
            if (position < 1 || count < 0 || position > length || count > length - position + 1)
                throw new AssertionError("Overflowed JDBC range: " + position + "/" + count);
            lastPosition = position; maximumWindow = Math.max(maximumWindow, count); openedRanges++;
            return new Reader() {
                long remaining = count;
                @Override public int read(char[] buffer, int offset, int requested) throws IOException {
                    if (readFailure != null) throw readFailure;
                    if (requested == 0) return 0;
                    if (remaining == 0) return -1;
                    int returned = (int) Math.min(remaining, requested);
                    // skip() only needs the returned count; single-character tests read this unit.
                    buffer[offset] = ' '; remaining -= returned; readCharacters += returned; return returned;
                }
                @Override public int read() throws IOException {
                    if (readFailure != null) throw readFailure;
                    if (remaining == 0) return -1;
                    remaining--; readCharacters++; return ' ';
                }
                @Override public void close() { closedRanges++; }
            };
        }
        @Override public void free() { freed++; }
        @Override public Reader getCharacterStream() { throw new AssertionError("Large CLOB must use ranges"); }
        @Override public String getSubString(long p, int n) { throw new AssertionError("No full text materialization"); }
        @Override public InputStream getAsciiStream() { throw new UnsupportedOperationException(); }
        @Override public long position(String s, long p) { throw new UnsupportedOperationException(); }
        @Override public long position(Clob c, long p) { throw new UnsupportedOperationException(); }
        @Override public int setString(long p, String s) { throw new UnsupportedOperationException(); }
        @Override public int setString(long p, String s, int o, int n) { throw new UnsupportedOperationException(); }
        @Override public OutputStream setAsciiStream(long p) { throw new UnsupportedOperationException(); }
        @Override public Writer setCharacterStream(long p) { throw new UnsupportedOperationException(); }
        @Override public void truncate(long n) { throw new UnsupportedOperationException(); }
    }
}
