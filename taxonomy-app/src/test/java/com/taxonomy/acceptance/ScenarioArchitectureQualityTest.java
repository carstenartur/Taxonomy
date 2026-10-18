package com.taxonomy.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Handwritten functional witnesses; none is copied from provider playback answers. */
class ScenarioArchitectureQualityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode REFERENCE = reference();
    private static final Map<String, String> CLAUSES = clauses();
    private static final String ASSUMPTION = "Within test design assumption A1; this is a declared design property.";

    record Example(String constraint, String element, String clause, String text) { }

    static Stream<Example> declaredObligations() {
        return Stream.of(
                new Example("C01", "UA-1580", "F1", "Display the published flood warnings, affected areas and water observations in an understandable view for residents and public authorities."),
                new Example("C02", "IP-1116", "F1", "Attach each observation's source and observation time to the information presented to the reader."),
                new Example("C03", "UA-1522", "F2", "Provide administration and operational oversight of the information service."),
                new Example("C04", "CR-1098", "F3", "Retain area subscriptions and user-selected notification channels; route applicable deliveries using those preferences."),
                new Example("C05", "CO-1048", "F3", "Support text notification delivery when text is the subscriber's selected channel."),
                new Example("C06", "CO-1066", "F3", "Provide phone notifications; deliver voice calls only when the user selected the phone channel."),
                new Example("C07", "CR-1037", "F3", "Provide email notifications only for the subscriber's chosen email channel."),
                new Example("C08", "BP-1521", "F4", "Allow entitled users to update their contact details."),
                new Example("C09", "BP-1530", "F4", "Let the user remove subscribed areas and stop treating removed areas as active delivery destinations."),
                new Example("C10", "BP-1530", "F4", "Provide user account deletion and deprovision the deleted account."),
                new Example("C11", "UA-1580", "F4", "Show users that surface-water flooding is outside the service scope."),
                new Example("C12", "BP-1017", "F5", "Consume the latest available observation batch at most every 15 minutes."),
                new Example("C13", "UA-1580", "F5", "Show stale observations as stale, preserving their source and observation time."),
                new Example("C14", "BP-2009", "F5", "Display an unavailable-data indication when observations are unavailable."),
                new Example("C15", "UA-1580", "F5", "Retain the official warning channels alongside the supplementary information view."),
                new Example("C16", "CR-1097", "A1", "Persist notification delivery work in a durable queue."),
                new Example("C17", "CR-1097", "A1", "Retry delivery work idempotently so repeated work does not cause duplicate effective processing."),
                new Example("C18", "CR-1007", "A1", "Protect subscriber details and restrict protected account actions to entitled identities."),
                new Example("C19", "CR-1198", "A1", "Retain an auditable history of delivery attempts and their outcomes."),
                new Example("C20", "UA-1580", "SCOPE", "Describe this as a supplementary information service, not a safety-critical replacement for official alerts."));
    }

    @ParameterizedTest @MethodSource("declaredObligations")
    void acceptsLocallyBoundHandwrittenObligation(Example example) {
        var result = assess(example, example.text(), example.element(), example.clause(), condition(example));
        assertEquals(example.constraint(), result.get("id"));
        assertEquals("PASS", result.get("verdict"));
    }

    @Test void theReferenceAndExecutableObligationsHaveExactlyTheSameTwentyBindings() {
        assertEquals(20, REFERENCE.path("coverageConstraints").size());
        assertEquals(java.util.stream.StreamSupport.stream(REFERENCE.path("coverageConstraints").spliterator(), false)
                        .map(c -> c.path("id").asText()).sorted().toList(),
                declaredObligations().map(Example::constraint).sorted().toList());
    }

    @Test void aGlobalCopyOfTheOriginalCannotSupplyALocalObligation() {
        for (Example example : declaredObligations().toList()) {
            assertRejected(example, REFERENCE.path("requirement").path("text").asText(),
                    example.element(), example.clause(), condition(example));
        }
    }

    @Test void anUnboundElementCannotDischargeThePhoneObligation() {
        Example phone = example("C06");
        assertRejected(phone, phone.text(), "CR-1037", "F3", "");
    }

    @Test void aDifferentClauseCannotDischargeThePhoneObligation() {
        Example phone = example("C06");
        assertRejected(phone, phone.text(), phone.element(), "A1", ASSUMPTION);
    }

    @Test void textAndEmailDoNotCompensateForMissingPhoneSupport() {
        assertRejected(example("C06"), "Deliver text and email notifications according to the user's chosen channels.");
    }

    @Test void aNegatedPhoneCapabilityIsNotADeclarationOfSupport() {
        assertRejected(example("C06"), "Do not support phone notifications even when the user selects the phone channel.");
    }

    @Test void aPhoneCapabilityCannotBeMadeOptionalByTheDeliveryCondition() {
        assertRejected(example("C06"), "Phone notification support is optional; deliver when the user selects the phone channel.");
    }

    @Test void sourceAndObservationTimeMustBothBePresent() {
        assertRejected(example("C02"), "Attach the observation time to the presented information.");
        assertRejected(example("C02"), "Attach the information source to the presented observation.");
    }

    @Test void currentDataOrASilentFallbackDoNotDescribeStalenessOrUnavailability() {
        assertRejected(example("C13"), "Show observations with their source and observation time.");
        assertRejected(example("C14"), "Show the last available observation as the current complete picture.");
    }

    @Test void theCadenceWordingCannotBeSilentlyStrengthened() {
        assertRejected(example("C12"), "Consume the latest available observation batch every 15 minutes with guaranteed end-to-end delivery.");
    }

    @Test void queueAndRetryNamesAloneDoNotDescribeTheirProperties() {
        assertRejected(example("C16"), "Message Queueing Service queues delivery messages.");
        assertRejected(example("C17"), "Retry queued delivery work after a failure.");
    }

    @Test void aGlobalAssumptionQuoteDoesNotMakeAnUnscopedClaimAnA1DesignProperty() {
        Example queue = example("C16");
        assertRejected(queue, queue.text(), queue.element(), "A1", "");
        assertRejected(queue, queue.text(), queue.element(), "F3", ASSUMPTION);
        assertRejected(queue, queue.text(), queue.element(), "A1", "A1 is a verified production fact rather than an assumption.");
    }

    @Test void suspensionDoesNotProvideAccountDeletion() {
        assertRejected(example("C10"), "Suspend the user's account indefinitely and retain it.");
        assertRejected(example("C10"), "Account deletion is not required; deactivate the account instead.");
    }

    @Test void genericLogsDoNotProvideAuditableDeliveryOutcomes() {
        assertRejected(example("C19"), "Retain auditable platform logs.");
    }

    @Test void officialAlertsCannotBeReplacedByTheSupplementaryService() {
        assertRejected(example("C20"), "The supplementary service is a safety-critical replacement for official alerts.");
    }

    private static Map<String, Object> assess(Example example, String text, String element, String clause, String condition) {
        return ScenarioArchitectureQuality.verifyConstraint(constraint(example.constraint()), List.of(
                new ScenarioArchitectureQuality.Witness("ELEMENT", element, element, text,
                        CLAUSES.get(clause), condition)), CLAUSES);
    }

    private static void assertRejected(Example example, String text) {
        assertRejected(example, text, example.element(), example.clause(), condition(example));
    }

    private static void assertRejected(Example example, String text, String element, String clause, String condition) {
        var failure = assertThrows(AssertionError.class, () -> assess(example, text, element, clause, condition));
        assertTrue(failure.getMessage().contains(example.constraint()), failure::getMessage);
    }

    private static String condition(Example example) { return example.clause().equals("A1") ? ASSUMPTION : ""; }
    private static Example example(String id) { return declaredObligations().filter(e -> e.constraint().equals(id)).findFirst().orElseThrow(); }
    private static JsonNode constraint(String id) {
        for (JsonNode item : REFERENCE.path("coverageConstraints")) if (item.path("id").asText().equals(id)) return item;
        throw new AssertionError(id);
    }
    private static JsonNode reference() {
        try (var input = ScenarioArchitectureQualityTest.class.getResourceAsStream("/scenarios/flood-quality-reference.json")) {
            return JSON.readTree(input);
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static Map<String, String> clauses() {
        var result = new LinkedHashMap<String, String>();
        REFERENCE.path("requirement").path("clauses").properties().forEach(e -> result.put(e.getKey(), e.getValue().asText()));
        return result;
    }
}
