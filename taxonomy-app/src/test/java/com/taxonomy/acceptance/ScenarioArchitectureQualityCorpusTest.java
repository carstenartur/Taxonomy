package com.taxonomy.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Authored observations and mutations, never generated from the independent expected reference. */
class ScenarioArchitectureQualityCorpusTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @Test void theSixDifferentCasesDoNotTurnOpenEvidenceIntoSuccessfulQuality() throws Exception {
        assertEquals("PASS", assess("clear-read-only").path("verdict").asText());
        assertEquals("PASS", assess("one-notification-channel").path("verdict").asText());
        assertEquals("INCONCLUSIVE", assess("unknown-observation-source").path("verdict").asText());
        assertEquals("INCONCLUSIVE", assess("contradictory-retention").path("verdict").asText());
        assertEquals("PASS", assess("rename-existing-view").path("verdict").asText());
        assertEquals("PASS", assess("already-satisfied").path("verdict").asText());
    }

    @Test void eitherPermittedChannelDischargesOneObligation() throws Exception {
        var email = observation("one-notification-channel");
        ((ObjectNode) email.path("elements").get(1)).put("id", "CR-1037");
        ((ObjectNode) email.path("relations").get(0)).put("target", "CR-1037");
        var result = assess(email);
        assertEquals("PASS", result.path("verdict").asText());
        assertEquals(1, result.path("obligations").size());
        assertEquals("SATISFIED", result.at("/obligations/0/status").asText());
        assertTrue(result.path("missingRequiredRelations").isEmpty());
        assertTrue(result.at("/relations/accepted/recall").isNull());
    }

    @Test void noChannelViolatesTheUnionCardinalityOnlyOnce() throws Exception {
        var candidate = observation("one-notification-channel");
        candidate.putArray("relations");
        var result = assess(candidate);
        assertEquals("FAIL", result.path("verdict").asText());
        assertEquals(1, result.path("missingObligations").size());
        assertEquals("choose-one-channel", result.at("/missingObligations/0/ruleId").asText());
        assertTrue(result.path("unjustifiedRelations").isEmpty());
    }

    @Test void aProposedChannelCannotStandInForAcceptedModelEvidence() throws Exception {
        var candidate = observation("one-notification-channel");
        ((ObjectNode) candidate.path("relations").get(0)).put("status", "proposed");
        var result = assess(candidate);
        assertEquals("FAIL", result.path("verdict").asText());
        assertEquals(1, result.path("missingObligations").size());
        assertEquals(0, result.at("/obligations/0/observedCount").asInt());
    }

    @Test void choosingBothChannelsViolatesTheExplicitUpperBound() throws Exception {
        var candidate = observation("one-notification-channel");
        ((ArrayNode) candidate.path("elements")).addObject().put("id", "CR-1037").put("root", "CR").putObject("properties");
        relation(candidate, "BP-1535", "USES", "CR-1037");
        var result = assess(candidate);
        assertEquals("FAIL", result.path("verdict").asText());
        assertEquals(1, result.path("excessObligations").size());
        assertTrue(result.path("missingObligations").isEmpty());
        assertTrue(result.path("unjustifiedRelations").isEmpty());
    }

    @Test void reversingAReadProducesSeparateMissingAndUnjustifiedDiagnostics() throws Exception {
        var candidate = observation("clear-read-only");
        ((ObjectNode) candidate.path("relations").get(0)).put("source", "IP-1116").put("target", "UA-1580");
        var result = assess(candidate);
        assertEquals("FAIL", result.path("verdict").asText());
        assertEquals(1, result.path("missingRequiredRelations").size());
        assertEquals("UA-1580", result.at("/missingRequiredRelations/0/source").asText());
        assertEquals(1, result.path("unjustifiedRelations").size());
        assertEquals("IP-1116", result.at("/unjustifiedRelations/0/source").asText());
    }

    @Test void aCandidateCannotDeclareIncompleteReferenceEvidenceComplete() throws Exception {
        var candidate = observation("unknown-observation-source");
        candidate.put("state", "SUCCESS").put("evidenceCompleteness", "COMPLETE");
        var result = assess(candidate);
        assertEquals("INCONCLUSIVE", result.path("verdict").asText());
        assertEquals("INCOMPLETE", result.path("evidenceCompleteness").asText());
        assertEquals(1, result.path("inconclusiveObligations").size());
        assertTrue(result.path("missingObligations").isEmpty());
        assertTrue(result.at("/relations/accepted/recall").isNull());
        assertTrue(result.at("/relations/accepted/precision").isNull());
        assertEquals(1, result.path("openQuestions").size());
    }

    @Test void anInterruptedClearCaseHasUnobservedRelationsWithoutProvingARequiredGap() throws Exception {
        var candidate = observation("clear-read-only");
        candidate.put("state", "PARTIAL").put("evidenceCompleteness", "INCOMPLETE").putArray("relations");
        var result = assess(candidate);
        assertEquals("INCONCLUSIVE", result.path("verdict").asText());
        assertTrue(result.path("missingRequiredRelations").isEmpty());
        assertEquals(1, result.path("notObservedRequiredRelations").size());
        assertTrue(result.at("/relations/accepted/recall").isNull());
    }

    @Test void anUnresolvedProductIdentityCannotBeSilentlyPromotedToAccepted() throws Exception {
        var candidate = observation("unknown-observation-source");
        ((ArrayNode) candidate.path("elements")).addObject().put("id", "IP-1116").put("root", "IP").putObject("properties");
        relation(candidate, "UA-1580", "CONSUMES", "IP-1116");
        var result = assess(candidate);
        assertEquals("FAIL", result.path("verdict").asText());
        assertEquals(1, result.path("unsupportedAcceptedRelations").size());
        assertTrue(result.path("unjustifiedRelations").isEmpty());
        assertEquals(1, result.path("openQuestions").size());
    }

    @Test void droppingTheContradictionIsNotSuccessfulHandlingOfUncertainty() throws Exception {
        var candidate = observation("contradictory-retention");
        candidate.putArray("questions");
        var result = assess(candidate);
        assertEquals("FAIL", result.path("verdict").asText());
        assertEquals(List.of("retention-conflict"), JSON.convertValue(result.path("unacknowledgedQuestions"), List.class));
        assertEquals(1, result.path("openQuestions").size());
    }

    @Test void arbitrarilyChoosingOneContradictoryPolicyRemainsAnError() throws Exception {
        var candidate = observation("contradictory-retention");
        ((ObjectNode) candidate.at("/elements/0/properties")).put("retention", "30 days");
        var result = assess(candidate);
        assertEquals("FAIL", result.path("verdict").asText());
        assertEquals("retention", result.at("/propertyMismatches/0/property").asText());
        assertEquals("UNRESOLVED", result.at("/propertyMismatches/0/expected").asText());
    }

    @Test void replacingAnExistingIdentityIsMissingOriginalAndUnjustifiedReplacement() throws Exception {
        var candidate = observation("rename-existing-view");
        ((ObjectNode) candidate.path("elements").get(0)).put("id", "UA-1580-copy");
        ((ObjectNode) candidate.path("relations").get(0)).put("source", "UA-1580-copy");
        var result = assess(candidate);
        assertEquals("FAIL", result.path("verdict").asText());
        assertEquals(List.of("UA-1580"), JSON.convertValue(result.path("missingElements"), List.class));
        assertEquals(List.of("UA-1580-copy"), JSON.convertValue(result.path("unjustifiedElements"), List.class));
    }

    @Test void renameMustRetainAnUnrelatedExistingProperty() throws Exception {
        var candidate = observation("rename-existing-view");
        ((ObjectNode) candidate.at("/elements/0/properties")).remove("sourceLabel");
        var result = assess(candidate);
        assertEquals("FAIL", result.path("verdict").asText());
        assertEquals("sourceLabel", result.at("/propertyMismatches/0/property").asText());
        assertTrue(result.at("/propertyMismatches/0/actual").isNull());
    }

    @Test void anExtraServiceFailsDespitePerfectRequiredRelationRecall() throws Exception {
        var candidate = observation("already-satisfied");
        ((ArrayNode) candidate.path("elements")).addObject().put("id", "CR-1037").put("root", "CR").putObject("properties");
        relation(candidate, "UA-1580", "USES", "CR-1037");
        var result = assess(candidate);
        assertEquals("FAIL", result.path("verdict").asText());
        assertEquals(1.0, result.at("/relations/accepted/recall").asDouble());
        assertEquals(List.of("CR-1037"), JSON.convertValue(result.path("unjustifiedElements"), List.class));
        assertEquals(1, result.path("unjustifiedRelations").size());
    }

    @Test void missingFieldsAndDuplicateIdentitiesCannotBecomeEmptySuccessfulModels() throws Exception {
        var missing = observation("already-satisfied");
        missing.remove("relations");
        assertThrows(IllegalArgumentException.class, () -> assess(missing));
        var duplicate = observation("clear-read-only");
        ((ArrayNode) duplicate.path("elements")).add(duplicate.path("elements").get(0));
        assertThrows(IllegalArgumentException.class, () -> assess(duplicate));
        var relationDuplicate = observation("clear-read-only");
        ((ArrayNode) relationDuplicate.path("relations")).add(relationDuplicate.path("relations").get(0));
        assertThrows(IllegalArgumentException.class, () -> assess(relationDuplicate));
    }

    @Test void wrongRootOrDanglingEndpointCannotDischargeAnObligation() throws Exception {
        var root = observation("one-notification-channel");
        ((ObjectNode) root.path("elements").get(1)).put("root", "IP");
        var result = assess(root);
        assertEquals("FAIL", result.path("verdict").asText());
        assertEquals("root", result.at("/propertyMismatches/0/property").asText());
        assertEquals(1, result.path("missingObligations").size());
        var dangling = observation("clear-read-only");
        ((ArrayNode) dangling.path("elements")).remove(1);
        assertEquals("FAIL", assess(dangling).path("verdict").asText());
        assertEquals(1, assess(dangling).path("danglingRelations").size());
    }

    @Test void reportKeepsMissingCasesNotRunAndUnmeasuredCostsNull() throws Exception {
        var input = observations();
        ((ArrayNode) input.path("observations")).remove(0);
        Path source = temporary.resolve("observations.json"), report = temporary.resolve("quality.json");
        Files.writeString(source, JSON.writeValueAsString(input));
        var result = JSON.valueToTree(ScenarioArchitectureQuality.writeCorpus(source, report));
        assertEquals("NOT_RUN", result.at("/cases/0/verdict").asText());
        assertEquals("NOT_MEASURED", result.at("/measurements/modelCost/status").asText());
        assertTrue(result.at("/measurements/modelCost/value").isNull());
        assertTrue(result.at("/measurements/modelRuntime/value").isNull());
        assertTrue(result.at("/measurements/humanCorrectionEffort/value").isNull());
        assertEquals("architecture-quality-1.0.0", result.path("referenceVersion").asText());
        assertTrue(result.path("referenceSha256").asText().matches("[0-9a-f]{64}"));
        assertTrue(result.path("observationsSha256").asText().matches("[0-9a-f]{64}"));
        assertEquals("6b19743eff1487a76ea3e5b788d90831ba1705da31790cf58f2d69a979b14130", result.path("catalogueSha256").asText());
        assertEquals("/data/C3_Taxonomy_Catalogue_25AUG2025.xlsx", result.path("catalogueResource").asText());
        assertEquals("AUTHORED_OBSERVATIONS_NOT_MODEL_OUTPUT", result.path("evidenceKind").asText());
        byte[] before = Files.readAllBytes(report);
        ScenarioArchitectureQuality.writeCorpus(source, report);
        assertArrayEquals(before, Files.readAllBytes(report));
    }

    @Test void reportsRejectUnknownOrRepeatedCasesInsteadOfIgnoringThem() throws Exception {
        var input = observations();
        ((ObjectNode) input.path("observations").get(0)).put("caseId", "unknown");
        Path source = temporary.resolve("observations.json"), report = temporary.resolve("quality.json");
        Files.writeString(source, JSON.writeValueAsString(input));
        assertThrows(IllegalArgumentException.class, () -> ScenarioArchitectureQuality.writeCorpus(source, report));
        var duplicated = observations();
        ((ArrayNode) duplicated.path("observations")).add(duplicated.path("observations").get(0));
        Files.writeString(source, JSON.writeValueAsString(duplicated));
        assertThrows(IllegalArgumentException.class, () -> ScenarioArchitectureQuality.writeCorpus(source, report));
        assertFalse(Files.exists(report));
    }

    @Test void anOverflowedSchemaNumberCannotMasqueradeAsVersionOne() throws Exception {
        var input = observations();
        input.put("schemaVersion", 4_294_967_297L);
        Path source = temporary.resolve("observations.json"), report = temporary.resolve("quality.json");
        Files.writeString(source, JSON.writeValueAsString(input));
        assertThrows(IllegalArgumentException.class, () -> ScenarioArchitectureQuality.writeCorpus(source, report));
        assertFalse(Files.exists(report));
    }

    @Test void recordedImportsRemainUnverifiedAndCannotSupplyTheirOwnMeasurementClaims() throws Exception {
        var input = observations();
        input.put("evidenceKind", "RECORDED_OUTPUT_WITHOUT_RUN_VERIFICATION");
        input.putObject("measurements").putObject("modelCost").put("status", "MEASURED").put("value", 0);
        Path source = temporary.resolve("observations.json"), report = temporary.resolve("quality.json");
        Files.writeString(source, JSON.writeValueAsString(input));
        var result = JSON.valueToTree(ScenarioArchitectureQuality.writeCorpus(source, report));
        assertEquals("RECORDED_OUTPUT_WITHOUT_RUN_VERIFICATION", result.path("evidenceKind").asText());
        assertEquals("NOT_MEASURED", result.at("/measurements/modelCost/status").asText());
        assertTrue(result.at("/measurements/modelCost/value").isNull());
        input.put("evidenceKind", "CERTIFIED_LIVE_MODEL_BENCHMARK");
        Files.writeString(source, JSON.writeValueAsString(input));
        assertThrows(IllegalArgumentException.class, () -> ScenarioArchitectureQuality.writeCorpus(source, report));
        assertFalse(Files.exists(report));
    }

    @Test void writesTheMavenOwnedCorpusReceipt() throws Exception {
        String supplied = System.getProperty("taxonomy.quality.observations", "");
        Path source = supplied.isBlank() ? temporary.resolve("authored-observations.json") : Path.of(supplied);
        if (supplied.isBlank()) Files.writeString(source, JSON.writeValueAsString(observations()));
        var report = JSON.valueToTree(ScenarioArchitectureQuality.writeCorpus(source,
                Path.of("target", "architecture-quality", "corpus.json")));
        assertEquals(6, report.path("cases").size());
        if (supplied.isBlank()) {
            assertEquals(4, report.at("/outcomes/PASS").asInt());
            assertEquals(2, report.at("/outcomes/INCONCLUSIVE").asInt());
            assertEquals(0, report.at("/outcomes/FAIL").asInt());
        }
        // Successful receipt production is not an assertion that imported model outputs passed.
        assertEquals("NOT_MEASURED", report.at("/measurements/modelCost/status").asText());
    }

    @Test void referenceIdentitiesExistInTheCheckedInCatalogueUnderTheirActualRoots() throws Exception {
        Map<String, String> roots = new LinkedHashMap<>();
        try (var source = getClass().getResourceAsStream("/data/C3_Taxonomy_Catalogue_25AUG2025.xlsx");
             var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(source)) {
            for (var sheet : Map.of("Business Processes", "BP", "Core Services", "CR",
                    "User Applications", "UA", "Information Products", "IP").entrySet()) {
                for (var row : workbook.getSheet(sheet.getKey())) {
                    var cell = row.getCell(0);
                    if (cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING)
                        roots.put(cell.getStringCellValue().strip(), sheet.getValue());
                }
            }
        }
        try (var source = getClass().getResourceAsStream("/scenarios/architecture-quality-v1.json")) {
            for (var reference : JSON.readTree(source).path("cases")) {
                for (String section : List.of("requiredElements", "allowedElements", "initialElements")) {
                    for (var element : reference.path(section).properties())
                        assertEquals(element.getValue().path("root").asText(), roots.get(element.getKey()),
                                reference.path("id").asText() + " has an unknown or wrongly rooted catalogue code " + element.getKey());
                }
            }
        }
    }

    private static ObjectNode observations() throws Exception {
        try (var source = ScenarioArchitectureQualityCorpusTest.class.getResourceAsStream("/scenarios/architecture-quality-observations-v1.json")) {
            assertNotNull(source);
            return (ObjectNode) JSON.readTree(source);
        }
    }
    private static ObjectNode observation(String id) throws Exception {
        for (var value : observations().path("observations")) if (id.equals(value.path("caseId").asText())) return (ObjectNode) value;
        throw new AssertionError("Unknown authored observation: " + id);
    }
    private static JsonNode assess(String id) throws Exception { return assess(observation(id)); }
    private static JsonNode assess(ObjectNode value) {
        return JSON.valueToTree(ScenarioArchitectureQuality.evaluateCorpusCase(value.path("caseId").asText(), value));
    }
    private static void relation(ObjectNode candidate, String source, String type, String target) {
        ((ArrayNode) candidate.path("relations")).addObject().put("source", source).put("type", type).put("target", target).put("status", "accepted");
    }
}
