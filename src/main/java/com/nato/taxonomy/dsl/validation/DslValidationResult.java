package com.nato.taxonomy.dsl.validation;

import java.util.*;

/**
 * Holds the result of DSL validation with categorized errors and warnings.
 */
public class DslValidationResult {

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public void addError(String message) { errors.add(message); }
    public void addWarning(String message) { warnings.add(message); }

    public List<String> getErrors() { return Collections.unmodifiableList(errors); }
    public List<String> getWarnings() { return Collections.unmodifiableList(warnings); }

    public boolean isValid() { return errors.isEmpty(); }

    public boolean hasWarnings() { return !warnings.isEmpty(); }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (!errors.isEmpty()) {
            sb.append("Errors:\n");
            errors.forEach(e -> sb.append("  - ").append(e).append('\n'));
        }
        if (!warnings.isEmpty()) {
            sb.append("Warnings:\n");
            warnings.forEach(w -> sb.append("  - ").append(w).append('\n'));
        }
        if (errors.isEmpty() && warnings.isEmpty()) {
            sb.append("Valid");
        }
        return sb.toString();
    }
}
