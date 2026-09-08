package com.taxonomy.archimate;

import java.math.BigDecimal;
import java.util.Objects;

/** A typed exchange property; arbitrary application objects are never serialized. */
public record ArchiMateProperty(String type, String value) {
    public ArchiMateProperty {
        if (type == null) throw new IllegalArgumentException("Missing exchange property type");
        Objects.requireNonNull(value, "property value");
        switch (type) {
            case "string" -> { }
            case "boolean" -> {
                if (!value.equals("true") && !value.equals("false")) {
                    throw new IllegalArgumentException("Invalid boolean exchange property");
                }
            }
            case "number" -> new BigDecimal(value);
            default -> throw new IllegalArgumentException("Unsupported exchange property type: " + type);
        }
    }

    public static ArchiMateProperty text(String value) {
        return new ArchiMateProperty("string", value);
    }

    public static ArchiMateProperty number(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite exchange property");
        return new ArchiMateProperty("number", Double.toString(value));
    }

    public static ArchiMateProperty flag(boolean value) {
        return new ArchiMateProperty("boolean", Boolean.toString(value));
    }
}
