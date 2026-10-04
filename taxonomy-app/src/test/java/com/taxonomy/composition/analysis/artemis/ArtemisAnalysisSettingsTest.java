package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.dag.AnalysisTaskGraph;
import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtemisAnalysisSettingsTest {

    @Test
    void transportModeIsLocalByDefaultAndFailsClosedOnUnknownValues() {
        assertThat(ArtemisAnalysisSettings.artemisMode(null)).isFalse();
        assertThat(ArtemisAnalysisSettings.artemisMode("local")).isFalse();
        assertThat(ArtemisAnalysisSettings.artemisMode("artemis")).isTrue();
        assertThatThrownBy(() -> ArtemisAnalysisSettings.artemisMode("kafka"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void plaintextTcpIsRejectedWhileTlsIsRequired() {
        assertThatThrownBy(() -> new ArtemisAnalysisSettings("tcp://broker:61616", true, 1000, 30000))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("sslEnabled=true");
        assertThatThrownBy(() -> new ArtemisAnalysisSettings(
                "tcp://broker:61617?sslEnabled=true&sslEnabled=false", true, 1000, 30000))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new ArtemisAnalysisSettings("tcp://broker:61617?sslEnabled=true", true, 1000, 30000))
                .isNotNull();
        assertThat(new ArtemisAnalysisSettings("(tcp://a:61617?sslEnabled=true,tcp://b:61617?sslEnabled=true)",
                true, 1000, 30000)).isNotNull();
        assertThat(new ArtemisAnalysisSettings("tcp://broker:61616", false, 1000, 30000)).isNotNull();
    }

    @Test
    void brokerUrlIsRequiredAndNeverRendered() {
        assertThatThrownBy(() -> new ArtemisAnalysisSettings(" ", true, 1000, 30000))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ArtemisAnalysisSettings("http://broker", false, 1000, 30000))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ArtemisAnalysisSettings("tcp://broker:61616", false, 10, 30000))
                .isInstanceOf(IllegalStateException.class);
        var settings = new ArtemisAnalysisSettings("tcp://analysis:" + "s3cr3t" + "@broker:61616", false, 1000, 30000);
        assertThat(settings.toString()).doesNotContain("s3cr3t").doesNotContain("broker");
    }

    @Test
    void destinationsAreDerivedFromRootAndFamily() {
        var destinations = new AnalysisDestinations(AnalysisDestinations.DEFAULT_PREFIX);
        assertThat(destinations.subtaxonomy(TaxonomyShardRoot.of("CP"))).isEqualTo("taxonomy.analysis.subtaxonomy.CP");
        assertThat(destinations.relation(TaxonomyShardRoot.of("IP"))).isEqualTo("taxonomy.analysis.relation.IP");
        assertThat(destinations.generalRelation()).isEqualTo("taxonomy.analysis.relation.general");
        var graph = AnalysisTaskGraph.plan("op", List.of(TaxonomyShardRoot.of("CP"), TaxonomyShardRoot.of("IP")), true);
        assertThat(graph.tasks()).hasSize(3);
        assertThatThrownBy(() -> new AnalysisDestinations("Bad Prefix")).isInstanceOf(IllegalArgumentException.class);
    }
}
