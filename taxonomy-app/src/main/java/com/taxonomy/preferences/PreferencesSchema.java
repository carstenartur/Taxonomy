package com.taxonomy.preferences;

import org.eclipse.jgit.lib.Repository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Canonical schema and bounds for Git-backed runtime preferences.
 *
 * <p>The browser controls are convenience only. Every API update and every snapshot
 * loaded from Git must pass the same server-side contract so malformed values cannot
 * disable safeguards or leave a versioned configuration that the runtime interprets
 * differently from the administration UI.</p>
 */
@Component
public final class PreferencesSchema {

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "llm.rpm",
            "llm.timeout.seconds",
            "rate-limit.per-minute",
            "analysis.min-relevance-score",
            "dsl.default-branch",
            "dsl.project-name",
            "dsl.auto-save.interval-seconds",
            "dsl.remote.url",
            "dsl.remote.token",
            "dsl.remote.push-on-commit",
            "limits.max-business-text",
            "limits.max-architecture-nodes",
            "limits.max-export-nodes",
            "diagram.policy");

    private static final Set<String> DIAGRAM_POLICIES = Set.of(
            "defaultImpact", "leafOnly", "clustering", "trace");

    /** Validate a partial API mutation. */
    public void validateChanges(Map<String, Object> changes) {
        if (changes == null || changes.isEmpty()) {
            throw invalid("at least one preference change is required");
        }
        validateEntries(changes);
    }

    /** Validate a complete or historic snapshot read from the Git repository. */
    public void validateSnapshot(Map<?, ?> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            throw invalid("the Git snapshot must contain preferences");
        }
        for (Map.Entry<?, ?> entry : snapshot.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw invalid("preference keys must be strings");
            }
            validateValue(key, entry.getValue());
        }
    }

    private static void validateEntries(Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            validateValue(entry.getKey(), entry.getValue());
        }
    }

    private static void validateValue(String key, Object value) {
        if (key == null || !ALLOWED_KEYS.contains(key)) {
            throw invalid("unknown preference key: " + String.valueOf(key));
        }
        switch (key) {
            case "llm.rpm", "rate-limit.per-minute" ->
                    requireInteger(key, value, 0, 1_000);
            case "llm.timeout.seconds" ->
                    requireInteger(key, value, 5, 600);
            case "analysis.min-relevance-score" ->
                    requireInteger(key, value, 0, 100);
            case "dsl.auto-save.interval-seconds" ->
                    requireInteger(key, value, 0, 86_400);
            case "limits.max-business-text" ->
                    requireInteger(key, value, 100, 100_000);
            case "limits.max-architecture-nodes" ->
                    requireInteger(key, value, 1, 1_000);
            case "limits.max-export-nodes" ->
                    requireInteger(key, value, 1, 10_000);
            case "dsl.default-branch" -> requireBranch(value);
            case "dsl.project-name" -> requireText(key, value, 240, false);
            case "dsl.remote.url" -> requireText(key, value, 2_048, true);
            case "dsl.remote.token" -> requireToken(value);
            case "dsl.remote.push-on-commit" -> requireBoolean(key, value);
            case "diagram.policy" -> requireDiagramPolicy(value);
            default -> throw invalid("unknown preference key: " + key);
        }
    }

    private static void requireInteger(
            String key,
            Object value,
            int minimum,
            int maximum) {
        if (!(value instanceof Number number)) {
            throw invalid(key + " must be an integer");
        }
        double decimal = number.doubleValue();
        if (!Double.isFinite(decimal) || decimal != Math.rint(decimal)
                || decimal < minimum || decimal > maximum) {
            throw invalid(key + " must be an integer between "
                    + minimum + " and " + maximum);
        }
    }

    private static void requireBranch(Object value) {
        String branch = requireText("dsl.default-branch", value, 255, false);
        if ("HEAD".equalsIgnoreCase(branch)
                || !Repository.isValidRefName("refs/heads/" + branch)) {
            throw invalid("dsl.default-branch must be a valid Git branch name");
        }
    }

    private static void requireToken(Object value) {
        String token = requireText("dsl.remote.token", value, 16_384, true);
        if (token.startsWith("****")) {
            throw invalid("dsl.remote.token must not contain a masked display value");
        }
    }

    private static void requireBoolean(String key, Object value) {
        if (!(value instanceof Boolean)) {
            throw invalid(key + " must be true or false");
        }
    }

    private static void requireDiagramPolicy(Object value) {
        String policy = requireText("diagram.policy", value, 64, false);
        if (!DIAGRAM_POLICIES.contains(policy)) {
            throw invalid("diagram.policy is not supported");
        }
    }

    private static String requireText(
            String key,
            Object value,
            int maximumLength,
            boolean blankAllowed) {
        if (!(value instanceof String text)) {
            throw invalid(key + " must be text");
        }
        if (text.length() > maximumLength) {
            throw invalid(key + " exceeds its maximum length");
        }
        if (!blankAllowed && text.isBlank()) {
            throw invalid(key + " must not be blank");
        }
        if (!text.equals(text.strip())) {
            throw invalid(key + " must not contain leading or trailing whitespace");
        }
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw invalid(key + " must not contain control characters");
        }
        return text;
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("Invalid preference configuration: " + detail);
    }
}
