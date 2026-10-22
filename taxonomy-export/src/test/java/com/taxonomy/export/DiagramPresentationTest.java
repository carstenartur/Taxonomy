package com.taxonomy.export;

import com.taxonomy.diagram.DiagramRelationMetadata;
import com.taxonomy.dto.RelationOrigin;
import com.taxonomy.dto.RelationSearchModel.*;
import com.taxonomy.model.SeedType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiagramPresentationTest {
    private static final String ORIGINAL = "Original wording, including ä and →. ".repeat(80)
            + "\nCOMPLETE-REASON-END";

    @Test
    void recordedOriginsHaveExplicitEnglishAndGermanLabels() {
        var labels = Map.of(
                RelationOrigin.TAXONOMY_SEED, List.of("Catalogue seed", "Katalogbeziehung"),
                RelationOrigin.PROPAGATED_TRACE, List.of("Propagated trace", "Fortgeführte Herleitung"),
                RelationOrigin.IMPACT_DERIVED, List.of("Derived impact", "Abgeleitete Auswirkung"),
                RelationOrigin.LLM_SUPPORTED, List.of("AI-proposed", "KI-Vorschlag"),
                RelationOrigin.SUGGESTED_CANDIDATE, List.of("AI-proposed", "KI-Vorschlag"));
        labels.forEach((origin, expected) -> {
            var metadata = metadata(origin, null, List.of());
            assertEquals(expected.get(0), DiagramPresentation.relationshipStatus(metadata));
            assertEquals(expected.get(0), DiagramPresentation.relationshipStatus(metadata, false));
            assertEquals(expected.get(1), DiagramPresentation.relationshipStatus(metadata, true));
        });
    }

    @Test
    void absentMetadataAndUnknownOriginRemainExplicitlyUnrecorded() {
        var unknown = metadata(null, null, List.of());
        assertEquals("Origin unrecorded", DiagramPresentation.relationshipStatus(null));
        for (boolean german : List.of(false, true)) {
            String status = german ? "Herkunft nicht aufgezeichnet" : "Origin unrecorded";
            String origin = german ? "Herkunft: nicht aufgezeichnet" : "Origin: unrecorded";
            assertEquals(status, DiagramPresentation.relationshipStatus(null, german));
            assertEquals(status, DiagramPresentation.relationshipStatus(unknown, german));
            assertEquals(List.of("Status: " + status), DiagramPresentation.relationshipDetails(null, german));
            assertEquals(List.of("Status: " + status, origin), DiagramPresentation.relationshipDetails(unknown, german));
        }
        assertEquals(DiagramPresentation.relationshipDetails(unknown, false),
                DiagramPresentation.relationshipDetails(unknown));
    }

    @Test
    void proposedEvidenceOverridesEvenACatalogueOriginWithoutImplyingHumanApproval() {
        for (var origin : RelationOrigin.values()) {
            var scoped = metadata(origin, null, List.of(edge(Direction.OUTGOING, null)));
            var provisional = metadata(origin, "provisional (AI-suggested, not yet confirmed)", List.of());
            for (var metadata : List.of(scoped, provisional)) {
                assertEquals("AI-proposed", DiagramPresentation.relationshipStatus(metadata));
                assertEquals("KI-Vorschlag", DiagramPresentation.relationshipStatus(metadata, true));
            }
        }
    }

    @Test
    void fullRecordedDetailsLocalizeLabelsButPreserveEveryOriginalValue() {
        var decision = new Decision("target", Outcome.VERIFIED, "decision contribution", "decision quote",
                Necessity.ALTERNATIVE, "decision condition", "alternative group", ORIGINAL, "open question");
        var metadata = new DiagramRelationMetadata(42L, RelationOrigin.IMPACT_DERIVED, .75,
                ORIGINAL, "derivation", "presence", SeedType.SOURCE_DERIVED,
                List.of(edge(Direction.OUTGOING, decision)));
        var english = List.of(
                "Status: AI-proposed", "Relation ID: 42", "Origin: IMPACT_DERIVED", "Confidence: 0.75",
                "Seed type: SOURCE_DERIVED", "Included because: " + ORIGINAL,
                "Derivation reason: derivation", "Presence reason: presence",
                "Scoped evidence: source -> target | SUPPORTS | OUTGOING",
                "Contribution: scoped contribution", "Original quote: scoped quote",
                "Contribution condition: scoped condition",
                "Outcome: VERIFIED | Necessity: ALTERNATIVE",
                "Evidence contribution: decision contribution | Quote: decision quote",
                "Condition: decision condition | Alternative group: alternative group",
                "Rationale: " + ORIGINAL + " | Question: open question");
        var german = List.of(
                "Status: KI-Vorschlag", "Beziehungskennung: 42", "Herkunft: IMPACT_DERIVED", "Konfidenz: 0.75",
                "Ausgangstyp: SOURCE_DERIVED", "Aufgenommen weil: " + ORIGINAL,
                "Herleitungsbegründung: derivation", "Begründung der Aufnahme: presence",
                "Anforderungsbezogene Evidenz: source -> target | SUPPORTS | OUTGOING",
                "Beitrag: scoped contribution", "Originalzitat: scoped quote",
                "Bedingung des Beitrags: scoped condition",
                "Ergebnis: VERIFIED | Notwendigkeit: ALTERNATIVE",
                "Evidenzbeitrag: decision contribution | Zitat: decision quote",
                "Bedingung: decision condition | Alternativgruppe: alternative group",
                "Begründung: " + ORIGINAL + " | Frage: open question");
        assertEquals(english, DiagramPresentation.relationshipDetails(metadata));
        assertEquals(english, DiagramPresentation.relationshipDetails(metadata, false));
        assertEquals(german, DiagramPresentation.relationshipDetails(metadata, true));
        assertEquals(ORIGINAL, metadata.includedBecause());
        assertEquals(ORIGINAL, decision.rationale());
        assertThrows(UnsupportedOperationException.class,
                () -> DiagramPresentation.relationshipDetails(metadata, true).add("invented evidence"));
    }

    @Test
    void incomingScopedEvidenceWithoutADecisionKeepsItsDirectionAndDoesNotInventDecisionFields() {
        var metadata = metadata(null, null, List.of(edge(Direction.INCOMING, null)));
        assertEquals(List.of("Status: AI-proposed", "Origin: unrecorded",
                "Scoped evidence: target -> source | SUPPORTS | INCOMING",
                "Contribution: scoped contribution", "Original quote: scoped quote",
                "Contribution condition: scoped condition"), DiagramPresentation.relationshipDetails(metadata, false));
        assertEquals(List.of("Status: KI-Vorschlag", "Herkunft: nicht aufgezeichnet",
                "Anforderungsbezogene Evidenz: target -> source | SUPPORTS | INCOMING",
                "Beitrag: scoped contribution", "Originalzitat: scoped quote",
                "Bedingung des Beitrags: scoped condition"), DiagramPresentation.relationshipDetails(metadata, true));
    }

    @Test
    void supplementalReasonsRetainFullTextAndHaveIndependentLocalizedFieldLabels() {
        String review = "  Recorded review\n" + ORIGINAL + "  ";
        var fields = Map.of("presenceReason", ORIGINAL, "decisionComment", review, "other", "not evidence");
        for (boolean german : List.of(false, true)) {
            String presenceLabel = german ? "Gespeicherte Auswahlbegründung: " : "Recorded selection reason: ";
            String reviewLabel = german ? "Gespeicherter Prüfkommentar: " : "Recorded review comment: ";
            var expected = List.of(presenceLabel + ORIGINAL, reviewLabel + review);
            assertEquals(expected, DiagramPresentation.relationshipEvidenceDetails(fields, german));
            assertEquals(List.of(presenceLabel + ORIGINAL),
                    DiagramPresentation.relationshipEvidenceDetails(Map.of("presenceReason", ORIGINAL), german));
            assertEquals(List.of(reviewLabel + review),
                    DiagramPresentation.relationshipEvidenceDetails(Map.of("decisionComment", review), german));
            assertThrows(UnsupportedOperationException.class,
                    () -> DiagramPresentation.relationshipEvidenceDetails(fields, german).clear());
        }
        assertEquals(ORIGINAL, fields.get("presenceReason"));
        assertEquals(review, fields.get("decisionComment"));
    }

    @Test
    void absentAndBlankSupplementalFieldsDoNotCreateSpuriousEvidenceLines() {
        for (boolean german : List.of(false, true)) {
            assertEquals(List.of(), DiagramPresentation.relationshipEvidenceDetails(null, german));
            assertEquals(List.of(), DiagramPresentation.relationshipEvidenceDetails(Map.of(), german));
            assertEquals(List.of(), DiagramPresentation.relationshipEvidenceDetails(Map.of("other", "ignored"), german));
            var fields = new HashMap<String, String>();
            fields.put("presenceReason", null);
            fields.put("decisionComment", null);
            assertEquals(List.of(), DiagramPresentation.relationshipEvidenceDetails(fields, german));
            for (String blank : List.of("", " ", "\t\r\n")) {
                fields.put("presenceReason", blank);
                fields.put("decisionComment", blank);
                assertEquals(List.of(), DiagramPresentation.relationshipEvidenceDetails(fields, german));
                fields.put("presenceReason", ORIGINAL);
                assertEquals(List.of((german ? "Gespeicherte Auswahlbegründung: " : "Recorded selection reason: ") + ORIGINAL),
                        DiagramPresentation.relationshipEvidenceDetails(fields, german));
                fields.put("presenceReason", blank);
                fields.put("decisionComment", ORIGINAL);
                assertEquals(List.of((german ? "Gespeicherter Prüfkommentar: " : "Recorded review comment: ") + ORIGINAL),
                        DiagramPresentation.relationshipEvidenceDetails(fields, german));
            }
        }
    }

    private static DiagramRelationMetadata metadata(RelationOrigin origin, String includedBecause, List<Edge> evidence) {
        return new DiagramRelationMetadata(null, origin, null, includedBecause, null, null, null, evidence);
    }

    private static Edge edge(Direction direction, Decision decision) {
        var source = new Node("source", "CP", "Authored source", "Description", false);
        var target = new Node("target", "BP", "Authored target", "Description", false);
        return new Edge(new Contribution(source, "scoped contribution", "scoped quote", "scoped condition"),
                target, "SUPPORTS", direction, decision);
    }
}
