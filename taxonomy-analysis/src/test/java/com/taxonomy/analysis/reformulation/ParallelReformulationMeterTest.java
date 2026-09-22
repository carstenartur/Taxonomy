package com.taxonomy.analysis.reformulation;

import com.taxonomy.analysis.service.LlmTransportMeter;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.assertThat;

class ParallelReformulationMeterTest {
    @Test void capturesTheSameObserverForSerialAndParallelRealGatewayCalls() throws Exception {
        var events = new CopyOnWriteArrayList<LlmTransportMeter.Observation>();
        try (var ignored = LlmTransportMeter.open(events::add)) {
            ParallelReformulationChecks.independentSubtreesOverlapAndPreserveTheSerialDocument();
        }
        assertThat(events).as("Four serial and four parallel HTTP calls").hasSize(8);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.source()).isEqualTo(LlmTransportMeter.Source.HTTP);
            assertThat(event.provider()).isEqualTo("CUSTOM_OPENAI");
            assertThat(event.statusCode()).isEqualTo(200);
            assertThat(event.usage().totalTokens()).as("Missing provider usage is unknown").isNull();
        });
        assertThat(events.stream().map(LlmTransportMeter.Observation::invocationId).distinct()).hasSize(8);
    }
}
