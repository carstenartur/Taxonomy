package com.taxonomy.export;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Display translations for recognized generated diagnostics, never for original reasons or quotes. */
public final class GeneratedDiagnosticText {
    private static final Map<String, String> EXACT = Map.ofEntries(
            Map.entry("Requirement-scoped relation proposals; not accepted into the active architecture. Confidence has not been calibrated.",
                    "Beziehungsvorschläge zur Anforderung wurden noch nicht in die aktive Architektur übernommen. Die Konfidenz wurde nicht kalibriert."),
            Map.entry("No verified required relationships available; no score-product or catalogue-seed fallback was used.",
                    "Es liegen keine verifizierten erforderlichen Beziehungen vor. Es wurden keine Ersatzbeziehungen aus Bewertungsprodukten oder Katalogvorlagen erzeugt."),
            Map.entry("RELATION_SEARCH_PARTIAL: see the analysis report for unassessed work, questions and budget limits.",
                    "RELATION_SEARCH_PARTIAL: Unbewertete Arbeit, offene Fragen und Budgetgrenzen sind im Analysebericht dokumentiert."),
            Map.entry("The underlying relational projection was marked stale when the report was generated.",
                    "Die zugrunde liegende relationale Projektion war bei der Berichterzeugung als veraltet markiert."));
    private static final Map<Pattern, String> FORMATS = formats();

    private GeneratedDiagnosticText() { }

    public static String display(String value, boolean german) {
        if (!german || value == null) return value;
        String exact = EXACT.get(value);
        if (exact != null) return exact;
        for (var format : FORMATS.entrySet()) {
            var match = format.getKey().matcher(value);
            if (match.matches()) return match.replaceFirst(format.getValue());
        }
        return value;
    }

    /** Call only when the source explicitly identifies a deterministic fallback reason. */
    public static String deterministicReason(String value, boolean german) {
        if (!german || value == null) return value;
        return switch (value) {
            case "The node was evaluated, scored 0%, and was therefore not pursued in this path." ->
                    "Der Knoten wurde bewertet, erhielt 0 % und wurde deshalb in diesem Pfad nicht weiter verfolgt.";
            case "No separate AI reason was available for this positive score. Inclusion in the report follows only from the stored value and taxonomy hierarchy." ->
                    "Für diesen positiven Bewertungswert lag keine separate KI-Einzelbegründung vor. Die Aufnahme in den Bericht folgt ausschließlich aus dem gespeicherten Wert und der Taxonomiehierarchie.";
            default -> value;
        };
    }

