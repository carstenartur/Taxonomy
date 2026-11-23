package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.model.AnalysisAutomationProfile;

import java.util.ArrayList;
import java.util.List;
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

    private static final Pattern OPERATION_ID = Pattern.compile("[0-9a-f]{64}");
    private static final List<AnalysisAutomationProfile> V1_PROFILES = List.of(
            AnalysisAutomationProfile.STANDARD, AnalysisAutomationProfile.FULL, AnalysisAutomationProfile.EXHAUSTIVE);
    private static final Pattern FORMAT = Pattern.compile(
            "^(copilot|autopilot):v1:(" + OPERATION_ID.pattern() + "):([1-3])/([1-3]):"
                    + "(STANDARD|FULL|EXHAUSTIVE):([01])([01])$");

    public CopilotOperationKey {
        if (operationId == null || !OPERATION_ID.matcher(operationId).matches()) {
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

    /** All persisted v1 key variants that may identify a pass of this operation. */
    public static List<String> candidatesForOperation(String operationId) {
        if (operationId == null || !OPERATION_ID.matcher(operationId).matches()) return List.of();

        List<String> candidates = new ArrayList<>(144);
        boolean[] flags = {false, true};
        for (boolean autopilot : flags) {
            for (int total = 1; total <= 3; total++) {
                for (int pass = 1; pass <= total; pass++) {
                    for (AnalysisAutomationProfile profile : V1_PROFILES) {
                        for (boolean proposeSolutions : flags) {
                            for (boolean proposeProducts : flags) {
                                candidates.add(new CopilotOperationKey(autopilot, operationId, pass, total,
                                        profile, proposeSolutions, proposeProducts).value());
                            }
                        }
                    }
                }
            }
        }
        return List.copyOf(candidates);
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
