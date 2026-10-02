package com.taxonomy.backup.archive;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InterruptedIOException;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.*;

class VerifiedBackupReadGuardTest {
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
