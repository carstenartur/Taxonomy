package com.taxonomy.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class FloatArrayConverter implements AttributeConverter<float[], byte[]> {

    @Override
    public byte[] convertToDatabaseColumn(float[] attribute) {
        if (attribute == null) return null;
        ByteBuffer buffer = ByteBuffer.allocate(attribute.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float f : attribute) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    @Override
    public float[] convertToEntityAttribute(byte[] dbData) {
        if (dbData == null) return null;
        ByteBuffer buffer = ByteBuffer.wrap(dbData).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[dbData.length / Float.BYTES];
        for (int i = 0; i < result.length; i++) {
            result[i] = buffer.getFloat();
        }
        return result;
    }
}
