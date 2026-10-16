package com.taxonomy.dto;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Numeric boundary for analysis percentages (0.5 means 0.5%, never 50%).
 * Decimal arithmetic is used before conversion to the existing JSON/JavaScript
 * numeric representation. An unrepresentable positive result is an explicit
 * error, not evidence that the node is irrelevant.
 */
public final class AnalysisPercentages {
    public static final String POLICY = "decimal-percent-v2";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private AnalysisPercentages() { }

    public static double require(Number number) {
        Objects.requireNonNull(number, "An assessment requires a numeric percentage");
        final BigDecimal value;
        try { value = number instanceof BigDecimal decimal ? decimal : new BigDecimal(number.toString()); }
        catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Percentage must be a finite number between 0 and 100", invalid);
        }
        if (value.signum() < 0 || value.compareTo(HUNDRED) > 0)
            throw new IllegalArgumentException("Percentage must be between 0 and 100");
        return represent(value);
    }

    public static double require(double value) { return require(Double.valueOf(value)); }

    public static boolean valid(Double value) {
        return value != null && Double.isFinite(value) && value >= 0 && value <= 100;
    }

    public static Map<String, Double> copy(Map<String, ? extends Number> input) {
        var result = new LinkedHashMap<String, Double>();
        if (input != null) input.forEach((key, value) -> result.put(key, require(value)));
        return result;
    }

    public static double product(double family, double suitability) {
        return represent(BigDecimal.valueOf(require(family))
                .multiply(BigDecimal.valueOf(require(suitability))).movePointLeft(2));
    }

    /** Proportional allocation; explicit zero weights stay zero, including an all-zero answer. */
    public static Map<String, Double> normalize(Map<String, ? extends Number> scores, double parent) {
        var result = copy(Objects.requireNonNull(scores));
        BigDecimal target = BigDecimal.valueOf(require(parent));
        BigDecimal sum = result.values().stream().map(BigDecimal::valueOf).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.signum() == 0 || sum.compareTo(target) == 0) return result;
        String largest = null;
        for (var entry : result.entrySet()) {
            double scaled = represent(BigDecimal.valueOf(entry.getValue()).multiply(target).divide(sum, PRECISION));
            entry.setValue(scaled);
            if (largest == null || scaled > result.get(largest)
                    || scaled == result.get(largest) && entry.getKey().compareTo(largest) < 0) largest = entry.getKey();
        }
        // Put any floating-point representation residual on the largest share, not
        // on a tiny child. No whole-percent rounding and no artificial minimum score.
        if (largest != null && parent > 0) {
            double remainder = parent - result.values().stream().mapToDouble(Double::doubleValue).sum();
            double corrected = result.get(largest) + remainder;
            if (!(corrected > 0)) throw new IllegalArgumentException("Percentage allocation cannot preserve positive evidence");
            result.put(largest, require(corrected));
        }
        return result;
    }

    /** Canonical numeric text also preserves the spelling of historical integer evidence. */
    public static String canonical(double percentage) {
        return BigDecimal.valueOf(require(percentage)).stripTrailingZeros().toPlainString();
    }

    /** Presentation only. Very small positives must not appear as an exact zero. */
    public static String display(double percentage) {
        require(percentage);
        if (percentage > 0 && percentage < 0.000001) return "<0.000001%";
        return BigDecimal.valueOf(percentage).setScale(6, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString() + "%";
    }

    private static double represent(BigDecimal value) {
        double result = value.doubleValue();
        if (!Double.isFinite(result) || result == 0 && value.signum() != 0)
            throw new IllegalArgumentException("Percentage is outside the supported numeric precision; leave the assessment open");
        return result == 0 ? 0d : result;
    }
}
