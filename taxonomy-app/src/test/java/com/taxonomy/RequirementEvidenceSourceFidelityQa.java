package com.taxonomy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Post-run checks for this frozen public case, separate from mapping and software-contract QA. */
final class RequirementEvidenceSourceFidelityQa {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REFERENCE_ID = "altscherbitz-backup-2026-v1";
    private static final Pattern QUANTITY = Pattern.compile(
            "(?iu)\\b(?:\\d+(?:[,.]\\d+)?|ein(?:e|en|em|er|es)?|zwei|drei|vier|fünf|zehn|dreißig)\\s*"
                    + "(?:Minuten?|Stunden?|Tage[n]?|Wochen?|Monate[n]?|Jahre[n]?)\\b|\\b(?:täglich|stündlich|wöchentlich|monatlich)\\b");
    private static final Pattern DENIAL = Pattern.compile("(?iu)\\b(?:nicht|keine?|offen|unbekannt|unspecified|unknown|not)\\b");
    private static final Pattern WRONG_SITE = Pattern.compile(
            "(?iu)(?:(?:nur|ausschließlich).{0,65}(?:ersten|gleichen|einzigen|bestehenden)|dritten)\\s+Standort");

    private RequirementEvidenceSourceFidelityQa() { }

    static Map<String, Object> evaluate(JsonNode reference, JsonNode scenario, JsonNode analysis, JsonNode review) {
        if (!REFERENCE_ID.equals(reference.path("referenceId").asText()))
            throw new IllegalArgumentException("These checks apply only to the frozen Altscherbitz reference");
        String original = scenario.required("requirement").asText();
        var claims = claims(analysis);
        var findings = new ArrayList<Map<String, Object>>();
        for (var entry : claims.entrySet()) {
            String key = entry.getKey();
            JsonNode claim = entry.getValue();
            String quote = claim.path("requirementQuote").asText();
            if (!quote.isEmpty() && !original.contains(quote)) add(findings, key, "QUOTE_CHANGED", "Quote is not in this requirement version");
            if (!claim.path("evidenceStatus").asText().equals("SUPPORTED")) continue;
            String rationale = claim.path("rationale").asText();
            String condition = claim.path("condition").asText();
            // Only simple positive assertions are automated. Denials, hypotheses and wider meaning still need review.
            for (String sentence : (rationale + ". " + condition).split("[.!?;]")) {
                if (DENIAL.matcher(sentence).find()) continue;
                var matcher = QUANTITY.matcher(sentence);
                while (matcher.find()) if (!original.toLowerCase(Locale.ROOT).contains(matcher.group().toLowerCase(Locale.ROOT)))
                    add(findings, key, "UNSUPPORTED_QUANTITY", "Interpretation adds an unprovided temporal quantity: " + matcher.group());
            }
            if (quote.contains("Die bestehende Umgebung nutzt") && claim.path("scope").asText().equals("DELIVERY"))
                add(findings, key, "EXISTING_AS_DELIVERY", "The existing-environment passage does not establish new delivery");
            if (quote.contains("zweiten Standort") && WRONG_SITE.matcher(rationale).find())
                add(findings, key, "SITE_CHANGED", "Interpretation replaces the explicit second-site obligation");
            if (quote.toLowerCase(Locale.ROOT).contains("optimier") && rationale.toLowerCase(Locale.ROOT).contains("optimier")
                    && quote.contains("bei Bedarf") && quote.contains("Abstimmung")) {
                String lower = condition.toLowerCase(Locale.ROOT);
                if (!(lower.contains("bedarf") || lower.contains("need"))
                        || !(lower.contains("abstimm") || lower.contains("consult") || lower.contains("agreement")))
                    add(findings, key, "OPTIMIZATION_CONDITION_LOST", "Optimization needs both need and consultation with the buyer");
            }
            if (quote.contains("noch offen") && claim.path("evidenceBasis").asText().equals("EXPLICIT")
                    && claim.path("necessity").asText().equals("REQUIRED") && claim.path("scope").asText().equals("DELIVERY"))
                add(findings, key, "OPEN_DETAIL_PROMOTED", "An explicitly open detail was promoted to explicit mandatory delivery");
        }
        var reviewProblems = new ArrayList<String>();
        var judgements = new ArrayList<Map<String, Object>>();
        if (review == null || review.isNull()) reviewProblems.add("No recorded semantic review");
        else {
            if (!reference.path("referenceId").asText().equals(review.path("referenceId").asText())
                    || !scenario.path("id").asText().equals(review.path("case").asText())
                    || !hash(original).equals(review.path("requirementSha256").asText()))
                reviewProblems.add("Review source identity does not match");
            var reviewed = new java.util.HashSet<String>();
            for (JsonNode decision : review.path("claims")) {
                String key = decision.path("key").asText();
                JsonNode claim = claims.get(key);
                if (!reviewed.add(key) || claim == null || !hash(JSON.writeValueAsString(claim)).equals(decision.path("claimSha256").asText())) {
                    reviewProblems.add("Duplicate, unknown or changed claim: " + key);
                    continue;
                }
                String verdict = decision.path("verdict").asText();
                String rationale = decision.path("rationale").asText();
                if (!Set.of("ACCEPTED", "QUALIFIED", "ISSUE").contains(verdict) || rationale.isBlank())
                    reviewProblems.add("Incomplete judgement: " + key);
                else judgements.add(Map.of("key", key, "verdict", verdict, "rationale", rationale));
            }
            if (!reviewed.equals(claims.keySet())) reviewProblems.add("Review does not cover every recorded claim exactly once");
        }
        if (claims.isEmpty()) reviewProblems.add("There are no recorded claims to review");
        boolean complete = reviewProblems.isEmpty();
        boolean issue = judgements.stream().anyMatch(j -> j.get("verdict").equals("ISSUE"));
        boolean qualified = judgements.stream().anyMatch(j -> j.get("verdict").equals("QUALIFIED"));
        boolean partial = "PARTIAL".equals(analysis.path("status").asText()) || analysis.at("/analysisCoverage/unknownNodes").asInt() > 0;
        String outcome = !findings.isEmpty() ? "FAILED_SCOPED_CHECKS" : !complete ? "UNREVIEWED"
                : issue ? "FAILED_SEMANTIC_REVIEW" : partial ? "INCONCLUSIVE_COVERAGE"
                : qualified ? "REVIEWED_WITH_QUALIFICATIONS" : "PASSED_REVIEWED_SCOPE";
        var result = new LinkedHashMap<String, Object>();
        result.put("referenceId", REFERENCE_ID);
        result.put("case", scenario.path("id").asText());
        result.put("ruleSet", "altscherbitz-source-fidelity-v1");
        result.put("introducedAfterInitialInference", true);
        result.put("method", "Bounded case-specific checks plus explicit claim-bound semantic review; not a universal entailment checker or externally certified mapping");
        result.put("recordedClaims", claims.size());
        result.put("deterministicFindings", findings);
        result.put("reviewStatus", complete ? "COMPLETE" : review == null || review.isNull() ? "MISSING" : "INCOMPLETE_OR_STALE");
        result.put("reviewProblems", reviewProblems);
        result.put("judgements", judgements);
        result.put("coveragePartial", partial);
        result.put("outcome", outcome);
        return result;
    }

    /** Generates unsigned review inputs. PENDING never constitutes a completed review. */
    static ObjectNode reviewTemplate(JsonNode reference, JsonNode scenario, JsonNode analysis) {
        var result = JSON.createObjectNode().put("referenceId", reference.path("referenceId").asText())
                .put("case", scenario.path("id").asText()).put("requirementSha256", hash(scenario.required("requirement").asText()));
        var rows = result.putArray("claims");
        claims(analysis).forEach((key, claim) -> rows.addObject().put("key", key)
                .put("claimSha256", hash(JSON.writeValueAsString(claim))).put("verdict", "PENDING").put("rationale", ""));
        return result;
    }

    private static Map<String, JsonNode> claims(JsonNode analysis) {
        var result = new LinkedHashMap<String, JsonNode>();
        for (String code : analysis.path("nodeAssessments").propertyNames()) {
            int index = 0;
            for (JsonNode claim : analysis.path("nodeAssessments").path(code)) result.put(code + "#" + ++index, claim);
        }
        return result;
    }

    private static void add(List<Map<String, Object>> findings, String key, String code, String detail) {
        findings.add(Map.of("claim", key, "code", code, "detail", detail));
    }

    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
