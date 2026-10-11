package com.taxonomy.backup.archive;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.*;

class VerifiedBackupReadGuardTest {
    @Test void revokedOperationStopsBeforeMoreArchiveBytesAndCloseDoesNotDrainTheRemainder() throws Exception {
        var revoked = new AtomicBoolean(); var reads = new AtomicInteger(); var closed = new AtomicBoolean();
        var bytes = new byte[65_536]; var crc = new CRC32(); crc.update(bytes);
        var source = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] target, int offset, int length) {
                int count = super.read(target, offset, length); if (count > 0) reads.addAndGet(count); return count;
            }
            @Override public void close() { closed.set(true); }
        };
        var input = new VerifiedBackup.CheckedInput(source, bytes.length, null, crc.getValue(),
                new ArchiveIO.ReadGuard(ArchiveLimits.defaults()), () -> { if (revoked.get()) throw new IOException("Operation revoked"); });
        assertThat(input.readNBytes(8)).hasSize(8); revoked.set(true);
        try {
            assertThatThrownBy(input::read).isInstanceOf(IOException.class).hasMessage("Operation revoked");
        } finally { input.close(); }
        assertThat(reads.get()).isEqualTo(8); assertThat(closed.get()).isTrue();
    }

    @Test void cancellationFirstObservedByCloseCannotDrainUnreadPayload() throws Exception {
        var cancelled = new AtomicBoolean(); var reads = new AtomicInteger(); var closed = new AtomicBoolean();
        var bytes = new byte[65_536]; var crc = new CRC32(); crc.update(bytes);
        var source = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] target, int offset, int length) {
                int count = super.read(target, offset, length); if (count > 0) reads.addAndGet(count); return count;
            }
            @Override public void close() { closed.set(true); }
        };
        var input = new VerifiedBackup.CheckedInput(source, bytes.length, null, crc.getValue(),
                new ArchiveIO.ReadGuard(ArchiveLimits.defaults()), () -> { if (cancelled.get()) throw new InterruptedIOException("Operation cancelled"); });
        assertThat(input.readNBytes(8)).hasSize(8); cancelled.set(true);
        try {
            assertThatThrownBy(input::close).isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(reads.get()).isEqualTo(8); assertThat(closed.get()).isTrue();
    }

    @Test void interruptionDuringTheUnderlyingReadCannotReturnSuccessfulEntryBytes() throws Exception {
        var source = new ByteArrayInputStream(new byte[]{42}) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                int count = super.read(bytes, offset, length);
                Thread.currentThread().interrupt();
                return count;
            }
        };
        var crc = new CRC32(); crc.update(42);
        try (var input = new VerifiedBackup.CheckedInput(source, 1, null, crc.getValue(),
                new ArchiveIO.ReadGuard(ArchiveLimits.defaults()))) {
            assertThatThrownBy(input::read).isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
}
