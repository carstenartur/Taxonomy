package com.taxonomy;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Independent checks over recorded results; never supplies answers to the provider. */
final class RequirementEvidenceReferenceQa {
    private RequirementEvidenceReferenceQa() { }

    static Map<String, Object> evaluate(JsonNode reference, JsonNode scenario, JsonNode analysis) {
        String original = scenario.required("requirement").asText();
        JsonNode assessments = analysis.path("nodeAssessments");
        var failures = new ArrayList<String>();
        var inconclusive = new ArrayList<String>();
        boolean partial = "PARTIAL".equals(analysis.path("status").asText())
                || analysis.at("/analysisCoverage/unknownNodes").asInt() > 0;
        var observedKinds = new LinkedHashSet<String>();
        var statuses = new LinkedHashMap<String, Integer>();
        int claims = 0;
        for (String code : assessments.propertyNames()) {
            for (JsonNode claim : assessments.path(code)) {
                claims++;
                String status = claim.path("evidenceStatus").asText();
                statuses.merge(status, 1, Integer::sum);
                String quote = claim.path("requirementQuote").asText();
                if (!quote.isEmpty() && !original.contains(quote)) failures.add(code + ": quote not in exact requirement");
                if (Set.of("SUPPORTED", "CONFLICT").contains(status) && quote.isBlank())
                    failures.add(code + ": positive/conflict claim has no source quote");
                if (status.equals("UNRESOLVED") && claim.path("question").asText().isBlank())
                    failures.add(code + ": unresolved claim has no concrete question");
                if (status.equals("SUPPORTED") && !claim.path("question").asText().isBlank())
                    failures.add(code + ": supported claim retains an open question");
                claim.path("subjectKinds").forEach(kind -> observedKinds.add(kind.asText()));
                boolean positiveDelivery = status.equals("SUPPORTED")
                        && claim.path("scope").asText().equals("DELIVERY")
                        && claim.path("necessity").asText().equals("REQUIRED");
                if (positiveDelivery && reference.path("unsupportedDirectDeliveryNodes").has(code))
                    failures.add(code + ": unsupported mandatory delivery: "
                            + reference.path("unsupportedDirectDeliveryNodes").path(code).asText());
            }
        }
        if (claims == 0) failures.add("No typed assessments were recorded; absence is not successful semantic QA");
        var mappingChecks = new ArrayList<Map<String, Object>>();
        for (JsonNode group : scenario.path("requiredMappingGroups")) {
            List<String> codes = strings(group.path("ids"));
            List<String> types = strings(group.path("contributionTypes"));
            boolean found = codes.stream().anyMatch(code -> {
                for (JsonNode claim : assessments.path(code)) {
                    if (claim.path("evidenceStatus").asText().equals(group.path("evidenceStatus").asText())
                            && types.contains(claim.path("contributionType").asText())) return true;
                }
                return false;
            });
            mappingChecks.add(Map.of("alternatives", codes, "observed", found));
            if (!found) (partial ? inconclusive : failures).add("Expected authored mapping group not observed: " + codes);
        }
        for (String expected : strings(scenario.path("expectedSubjectKinds"))) {
            if (!observedKinds.contains(expected)) (partial ? inconclusive : failures).add("Expected subject aspect not observed: " + expected);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("case", scenario.required("id").asText());
        result.put("referenceId", reference.required("referenceId").asText());
        result.put("method", "Independent authored mapping checks plus exact-source integrity; not external certification or calibrated accuracy");
        result.put("recordedClaims", claims);
        result.put("statuses", statuses);
        result.put("subjectKinds", observedKinds);
        result.put("mappingChecks", mappingChecks);
        result.put("failures", failures);
        result.put("inconclusiveChecks", inconclusive);
        result.put("analysisStatus", analysis.path("status").asText());
        result.put("coveragePartial", partial);
        result.put("checksPassed", failures.isEmpty() && inconclusive.isEmpty());
        result.put("outcome", !failures.isEmpty() ? "FAILED_CHECKS"
                : partial || !inconclusive.isEmpty() ? "INCONCLUSIVE_COVERAGE" : "PASSED_SCOPED_CHECKS");
        return result;
    }

    private static List<String> strings(JsonNode array) {
        var result = new ArrayList<String>();
        array.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }
}
