package com.taxonomy.web;

import org.apache.coyote.Response;
import org.apache.coyote.http11.HttpOutputBuffer;
import org.apache.coyote.http11.filters.ChunkedOutputFilter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A disconnected client must not corrupt the next response handled by a recycled HTTP processor. */
class TomcatChunkedResponseTest {
    private static final byte[] TERMINATOR = bytes("0\r\n\r\n");

    @ParameterizedTest
    @ValueSource(ints = {1, 5})
    void recycledFilterCompletesNextResponseAfterTerminationWriteFails(int consumedBytes) throws Exception {
        var filter = new ChunkedOutputFilter();
        var disconnected = new RecordingTransport(consumedBytes);
        filter.setBuffer(disconnected);
        filter.setResponse(new Response());
        filter.doWrite(ByteBuffer.wrap(bytes("first response")));

        assertThatThrownBy(filter::end)
                .isInstanceOf(IOException.class)
                .hasMessage("Client disconnected while writing the response terminator");

        // Tomcat pools the processor, including this filter, after closing the failed connection.
        filter.recycle();
        var next = new RecordingTransport(0);
        filter.setBuffer(next);
        filter.setResponse(new Response());
        filter.doWrite(ByteBuffer.wrap(bytes("OK")));
        filter.end();

        assertThat(next.output.toByteArray())
                .as("the next HTTP/1.1 response must retain its complete chunked framing")
                .isEqualTo(bytes("2\r\nOK\r\n0\r\n\r\n"));
        assertThat(next.ended).as("the downstream response must be completed").isTrue();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /** The real encoder is unchanged; only the downstream I/O boundary disconnects. */
    private static final class RecordingTransport implements HttpOutputBuffer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final int failAfterTerminationBytes;
        private boolean ended;

        private RecordingTransport(int failAfterTerminationBytes) {
            this.failAfterTerminationBytes = failAfterTerminationBytes;
        }

        @Override
        public int doWrite(ByteBuffer source) throws IOException {
            boolean disconnect = failAfterTerminationBytes > 0
                    && source.duplicate().equals(ByteBuffer.wrap(TERMINATOR));
            byte[] consumed = new byte[disconnect ? failAfterTerminationBytes : source.remaining()];
            source.get(consumed);
            output.writeBytes(consumed);
            if (disconnect) {
                throw new IOException("Client disconnected while writing the response terminator");
            }
            return consumed.length;
        }

        @Override public long getBytesWritten() { return output.size(); }
        @Override public void flush() { }
        @Override public void end() { ended = true; }
    }
}
