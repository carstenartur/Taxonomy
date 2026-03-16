package com.taxonomy;

import com.taxonomy.shared.model.FloatArrayConverter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FloatArrayConverterTest {

    private final FloatArrayConverter converter = new FloatArrayConverter();

    @Test
    void nullConvertsToDatabaseColumnAsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void nullConvertsToEntityAttributeAsNull() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void roundTripPreservesValues() {
        float[] input = {1.0f, -0.5f, 0.0f, Float.MAX_VALUE, Float.MIN_VALUE};
        byte[] dbData = converter.convertToDatabaseColumn(input);
        float[] result = converter.convertToEntityAttribute(dbData);
        assertThat(result).containsExactly(input);
    }

    @Test
    void emptyArrayRoundTrips() {
        float[] input = new float[0];
        byte[] dbData = converter.convertToDatabaseColumn(input);
        float[] result = converter.convertToEntityAttribute(dbData);
        assertThat(result).isEmpty();
    }

    @Test
    void singleElementRoundTrips() {
        float[] input = {3.14159f};
        byte[] dbData = converter.convertToDatabaseColumn(input);
        float[] result = converter.convertToEntityAttribute(dbData);
        assertThat(result).containsExactly(input);
    }

    @Test
    void embeddingDimensionRoundTrips() {
        float[] input = new float[384];
        for (int i = 0; i < 384; i++) {
            input[i] = (float) Math.sin(i * 0.01);
        }
        byte[] dbData = converter.convertToDatabaseColumn(input);
        assertThat(dbData).hasSize(384 * Float.BYTES);
        float[] result = converter.convertToEntityAttribute(dbData);
        assertThat(result).containsExactly(input);
    }
}
