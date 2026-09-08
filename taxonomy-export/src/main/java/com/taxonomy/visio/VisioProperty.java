package com.taxonomy.visio;

import java.math.BigDecimal;
import java.util.Objects;

/** Literal Shape Data values; user values are never written as ShapeSheet formulas. */
public record VisioProperty(Kind kind, String value) {
    public enum Kind { STRING, NUMBER, BOOLEAN }

    public VisioProperty {
        Objects.requireNonNull(kind, "property kind");
        Objects.requireNonNull(value, "property value");
        if (kind == Kind.NUMBER) {
            if (value.length() > 350) throw new IllegalArgumentException("Property number is too long");
            BigDecimal number = new BigDecimal(value).stripTrailingZeros();
            if (Math.abs((long) number.scale()) > 350 || number.precision() > 350
                    || !Double.isFinite(number.doubleValue())) {
                throw new IllegalArgumentException("Property number is outside the supported finite range");
            }
            value = number.toPlainString();
        }
        if (kind == Kind.BOOLEAN && !value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException("Invalid Boolean property");
        }
    }

    public static VisioProperty text(Object value) {
        return new VisioProperty(Kind.STRING, Objects.requireNonNull(value, "property value").toString());
    }
    public static VisioProperty number(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Property number must be finite");
        return new VisioProperty(Kind.NUMBER, BigDecimal.valueOf(value).toPlainString());
    }
    public static VisioProperty bool(boolean value) { return new VisioProperty(Kind.BOOLEAN, Boolean.toString(value)); }
    public String shapeType() { return switch (kind) { case STRING -> "0"; case NUMBER -> "2"; case BOOLEAN -> "3"; }; }
    public String shapeValue() { return kind == Kind.BOOLEAN ? (value.equals("true") ? "1" : "0") : value; }
}
