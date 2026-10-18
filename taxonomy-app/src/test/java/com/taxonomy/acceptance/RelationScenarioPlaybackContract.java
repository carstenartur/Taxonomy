package com.taxonomy.acceptance;

import com.taxonomy.analysis.relations.RelationSearchProtocol;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static com.taxonomy.dto.RelationSearchModel.*;

/** Executes the real JSON protocol against the authored scenario response corpus. */
public final class RelationScenarioPlaybackContract {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Node APPLICATION = new Node("UA-1580", "UA", "Hydrographic Applications", "", false);
    private static final Node ROOT = new Node("IP", "IP", "Information Products", "", true);
    private static final Node PICTURE = new Node("IP-1116", "IP", "Recognized Environmental Pictures", "", false);

    public static void main(String[] args) throws Exception {
        int passed = 0;
        List<String> failures = new ArrayList<>();
        for (var method : RelationScenarioPlaybackContract.class.getDeclaredMethods()) {
            if (!method.getName().startsWith("test")) { continue; }
            try { method.invoke(null); passed++; }
            catch (ReflectiveOperationException failure) { failures.add(method.getName() + ": " + failure.getCause()); }
        }
        failures.forEach(System.err::println);
        System.out.println("Scenario relation playback: " + passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) { throw new AssertionError(failures.toString()); }
    }

    public static void testExtractsQuotedLeafContributionButNotItsAncestor() throws Exception {
        var playback = ScenarioLlmPlayback.flood();
        String original = original(playback);
        var selected = protocol(playback).contributions(original, List.of(APPLICATION,
                new Node("UA-1000", "UA", "User Applications", "", false)));
        var leaf = selected.stream().filter(s -> s.node().id().equals(APPLICATION.id())).findFirst().orElseThrow();
        var ancestor = selected.stream().filter(s -> s.node().id().equals("UA-1000")).findFirst().orElseThrow();
        check(leaf.contributions().size() == 1, "The concrete application has one scoped contribution");
        check(original.contains(leaf.contributions().getFirst().quote()), "The quote must occur in the unchanged original");
        check(ancestor.contributions().isEmpty(), "An ancestor is a navigation context, not another required application");
    }

    public static void testNavigationAndSeparateVerificationPreserveApplicationReadSemantics() throws Exception {
        var playback = ScenarioLlmPlayback.flood();
        var protocol = protocol(playback);
        String original = original(playback);
        var contribution = protocol.contributions(original, List.of(APPLICATION)).getFirst().contributions().getFirst();
        var root = protocol.evaluate(new Query(original, contribution, "CONSUMES", Direction.OUTGOING,
                Phase.NAVIGATE, List.of(ROOT), null)).getFirst();
        check(root.outcome() == Outcome.DESCEND, "A category is searched, never turned into an information endpoint");
        var proposal = protocol.evaluate(new Query(original, contribution, "CONSUMES", Direction.OUTGOING,
                Phase.NAVIGATE, List.of(PICTURE), null)).getFirst();
        check(proposal.outcome() == Outcome.MATCH && proposal.necessity() == Necessity.REQUIRED, "The application reads the picture");
        var checked = protocol.evaluate(new Query(original, contribution, "CONSUMES", Direction.OUTGOING,
                Phase.VERIFY, List.of(PICTURE), proposal)).getFirst();
        check(checked.outcome() == Outcome.VERIFIED, "The concrete claim must be verified in a separate call");
        check(checked.quote().equals(proposal.quote()) && checked.contribution().equals(proposal.contribution()), "Verification preserves the exact claim");
        // The real relation plan never offers an information product consuming an
        // application. An unauthored reversed prompt must fail, not invent an answer.
        expectFailure(() -> protocol.evaluate(new Query(original, contribution, "CONSUMES", Direction.INCOMING,
                Phase.NAVIGATE, List.of(PICTURE), null)));
        var write = protocol.evaluate(new Query(original, contribution, "PRODUCES", Direction.OUTGOING,
                Phase.NAVIGATE, List.of(PICTURE), null)).getFirst();
        check(write.outcome() == Outcome.REJECT, "Reading must not manufacture write access");
    }

    public static void testVerificationDoesNotBlindlyEchoAChangedProposal() throws Exception {
        var playback = ScenarioLlmPlayback.flood();
        var protocol = protocol(playback);
        String original = original(playback);
        var contribution = protocol.contributions(original, List.of(APPLICATION)).getFirst().contributions().getFirst();
        var proposal = protocol.evaluate(new Query(original, contribution, "CONSUMES", Direction.OUTGOING,
                Phase.NAVIGATE, List.of(PICTURE), null)).getFirst();
        var changed = new Decision(proposal.targetId(), Outcome.MATCH, "Invented unrelated obligation", proposal.quote(),
                proposal.necessity(), proposal.condition(), proposal.alternativeGroup(), proposal.rationale(), "");
        var result = protocol.evaluate(new Query(original, contribution, "CONSUMES", Direction.OUTGOING,
                Phase.VERIFY, List.of(PICTURE), changed)).getFirst();
        check(result.outcome() == Outcome.REJECT, "The corpus must recheck its authored claim, not echo any proposed text");
    }

    public static void testUnknownRequirementAndForeignCandidateFailClosed() throws Exception {
        var playback = ScenarioLlmPlayback.flood();
        expectFailure(() -> protocol(playback).contributions(original(playback) + " Changed meaning.", List.of(APPLICATION)));
        expectFailure(() -> protocol(playback).contributions(original(playback),
                List.of(new Node("FOREIGN", "UA", "Fabricated", "", false))));
        check(playback.failures().size() == 2, "Unmatched calls remain visible and cannot use a real provider fallback");
    }

