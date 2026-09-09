package com.taxonomy.export;

import com.taxonomy.archimate.ArchiMateProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ArchiMatePropertyTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "date", "STRING"})
    void rejectsMissingOrUnsupportedTypesWithAValidationError(String type) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> new ArchiMateProperty(type, "value"));
        assertTrue(error.getMessage().contains("exchange property type"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "NaN", "1.2.3", "1e999999999999"})
    void rejectsMalformedNumbersWithAStableValidationError(String value) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> new ArchiMateProperty("number", value));
        assertEquals("Invalid numeric exchange property", error.getMessage());
        assertInstanceOf(NumberFormatException.class, error.getCause());
    }

    @Test
    void preservesSupportedTypedValues() {
        assertEquals(new ArchiMateProperty("string", "Text"), ArchiMateProperty.text("Text"));
        assertEquals(new ArchiMateProperty("number", "1.25"), ArchiMateProperty.number(1.25));
        assertEquals(new ArchiMateProperty("boolean", "true"), ArchiMateProperty.flag(true));
    }
}
