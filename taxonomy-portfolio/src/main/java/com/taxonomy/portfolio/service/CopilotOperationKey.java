package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.model.AnalysisAutomationProfile;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compact, parseable metadata stored in the existing analysis-job idempotency key. */
public record CopilotOperationKey(
        boolean autopilot,
        String operationId,
        int pass,
        int totalPasses,
        AnalysisAutomationProfile profile,
        boolean proposeSolutions,
        boolean proposeProducts) {

    private static final Pattern FORMAT = Pattern.compile(
            "^(copilot|autopilot):v1:([0-9a-f]{64}):([1-3])/([1-3]):"
                    + "(STANDARD|FULL|EXHAUSTIVE):([01])([01])$");

    public CopilotOperationKey {
        if (operationId == null || !operationId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("operationId must be a lowercase SHA-256 value");
        }
        if (pass < 1 || totalPasses < 1 || pass > totalPasses || totalPasses > 3) {
            throw new IllegalArgumentException("pass must be within the configured 1-3 pass range");
        }
        if (profile == null) {
            throw new IllegalArgumentException("profile is required");
        }
    }

    public String value() {
        return (autopilot ? "autopilot" : "copilot")
                + ":v1:" + operationId
                + ":" + pass + "/" + totalPasses
                + ":" + profile.name()
                + ":" + (proposeSolutions ? "1" : "0")
                + (proposeProducts ? "1" : "0");
    }

    public CopilotOperationKey forPass(int requestedPass) {
        return new CopilotOperationKey(
                autopilot,
                operationId,
                requestedPass,
                totalPasses,
                profile,
                proposeSolutions,
                proposeProducts);
    }

    public static Optional<CopilotOperationKey> parse(String value) {
        if (value == null) return Optional.empty();
        Matcher matcher = FORMAT.matcher(value.strip());
        if (!matcher.matches()) return Optional.empty();
        int pass = Integer.parseInt(matcher.group(3));
        int total = Integer.parseInt(matcher.group(4));
        if (pass > total) return Optional.empty();
        return Optional.of(new CopilotOperationKey(
                "autopilot".equals(matcher.group(1)),
                matcher.group(2),
                pass,
                total,
                AnalysisAutomationProfile.valueOf(matcher.group(5).toUpperCase(Locale.ROOT)),
                "1".equals(matcher.group(6)),
                "1".equals(matcher.group(7))));
    }
}
