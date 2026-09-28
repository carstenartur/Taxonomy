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
        var reversed = protocol.evaluate(new Query(original, contribution, "CONSUMES", Direction.INCOMING,
                Phase.NAVIGATE, List.of(PICTURE), null)).getFirst();
        var write = protocol.evaluate(new Query(original, contribution, "PRODUCES", Direction.OUTGOING,
                Phase.NAVIGATE, List.of(PICTURE), null)).getFirst();
        check(reversed.outcome() == Outcome.REJECT && write.outcome() == Outcome.REJECT, "Reading must not manufacture reversed or write access");
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
