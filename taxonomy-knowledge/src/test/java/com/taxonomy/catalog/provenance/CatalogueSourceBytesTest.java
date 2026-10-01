package com.taxonomy.catalog.provenance;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class CatalogueSourceBytesTest {
    enum ClosingCancellation { FLAG_ONLY, INTERRUPTED_CLOSE_AFTER_READ_FAILURE }

    @ParameterizedTest @EnumSource(ClosingCancellation.class)
    void cancellationDuringSourceCloseCannotBecomeSuccessfulOrOrdinaryFailedCapture(ClosingCancellation mode) {
        try {
            var input = new InputStream() {
                @Override public int read() throws IOException {
                    if (mode == ClosingCancellation.INTERRUPTED_CLOSE_AFTER_READ_FAILURE) throw new IOException("private read failure");
                    return -1;
                }
                @Override public void close() throws IOException {
                    if (mode == ClosingCancellation.FLAG_ONLY) Thread.currentThread().interrupt();
                    else throw new InterruptedIOException("private close interruption");
                }
            };
            assertThatThrownBy(() -> CatalogueSourceBytes.capture(() -> input)).isInstanceOf(InterruptedIOException.class)
                    .hasNoCause().hasMessageNotContaining("private");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void parserTransferCannotExposeThePrivateCapturedArrayToAnOutputConsumer() throws Exception {
        byte[] original = "exact retained input".getBytes(StandardCharsets.UTF_8);
        var captured = CatalogueSourceBytes.read(new ByteArrayInputStream(original));
        var received = new ByteArrayOutputStream();
        try (var parser = captured.openStream()) {
            parser.transferTo(new OutputStream() {
                @Override public void write(int value) { received.write(value); }
                @Override public void write(byte[] buffer, int offset, int length) {
                    received.write(buffer, offset, length); Arrays.fill(buffer, (byte) 'X');
                }
            });
        }
        assertThat(received.toByteArray()).isEqualTo(original);
        try (var nextParser = captured.openStream()) { assertThat(nextParser.readAllBytes()).isEqualTo(original); }
        assertThat(captured.copyForStorage()).isEqualTo(original);
        assertThat(captured.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
    }

    @ParameterizedTest @ValueSource(ints = { 0, 17, 8192, 65537 })
    void parserReopensTheSameCapturedBytesAndDigest(int length) throws Exception {
        byte[] original = new byte[length]; new Random(1146).nextBytes(original);
        var expected = original.clone();
        var captured = CatalogueSourceBytes.read(new ByteArrayInputStream(original));
        Arrays.fill(original, (byte) 0);
        assertThat(captured.length()).isEqualTo(length);
        assertThat(captured.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)));
        try (var first = captured.openStream()) { assertThat(first.readAllBytes()).isEqualTo(expected); }
        try (var again = captured.openStream()) { assertThat(again.readAllBytes()).isEqualTo(expected); }
    }

    @Test void callerStillOwnsTheSourceStream() throws Exception {
        boolean[] closed = { false };
        var input = new ByteArrayInputStream("source".getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() { closed[0] = true; }
        };
        assertThat(CatalogueSourceBytes.read(input).length()).isEqualTo(6);
        assertThat(closed[0]).isFalse(); input.close(); assertThat(closed[0]).isTrue();
    }

    @Test void rejectsOversizedInputBeforeReadingAnUnboundedStream() {
        long[] read = { 0 };
        var endless = new InputStream() {
            @Override public int read() { read[0]++; return 'x'; }
            @Override public int read(byte[] buffer, int offset, int length) {
                Arrays.fill(buffer, offset, offset + length, (byte) 'x'); read[0] += length; return length;
            }
        };
        assertThatThrownBy(() -> CatalogueSourceBytes.read(endless)).isInstanceOf(IOException.class).hasNoCause();
        assertThat(read[0]).isBetween((long) CatalogueSourceBytes.MAX_BYTES + 1, (long) CatalogueSourceBytes.MAX_BYTES + 8192);
    }

    @Test void streamFailuresDoNotEchoLocationsOrAttachTheSourceException() {
        var failure = new InputStream() { @Override public int read() throws IOException { throw new IOException("https://user:SECRET@source"); } };
        assertThatThrownBy(() -> CatalogueSourceBytes.read(failure)).isInstanceOf(IOException.class).hasNoCause().hasMessageNotContaining("SECRET");
    }

    @Test void cancellationIsHonoredBeforeAndDuringRead() {
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> CatalogueSourceBytes.read(InputStream.nullInputStream())).isInstanceOf(InterruptedIOException.class).hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        try {
            var input = new InputStream() { @Override public int read() { Thread.currentThread().interrupt(); return 'x'; } };
            assertThatThrownBy(() -> CatalogueSourceBytes.read(input)).isInstanceOf(InterruptedIOException.class).hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void aSourceInterruptionPreservesCancellationWithoutItsMessage() {
        try {
            var input = new InputStream() { @Override public int read() throws IOException { throw new InterruptedIOException("SOURCE-SECRET"); } };
            assertThatThrownBy(() -> CatalogueSourceBytes.read(input)).isInstanceOf(InterruptedIOException.class).hasNoCause().hasMessageNotContaining("SECRET");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
}