    private static Map<Pattern, String> formats() {
        var result = new LinkedHashMap<Pattern, String>();
        result.put(Pattern.compile("RELATION_SEARCH_PARTIAL: (\\d+)/(\\d+) evaluation attempts; (\\d+) unfinished search batches\\. (\\d+) unassessed sources; (\\d+) pending and (\\d+) unresolved source/target searches\\.(.*)", Pattern.DOTALL),
                "RELATION_SEARCH_PARTIAL: $1/$2 Bewertungsversuche; $3 nicht abgeschlossene Suchpakete. $4 unbewertete Quellen; $5 ausstehende und $6 ungeklärte Suchen nach Quell-/Zielpaaren.$7");
        result.put(Pattern.compile("RELATION_SEARCH_PARTIAL: (\\d+)/(\\d+) evaluation attempts; (\\d+) unfinished search batches\\.(.*)", Pattern.DOTALL),
                "RELATION_SEARCH_PARTIAL: $1/$2 Bewertungsversuche; $3 nicht abgeschlossene Suchpakete.$4");
        result.put(Pattern.compile("ROOT_SOURCE_UNRESOLVED (BP|BR|CP|CI|CO|CR|IP|UA): identify a concrete required contribution below this catalogue container\\.(.*)", Pattern.DOTALL),
                "ROOT_SOURCE_UNRESOLVED $1: Einen konkreten erforderlichen Beitrag unterhalb dieses Katalogcontainers bestimmen.$2");
        result.put(Pattern.compile("SOURCE_LIMIT: (\\d+) sources remain unassessed\\.(.*)", Pattern.DOTALL),
                "SOURCE_LIMIT: $1 Quellen sind noch unbewertet.$2");
        result.put(Pattern.compile("SOURCE_ONLY: (\\d+) quoted contribution proposals retained without verified required relationships; review their open dependencies\\.(.*)", Pattern.DOTALL),
                "SOURCE_ONLY: $1 zitierte Beitragsvorschläge bleiben ohne verifizierte erforderliche Beziehungen erhalten. Ihre offenen Abhängigkeiten sind zu prüfen.$2");
        result.put(Pattern.compile("GENERIC_RELATION_CONTEXT: (\\d+) automatic relationships used a taxonomy container\\. See retained analysis evidence for context\\.(.*)", Pattern.DOTALL),
                "GENERIC_RELATION_CONTEXT: $1 automatisch ermittelte Beziehungen verwendeten einen Taxonomiecontainer. Der Kontext ist in der gespeicherten Analyseevidenz dokumentiert.$2");
        result.put(Pattern.compile("DECISIONS_PENDING: (\\d+) optional/alternative contexts remain unaccepted in structured evidence; no optional-only edge is included in the required view\\.(.*)", Pattern.DOTALL),
                "DECISIONS_PENDING: $1 optionale oder alternative Kontexte sind in der strukturierten Evidenz noch nicht angenommen. Rein optionale Beziehungen sind nicht in der erforderlichen Sicht enthalten.$2");
        result.put(Pattern.compile("NODE_LIMIT: (\\d+) relations omitted from this view; complete evidence is unchanged in the analysis report\\.(.*)", Pattern.DOTALL),
                "NODE_LIMIT: $1 Beziehungen sind in dieser Sicht ausgelassen. Die vollständige Evidenz bleibt im Analysebericht unverändert.$2");
        result.put(Pattern.compile("NODE_LIMIT: (\\d+) source contribution proposals omitted from this view; full source evidence is retained in the report\\.(.*)", Pattern.DOTALL),
                "NODE_LIMIT: $1 Beitragsvorschläge von Quellen sind in dieser Sicht ausgelassen. Die vollständige Quellevidenz bleibt im Bericht erhalten.$2");
        result.put(Pattern.compile("PARTIAL ANALYSIS / TEILANALYSE: (\\d+) unassessed question\\(s\\); missing assessments are not negative findings\\.(.*)", Pattern.DOTALL),
                "PARTIAL ANALYSIS / TEILANALYSE: Offene Fragen ohne Bewertung: $1. Fehlende Bewertungen sind keine negativen Befunde.$2");
        result.put(Pattern.compile("The analysis status is (PARTIAL|ERROR|SUCCESS|PENDING|RUNNING|CANCELLED|FAILED); the result must not be treated as complete without review\\.(.*)", Pattern.DOTALL),
                "Der Analysezustand lautet $1; das Ergebnis darf nicht ohne Prüfung als vollständig behandelt werden.$2");
        result.put(Pattern.compile("((?:[A-Z]{2}-\\d+ · )?)The product family is relevant, but none of its (\\d+) catalogued products reached the suitability threshold of (\\d+(?:\\.\\d+)?)\\.(.*)", Pattern.DOTALL),
                "$1Die Produktfamilie ist relevant, aber keines ihrer $2 katalogisierten Produkte erreichte die Eignungsschwelle von $3.$4");
        result.put(Pattern.compile("Product coverage gap for (.+) \\((.+), (\\d+(?:\\.\\d+)?)%\\): None of the (\\d+) fully evaluated catalogue products reached the suitability threshold( \\(\\d+(?:\\.\\d+)? %\\))?\\. Candidates: (.*)", Pattern.DOTALL),
                "Produktabdeckungslücke für $1 ($2, $3 %): Keines der $4 vollständig bewerteten Katalogprodukte erreichte den Eignungsschwellenwert$5. Kandidaten: $6");
        return java.util.Collections.unmodifiableMap(result);
    }
}
