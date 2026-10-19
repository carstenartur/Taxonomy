package com.taxonomy.exchange.backup;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

class PortableJsonPayloadsWriterTest {
    private static final int LIMIT = 24_000_000;

    @Test void chunkedAndSingleUnitWritersMatchEveryLegacyFrameAndFallbackExactly() throws Exception {
        var values = new ArrayList<String>();
        for (int size : new int[]{0, 1, 2, 4095, 4096, 8191, 8192, 65_535, 65_536, 65_537, 131_072}) {
            values.add("x".repeat(size));
            values.add("水位😀\uD800X\uDC00\u0000".repeat(size / 4 + 2).substring(0, size));
        }
        var random = new Random(1251);
        for (int i = 0; i < 80; i++) {
            char[] units = new char[i < 3 ? 65_535 + i : random.nextInt(100_000)];
            for (int c = 0; c < units.length; c++) units[c] = (char) random.nextInt(65_536);
            values.add(new String(units));
        }
        for (String value : values) {
            String expected = PortableJsonPayloads.pack(value, LIMIT);
            String actual = PortableJsonPayloads.packSerialized(writer -> {
                for (int offset = 0; offset < value.length();) {
                    int count = Math.min(7919, value.length() - offset);
                    writer.write(value, offset, count); offset += count;
                    if (offset % 2 == 0) writer.flush();
                }
            }, LIMIT);
            assertThat(actual).isEqualTo(expected);
            assertThat(PortableJsonPayloads.unpack(actual, LIMIT)).isEqualTo(value);
            assertThat(PortableJsonPayloads.packSerialized(writer -> {
                for (char unit : value.toCharArray()) writer.write(unit);
                writer.close();
            }, LIMIT)).isEqualTo(expected);
        }
    }

    @Test void completeMapperOutputAndFeaturesKeepTheExactExistingRepresentation() throws Exception {
        var values = new ArrayList<Object>(Arrays.asList(null, "", Map.of("b", "中😀\uD800", "a", List.of(1, 2, 3)),
                "x".repeat(65_536), "a".repeat(16_383) + "\uD800X\uDC00"));
        var random = new Random(1251);
        for (int i = 0; i < 20; i++) {
            char[] units = new char[random.nextInt(30_000)];
            for (int c = 0; c < units.length; c++) units[c] = (char) random.nextInt(65_536);
            values.add(new String(units));
        }
        for (var mapper : List.of(new ObjectMapper(),
                JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build(),
                JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build(),
                JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build(),
                JsonMapper.builder().enable(SerializationFeature.WRAP_ROOT_VALUE).build())) {
            for (Object value : values) {
                String json = mapper.writeValueAsString(value);
                String actual = PortableJsonPayloads.packSerialized(writer -> mapper.writeValue(writer, value), LIMIT);
                assertThat(actual).isEqualTo(PortableJsonPayloads.pack(json, LIMIT));
                assertThat(PortableJsonPayloads.unpack(actual, LIMIT)).isEqualTo(json);
            }
        }
    }

    @Test void quotaIsStickyEvenWhenTheSerializerSwallowsOrWrapsItsIoFailure() {
        assertThatThrownBy(() -> PortableJsonPayloads.packSerialized(w -> w.write("a"), -1))
                .isInstanceOf(IOException.class);
        for (var serializer : List.<PortableJsonPayloads.PayloadWriter>of(
                w -> w.write("overflow"),
                w -> { try { w.write("overflow"); } catch (IOException ignored) { } },
                w -> { try { w.write("overflow"); } catch (IOException ignored) { } w.write("x"); },
                w -> { try { w.write("overflow"); } catch (IOException e) { throw new IllegalStateException(e); } })) {
            assertThatThrownBy(() -> PortableJsonPayloads.packSerialized(serializer, 1)).isInstanceOf(IOException.class);
        }
        var mapper = new ObjectMapper();
        String value = "x".repeat(100_000);
        assertThatThrownBy(() -> PortableJsonPayloads.packSerialized(w -> mapper.writeValue(w, value), 65_536))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> PortableJsonPayloads.packSerialized(w -> {
            try { mapper.writeValue(w, value); } catch (RuntimeException ignored) { }
        }, 65_536)).isInstanceOf(IOException.class);
    }

    @Test void serializerFailureDoesNotPublishPartialStateOrBecomeSelfSuppression() throws Exception {
        var failure = new IOException("Original serializer failure");
        String[] state = {"original"};
        assertThatThrownBy(() -> state[0] = PortableJsonPayloads.packSerialized(w -> {
            w.write("accepted prefix"); throw failure;
        }, LIMIT)).isSameAs(failure);
        assertThat(state[0]).isEqualTo("original");
        assertThatThrownBy(() -> PortableJsonPayloads.packSerialized(w -> {
            w.write("accepted"); w.write("overflow");
        }, 8)).isInstanceOf(IOException.class).hasNoSuppressedExceptions();
        assertThat(PortableJsonPayloads.packSerialized(w -> { w.write("complete"); w.close(); }, 8))
                .isEqualTo("complete");
        assertThatThrownBy(() -> PortableJsonPayloads.packSerialized(w -> { w.close(); w.write("x"); }, 1))
                .isInstanceOf(IOException.class);
    }

    @Test void exactLogicalLimitAndAnInterruptedCheckpointKeepEveryCodeUnit() throws Exception {
        String value = "水位\uD800X\uDC00".repeat(24_000);
        assertThat(PortableJsonPayloads.packSerialized(w -> w.write(value), value.length()))
                .isEqualTo(PortableJsonPayloads.pack(value, value.length()));
        assertThatThrownBy(() -> PortableJsonPayloads.packSerialized(w -> w.write(value), value.length() - 1))
                .isInstanceOf(IOException.class);
        Thread.currentThread().interrupt();
        try {
            String stored = PortableJsonPayloads.packSerialized(w -> w.write(value.toCharArray()), LIMIT);
            assertThat(PortableJsonPayloads.unpack(stored, LIMIT)).isEqualTo(value);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void commonCompressibleWriterDoesNotAllocateAnExpandedDocument() throws Exception {
        String value = "Complete original retained evidence 水位 🌊\n".repeat(200_000);
        var mapper = new ObjectMapper();
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(bean.isThreadAllocatedMemorySupported()).isTrue();
        boolean enabled = bean.isThreadAllocatedMemoryEnabled();
        if (!enabled) bean.setThreadAllocatedMemoryEnabled(true);
        try {
            PortableJsonPayloads.pack(mapper.writeValueAsString(value), LIMIT);
            PortableJsonPayloads.packSerialized(w -> mapper.writeValue(w, value), LIMIT);
            long before = bean.getCurrentThreadAllocatedBytes();
            String legacy = PortableJsonPayloads.pack(mapper.writeValueAsString(value), LIMIT);
            long materialized = bean.getCurrentThreadAllocatedBytes() - before;
            before = bean.getCurrentThreadAllocatedBytes();
            String streamed = PortableJsonPayloads.packSerialized(w -> mapper.writeValue(w, value), LIMIT);
            long allocated = bean.getCurrentThreadAllocatedBytes() - before;
            assertThat(streamed).isEqualTo(legacy);
            assertThat(allocated).as("streamed=%s; materialized=%s", allocated, materialized)
                    .isLessThan(materialized / 5);
        } finally {
            if (!enabled) bean.setThreadAllocatedMemoryEnabled(false);
        }
    }
}