    public static void testActualRelationPromptsAndRepliesRemainReviewable() throws Exception {
        var playback = ScenarioLlmPlayback.flood();
        var protocol = protocol(playback);
        var original = original(playback);
        var source = protocol.contributions(original, List.of(APPLICATION)).getFirst().contributions().getFirst();
        var query = new Query(original, source, "CONSUMES", Direction.OUTGOING, Phase.NAVIGATE, List.of(PICTURE), null);
        var proposal = protocol.evaluate(query).getFirst();
        protocol.evaluate(new Query(original, source, "CONSUMES", Direction.OUTGOING, Phase.VERIFY, List.of(PICTURE), proposal));
        check(playback.calls().size() == 3, "Extraction, navigation and verification are separate real protocol exchanges");
        for (var call : playback.calls()) {
            check(call.prompt().startsWith(ScenarioRelationPlayback.PREFIX) && call.prompt().contains(original),
                    "The full original and actual relation prompt must be retained");
            check(!call.response().isBlank(), "A response hash alone cannot support domain review");
            check(call.promptSha256().equals(ScenarioLlmPlayback.sha256(call.prompt()))
                            && call.responseSha256().equals(ScenarioLlmPlayback.sha256(call.response())),
                    "Stored text must match the exchange fingerprints");
        }
    }

    public static void testMissingPositiveSourceCannotBecomeAnInventedContextRejection() throws Exception {
        var fixture = ScenarioLlmPlayback.flood().fixture().deepCopy();
        var sources = (tools.jackson.databind.node.ArrayNode) fixture.at("/relationPlayback/sourceContributions");
        for (int index = 0; index < sources.size(); index++) {
            if (sources.get(index).path("nodeId").asString().equals("CP-1016")) {
                sources.remove(index);
                break;
            }
        }
        try {
            new ScenarioLlmPlayback(fixture);
            throw new AssertionError("Every positively assessed source needs an authored contribution or a reasoned rejection");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().equals("Missing positive source assessment: CP-1016"),
                    "The omitted source must fail explicitly, not disappear as navigation context");
        }
    }

    public static void testEverySelectableChannelRemainsARequiredCapability() throws Exception {
        var playback = ScenarioLlmPlayback.flood();
        var protocol = protocol(playback);
        String original = original(playback);
        var dissemination = new Node("BP-1535", "BP", "Information Retrieval and Dissemination", "", false);
        for (var channel : List.of(
                new Node("CO-1048", "CO", "Short Messaging Access Services", "", false),
                new Node("CO-1066", "CO", "Voice Access Services", "", false),
                new Node("CR-1037", "CR", "Informal Messaging Services", "", false))) {
            var contribution = protocol.contributions(original, List.of(channel)).getFirst().contributions().getFirst();
            var proposal = protocol.evaluate(new Query(original, contribution, "SUPPORTS", Direction.OUTGOING,
                    Phase.NAVIGATE, List.of(dissemination), null)).getFirst();
            check(proposal.outcome() == Outcome.MATCH && proposal.necessity() == Necessity.REQUIRED,
                    channel.id() + " must remain available even when an individual delivery uses another channel");
            check(!proposal.condition().isBlank() && proposal.alternativeGroup().isBlank(),
                    "User selection is a per-delivery condition, not a mutually exclusive architecture alternative");
            var verified = protocol.evaluate(new Query(original, contribution, "SUPPORTS", Direction.OUTGOING,
                    Phase.VERIFY, List.of(dissemination), proposal)).getFirst();
            check(verified.outcome() == Outcome.VERIFIED && verified.condition().equals(proposal.condition()),
                    "Separate verification must retain the required channel and its delivery condition");
        }
    }

    public static void testPublicReadingChannelDeliveryAndAssumedReliabilityStaySeparate() throws Exception {
        var playback = ScenarioLlmPlayback.flood();
        var node = new Node("BP-1535", "BP", "Information Retrieval and Dissemination", "", false);
        var assessment = protocol(playback).contributions(original(playback), List.of(node)).getFirst();
        check(assessment.contributions().size() == 3,
                "F1 public reading, F3 channel dispatch and A1 reliability need distinct functional scopes");
        var reading = assessment.contributions().stream().filter(part -> part.quote().startsWith("F1:")).findFirst().orElseThrow();
        var delivery = assessment.contributions().stream().filter(part -> part.quote().startsWith("F3:")).findFirst().orElseThrow();
        var reliability = assessment.contributions().stream().filter(part -> part.quote().startsWith("A1 ")).findFirst().orElseThrow();
        check(reading.condition().isBlank() && delivery.condition().isBlank(),
                "The mandatory F1/F3 functions must not depend on the supplementary A1 design assumption");
        check(reliability.condition().contains("test design assumption"), "The A1-specific scope must retain its assumption");
        check(!reading.text().contains("queue") && !delivery.text().contains("retries"),
                "Read-only and ordinary delivery scopes must not silently inherit A1 infrastructure choices");
    }

    private static RelationSearchProtocol protocol(ScenarioLlmPlayback playback) {
        return new RelationSearchProtocol(prompt -> JSON.readTree(playback.respond(prompt))
                .at("/choices/0/message/content").asString());
    }
    private static String original(ScenarioLlmPlayback playback) { return playback.fixture().at("/requirement/text").asString(); }
    private static void expectFailure(Runnable operation) {
        try { operation.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("An unknown request must fail closed");
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
}
