package com.taxonomy.portfolio.payload;

import com.taxonomy.exchange.backup.PortableJsonPayloads;
import com.taxonomy.portfolio.service.PortfolioException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.function.IntUnaryOperator;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.*;

class PortfolioAnalysisPayloadsTest {
    private static final String FAMILY = "taxonomy-json-payload:";
    private static final int LIMIT = 24_000_000;

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {" \t\r\n\u2003", "\"taxonomy-json-payload:v1:literal\"", "{\"水\":\"🌊\"}"})
    void legacyValuesAreDelegatedExactly(String value) throws IOException {
        assertThat(PortfolioAnalysisPayloads.plain(value)).isSameAs(value);
        assertThat(PortfolioAnalysisPayloads.store(value, true)).isSameAs(value);
        assertThat(PortfolioAnalysisPayloads.store(value, false)).isSameAs(value);
        Reader stored = value == null ? null : new StringReader(value);
        String parsed = PortfolioAnalysisPayloads.read(stored,
                input -> input == null ? "parser saw null" : collect(input));
        assertThat(parsed).isEqualTo(value == null ? "parser saw null" : value);
    }

    @Test
    void legacyPrefixProbeIsBoundedAndParserCanCloseItsBorrowedReader() throws IOException {
        String source = "taxonomy-json-payload;this tail must remain with the caller";
        var stored = new GeneratedReader(source.length(), source::charAt);
        stored.availableThrough = FAMILY.length();
        String prefix = PortfolioAnalysisPayloads.read(stored, parserInput -> {
            assertThat(stored.position).isLessThanOrEqualTo(FAMILY.length());
            try (parserInput) {
                char[] units = new char[FAMILY.length()];
                for (int i = 0; i < units.length; i++) units[i] = (char) parserInput.read();
                return new String(units);
            }
        });
        assertThat(prefix).isEqualTo(source.substring(0, FAMILY.length()));
        assertThat(stored.closed).isFalse();
        stored.availableThrough = source.length();
        assertThat(collect(stored)).isEqualTo(source.substring(FAMILY.length()));
    }

    @Test
    void legacyStreamingParserObservesLateReadErrorsAndCallerStillOwnsTheReader() {
        String source = "{\"status\":\"PARTIAL\"}";
        var stored = new GeneratedReader(source.length(), source::charAt);
        stored.eofFailure = new IOException("late legacy CLOB failure");
        assertThatThrownBy(() -> PortfolioAnalysisPayloads.read(stored, PortfolioAnalysisPayloadsTest::collect))
                .isSameAs(stored.eofFailure);
        assertThat(stored.position).isEqualTo(source.length());
        assertThat(stored.closed).isFalse();
    }

    @Test
    void legacyReaderCanStreamPastThePackedCharacterLimit() throws IOException {
        int length = LIMIT + 37;
        var stored = new GeneratedReader(length, index -> index == 0 || index == length - 1 ? '"' : '水');
        stored.availableThrough = FAMILY.length();
        long count = PortfolioAnalysisPayloads.read(stored, input -> {
            stored.availableThrough = length;
            return input.transferTo(Writer.nullWriter());
        });
        assertThat(count).isEqualTo(length);
        assertThat(stored.position).isEqualTo(length);
        assertThat(stored.closed).isFalse();
    }

    @Test
    void largeLegacyStringIsReturnedWithoutApplyingTheFrameLimit() {
        String source = "\"" + "x".repeat(LIMIT) + "\"";
        assertThat(PortfolioAnalysisPayloads.plain(source)).isSameAs(source);
        assertThat(PortfolioAnalysisPayloads.store(source, true)).isSameAs(source);
    }

    @Test
    void eligibleStorageFollowsTheSwitchAndPreservesExactOriginalText() {
        String original = " \r\n{\"status\":\"PARTIAL\",\"evidence\":\"" + "🌊水".repeat(24_000) + "\"}\n";
        assertThat(PortfolioAnalysisPayloads.store(original, false)).isSameAs(original);
        String packed = PortfolioAnalysisPayloads.store(original, true);
        assertThat(packed).startsWith(FAMILY + "v1:").hasSizeLessThan(original.length());
        assertThat(PortfolioAnalysisPayloads.plain(packed)).isEqualTo(original);
    }

    @Test
    void optionalSnapshotWriterUsesFastV1WhileOrdinaryPackingKeepsDefaultEffort() throws Exception {
        String original = " \r\n{\"evidence\":\"" + "Quelle 水位 🌊 e\u0301 \uD800x\uDC00\t".repeat(8_000) + "\"}\n";
        String defaultFrame = frame(original);
        String fastFrame = frame(original, Deflater.BEST_SPEED);
        assertThat(fastFrame).isNotEqualTo(defaultFrame);
        assertThat(PortableJsonPayloads.pack(original, LIMIT)).isEqualTo(defaultFrame);

        String stored = PortfolioAnalysisPayloads.store(original, true);
        assertThat(stored).as("optional portfolio v1 must match the BEST_SPEED witness").isEqualTo(fastFrame);
        assertThat(PortableJsonPayloads.unpack(fastFrame, LIMIT)).isEqualTo(original);
        assertThat(PortableJsonPayloads.unpack(defaultFrame, LIMIT)).isEqualTo(original);
    }

    @Test
    void bothReadersKeepExactTextAcrossSurrogateAndBufferBoundaries() throws Exception {
        String original = "\"" + "x".repeat(4_094) + "🌊水\uD800x\uDC00\"\r\n";
        String frame = frame(original);
        var stored = new GeneratedReader(frame.length(), frame::charAt);
        assertThat(PortfolioAnalysisPayloads.plain(frame)).isEqualTo(original);
        assertThat(PortfolioAnalysisPayloads.read(stored, PortfolioAnalysisPayloadsTest::collect)).isEqualTo(original);
        assertThat(stored.position).isEqualTo(frame.length());
        assertThat(stored.closed).isFalse();
    }

    enum Damage { VERSION, BASE64, COUNT, NONCANONICAL_COUNT, HASH, TRUNCATED, DECODED_SIZE }

    @ParameterizedTest @EnumSource(Damage.class)
    void framesUseTheStrictDecoderForStringAndStreamingReads(Damage damage) throws Exception {
        String[] fields = frame("{\"status\":\"PARTIAL\"}").split(":", 5);
        switch (damage) {
            case VERSION -> fields[1] = "v2";
            case BASE64 -> fields[4] = "?" + fields[4].substring(1);
            case COUNT -> fields[2] = "1";
            case NONCANONICAL_COUNT -> fields[2] = "0" + fields[2];
            case HASH -> fields[3] = "0".repeat(64);
            case TRUNCATED -> fields[4] = fields[4].substring(0, fields[4].length() - 4);
            case DECODED_SIZE -> fields[2] = Integer.toString(LIMIT + 1);
        }
        String stored = String.join(":", fields);
        assertThatThrownBy(() -> PortfolioAnalysisPayloads.plain(stored))
                .isInstanceOfSatisfying(PortfolioException.class, failure -> {
                    assertThat(failure.getKind()).isEqualTo(PortfolioException.Kind.ANALYSIS_FAILED);
                    assertThat(failure.getCause()).isInstanceOf(IOException.class);
                });
        assertThatThrownBy(() -> PortfolioAnalysisPayloads.read(new StringReader(stored),
                PortfolioAnalysisPayloadsTest::collect)).isInstanceOf(IOException.class);
    }

    @Test
    void oversizedStoredFrameIsRejectedBeforeCallingTheParser() {
        int length = LIMIT + 10_000;
        var stored = new GeneratedReader(length, index -> index < FAMILY.length() ? FAMILY.charAt(index) : 'A');
        assertThatThrownBy(() -> PortfolioAnalysisPayloads.read(stored, ignored -> {
            throw new AssertionError("oversized stored frame reached parser");
        })).isInstanceOf(IOException.class);
        assertThat(stored.position).isBetween(LIMIT + 1, length - 1);
        assertThat(stored.closed).isFalse();
    }

    @Test
    void corruptFrameTailCannotBeAcceptedWhenParserStopsAndClosesEarly() throws Exception {
        String[] fields = frame("{\"status\":\"PARTIAL\"}" + " ".repeat(24_000)).split(":", 5);
        fields[3] = "0".repeat(64);
        String frame = String.join(":", fields);
        var stored = new GeneratedReader(frame.length(), frame::charAt);
        boolean[] parsed = {false};
        assertThatThrownBy(() -> PortfolioAnalysisPayloads.read(stored, parserInput -> {
            try (parserInput) {
                assertThat(parserInput.read()).isEqualTo('{');
                parsed[0] = true;
                return "early success";
            }
        })).isInstanceOf(IOException.class);
        assertThat(parsed[0]).isTrue();
        assertThat(stored.closed).isFalse();
    }

    @Test
    void packedReaderObservesLateStoredReadErrorsBeforeCallingTheParser() throws Exception {
        String frame = frame("{}");
        var stored = new GeneratedReader(frame.length(), frame::charAt);
        stored.eofFailure = new IOException("late packed CLOB failure");
        assertThatThrownBy(() -> PortfolioAnalysisPayloads.read(stored, ignored -> {
            throw new AssertionError("frame with a failed stored read reached parser");
        })).isSameAs(stored.eofFailure);
        assertThat(stored.closed).isFalse();
    }

    private static String collect(Reader input) throws IOException {
        var output = new StringWriter();
        input.transferTo(output);
        return output.toString();
    }

    private static String frame(String original) throws Exception {
        return frame(original, Deflater.DEFAULT_COMPRESSION);
    }

    private static String frame(String original, int level) throws Exception {
        byte[] units = new byte[original.length() * 2];
        for (int i = 0; i < original.length(); i++) {
            units[2 * i] = (byte) (original.charAt(i) >>> 8);
            units[2 * i + 1] = (byte) original.charAt(i);
        }
        var compressed = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(compressed) {
            { def.setLevel(level); }
        }) {
            gzip.write(units);
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(units);
        return FAMILY + "v1:" + original.length() + ":" + HexFormat.of().formatHex(digest) + ":"
                + Base64.getEncoder().encodeToString(compressed.toByteArray());
    }

    private static final class GeneratedReader extends Reader {
        private final int length;
        private final IntUnaryOperator unitAt;
        private int availableThrough;
        private int position;
        private boolean closed;
        private IOException eofFailure;

        GeneratedReader(int length, IntUnaryOperator unitAt) {
            this.length = length;
            this.unitAt = unitAt;
            availableThrough = length;
        }

        @Override public int read(char[] output, int offset, int count) throws IOException {
            assertThat(closed).isFalse();
            if (count == 0) return 0;
            if (position == length) {
                if (eofFailure != null) throw eofFailure;
                return -1;
            }
            int read = Math.min(count, length - position);
            if (position + read > availableThrough) throw new AssertionError("read past the bounded prefix");
            for (int i = 0; i < read; i++) output[offset + i] = (char) unitAt.applyAsInt(position++);
            return read;
        }

        @Override public void close() { closed = true; }
    }
}
