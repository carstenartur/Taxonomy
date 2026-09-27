package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.analysis.service.LlmService;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static com.taxonomy.dto.RelationSearchModel.*;

/** The opt-in mock must exercise the same protocol, not bypass it with a legacy graph. */
public final class MockRelationProtocolContract {
    private static final Node APPLICATION = new Node("UA-1574", "UA", "Unified Communication Applications", "", false);
    private static final Node RECORD = new Node("IP-1136", "IP", "Electronic Treatment Records", "", false);
    private static final String ORIGINAL = "Clinical handover: display records. Literal data: INPUT\n\"{inert}\"";

    public static void main(String[] args) throws Exception { testMockUsesStrictScopedProtocolWithoutLiveTransport(); testOtherRawMockCallsRemainUnchanged(); }

    public static void testMockUsesStrictScopedProtocolWithoutLiveTransport() throws Exception {
        var protocol = new RelationSearchProtocol(mockService()::callLlmRaw);
        var sources = protocol.contributions(ORIGINAL, List.of(APPLICATION,
                new Node("UA-1000", "UA", "User Applications", "", false)));
        var part = sources.stream().filter(s -> s.node().id().equals(APPLICATION.id())).findFirst().orElseThrow()
                .contributions().getFirst();
        check(part.quote().equals(ORIGINAL) && part.text().contains("MOCK"), "The mock is explicit and retains literal original data");
        check(sources.stream().filter(s -> s.node().id().equals("UA-1000")).findFirst().orElseThrow()
                .contributions().isEmpty(), "The mock does not pretend its navigation ancestors are components");
        var root = protocol.evaluate(new Query(ORIGINAL, part, "CONSUMES", Direction.OUTGOING, Phase.NAVIGATE,
                List.of(new Node("IP", "IP", "Information Products", "", true)), null)).getFirst();
        check(root.outcome() == Outcome.DESCEND, "The real engine must traverse the hierarchy");
        var proposed = protocol.evaluate(new Query(ORIGINAL, part, "CONSUMES", Direction.OUTGOING,
                Phase.NAVIGATE, List.of(RECORD), null)).getFirst();
        check(proposed.outcome() == Outcome.MATCH && proposed.necessity() == Necessity.REQUIRED, "A concrete demonstration claim is proposed");
        var verified = protocol.evaluate(new Query(ORIGINAL, part, "CONSUMES", Direction.OUTGOING,
                Phase.VERIFY, List.of(RECORD), proposed)).getFirst();
        check(verified.outcome() == Outcome.VERIFIED && verified.rationale().contains("MOCK"), "Separate verification remains explicit simulation");
        var forged = new Decision(proposed.targetId(), Outcome.MATCH, "unrelated", proposed.quote(), proposed.necessity(),
                proposed.condition(), proposed.alternativeGroup(), proposed.rationale(), "");
        check(protocol.evaluate(new Query(ORIGINAL, part, "CONSUMES", Direction.OUTGOING,
                Phase.VERIFY, List.of(RECORD), forged)).getFirst().outcome() == Outcome.REJECT, "The mock does not blindly echo a forged proposal");
        check(protocol.evaluate(new Query(ORIGINAL, part, "PRODUCES", Direction.OUTGOING,
                Phase.NAVIGATE, List.of(RECORD), null)).getFirst().outcome() == Outcome.REJECT, "The read demonstration does not invent writes");
    }
    public static void testOtherRawMockCallsRemainUnchanged() throws Exception {
        check(mockService().callLlmRaw("unrelated raw task").equals("[]"), "Only the explicitly identified relation protocol is handled");
    }
    private static LlmService mockService() throws Exception {
        var config = new LlmProviderConfig(null);
        var mode = LlmProviderConfig.class.getDeclaredField("llmMock"); mode.setAccessible(true); mode.set(config, true);
        // A real gateway is deliberately absent: accidental transport use must fail this test.
        return new LlmService(config, null, new ObjectMapper(), null, null, null, null);
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
}
