package com.nato.taxonomy.dsl.parser;

import com.nato.taxonomy.dsl.model.TaxonomyRootTypes;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DSL-specific tokenizer for indexing architecture commit changes.
 *
 * <p>Extracts structured tokens from DSL text or diffs, categorized into:
 * <ul>
 *   <li><b>Structure tokens</b>: element, relation, mapping, view, evidence, requirement</li>
 *   <li><b>Domain tokens</b>: Capability, Process, Service, Application, InformationProduct, etc.</li>
 *   <li><b>Identifier tokens</b>: CP-1001, BP-1040, REQ-001, etc.</li>
 *   <li><b>Relation tokens</b>: REALIZES, SUPPORTS, USES, PRODUCES, CONSUMES, etc.</li>
 * </ul>
 *
 * <p>The tokenized output is a space-separated string suitable for full-text search indexing.
 */
public class DslTokenizer {

    /** DSL block keywords. */
    private static final Set<String> STRUCTURE_TOKENS = Set.of(
            "element", "relation", "mapping", "view", "evidence",
            "requirement", "meta", "constraint", "decision", "pattern");

    /** Recognized relation types. */
    private static final Set<String> RELATION_TOKENS = Set.of(
            "REALIZES", "SUPPORTS", "CONSUMES", "USES", "FULFILLS",
            "ASSIGNED_TO", "DEPENDS_ON", "PRODUCES", "COMMUNICATES_WITH", "RELATED_TO");

    /** Domain type names (values from TaxonomyRootTypes). */
    private static final Set<String> DOMAIN_TOKENS;

    static {
        Set<String> types = new LinkedHashSet<>(TaxonomyRootTypes.ROOT_TO_TYPE.values());
        types.add("Capability");
        types.add("Process");
        types.add("Service");
        DOMAIN_TOKENS = Collections.unmodifiableSet(types);
    }

    /** Pattern for taxonomy-style identifiers: 2-letter prefix + dash + digits. */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("\\b([A-Z]{2,3}-\\d{1,5})\\b");

    /** Pattern for requirement-style identifiers: REQ-NNN or EV-NNN. */
    private static final Pattern REQ_PATTERN = Pattern.compile("\\b(REQ-\\d{1,5}|EV-\\d{1,5})\\b");

    /**
     * Tokenize DSL text into a space-separated string of categorized tokens.
     *
     * @param dslText the raw DSL text (or diff text)
     * @return space-separated tokens suitable for full-text search indexing
     */
    public String tokenize(String dslText) {
        if (dslText == null || dslText.isBlank()) {
            return "";
        }

        List<String> tokens = new ArrayList<>();

        // Extract identifiers (CP-1001, BP-1040, REQ-001, etc.)
        Matcher idMatcher = IDENTIFIER_PATTERN.matcher(dslText);
        while (idMatcher.find()) {
            tokens.add(idMatcher.group(1));
        }
        Matcher reqMatcher = REQ_PATTERN.matcher(dslText);
        while (reqMatcher.find()) {
            tokens.add(reqMatcher.group(1));
        }

        // Extract word tokens
        String[] words = dslText.split("\\s+");
        for (String word : words) {
            String clean = word.replaceAll("[\"',;(){}\\[\\]]", "").trim();
            if (clean.isEmpty()) continue;

            if (STRUCTURE_TOKENS.contains(clean.toLowerCase())) {
                tokens.add("STRUCT:" + clean.toLowerCase());
            } else if (RELATION_TOKENS.contains(clean)) {
                tokens.add("REL:" + clean);
            } else if (DOMAIN_TOKENS.contains(clean)) {
                tokens.add("DOM:" + clean);
            }
        }

        // Deduplicate while preserving order
        Set<String> seen = new LinkedHashSet<>(tokens);
        return String.join(" ", seen);
    }

    /**
     * Extract all architecture element IDs from DSL text.
     *
     * @param dslText the raw DSL text
     * @return set of element IDs found (e.g., CP-1001, BP-1040)
     */
    public Set<String> extractElementIds(String dslText) {
        if (dslText == null) return Collections.emptySet();
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER_PATTERN.matcher(dslText);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        Matcher reqMatcher = REQ_PATTERN.matcher(dslText);
        while (reqMatcher.find()) {
            ids.add(reqMatcher.group(1));
        }
        return ids;
    }

    /**
     * Extract all relation keys from DSL text.
     * A relation key is "{sourceId} {relationType} {targetId}".
     *
     * @param dslText the raw DSL text
     * @return set of relation keys found
     */
    public Set<String> extractRelationKeys(String dslText) {
        if (dslText == null) return Collections.emptySet();
        Set<String> keys = new LinkedHashSet<>();
        Pattern relPattern = Pattern.compile(
                "^\\s*relation\\s+([A-Z]{2,3}-\\d{1,5})\\s+(\\w+)\\s+([A-Z]{2,3}-\\d{1,5})",
                Pattern.MULTILINE);
        Matcher matcher = relPattern.matcher(dslText);
        while (matcher.find()) {
            keys.add(matcher.group(1) + " " + matcher.group(2) + " " + matcher.group(3));
        }
        return keys;
    }
}
