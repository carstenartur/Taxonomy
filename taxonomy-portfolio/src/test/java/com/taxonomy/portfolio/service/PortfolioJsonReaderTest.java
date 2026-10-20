package com.taxonomy.portfolio.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PortfolioJsonReaderTest {
    final PortfolioJsonCodec codec = new PortfolioJsonCodec(JsonMapper.builder().build());

    @Test void nullAndBlankPayloadsKeepStringSemanticsWithoutClosingCallerReaders() {
        assertThat(codec.read((Reader) null, Map.class)).isNull();
        for (String payload : new String[] {"", " \t\r\n", "\u2003\u2002", "null"}) {
            var input = new Input(payload);
            assertThat(codec.read(input, Map.class)).isNull();
            assertThat(input.closed).isZero();
        }
        var input = new Input(" \t{\"answer\":42}");
        assertThat(codec.read(input, Map.class)).isEqualTo(codec.read(" \t{\"answer\":42}", Map.class));
        assertThat(input.closed).isZero();
    }

    @Test void parsingFailureRemainsTypedAndDoesNotCloseTheCallerReader() {
        for (String payload : new String[] {"{", "\u2003{}"}) {
            var input = new Input(payload);
            assertThatThrownBy(() -> codec.read(input, Map.class)).isInstanceOf(PortfolioException.class)
                    .hasMessage("Could not deserialize portfolio payload as Map");
            assertThat(input.closed).isZero();
        }
    }

    @Test void aReadFailureAfterTheClosingJsonTokenIsNotLost() {
        var codec = new PortfolioJsonCodec(JsonMapper.builder()
                .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build());
        var input = new Input("{}");
        var late = new IOException("late JDBC read failure");
        input.atEnd = late;
        assertThatThrownBy(() -> codec.read(input, Map.class)).isInstanceOf(PortfolioException.class)
                .hasMessage("Could not deserialize portfolio payload as Map").hasRootCause(late);
        assertThat(input.closed).isZero();
    }

    @Test void drainingTheWholeInputDoesNotAddAStricterTrailingTokenPolicy() {
        var codec = new PortfolioJsonCodec(JsonMapper.builder()
                .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build());
        String payload = "{} {\"historicalTrailingValue\":true}";
        var input = new Input(payload);
        assertThat(codec.read(input, Map.class)).isEqualTo(codec.read(payload, Map.class));
        assertThat(input.position).isEqualTo(payload.length());
        assertThat(input.closed).isZero();
    }

    private static final class Input extends Reader {
        final StringReader delegate;
        int position, closed;
        IOException atEnd;
        Input(String payload) { delegate = new StringReader(payload); }
        @Override public int read(char[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, Math.min(length, 2));
            if (count == -1 && atEnd != null) throw atEnd;
            if (count > 0) position += count;
            return count;
        }
        @Override public void close() { closed++; delegate.close(); }
    }
}
