package com.taxonomy.export.reformulation;

import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrozenReformulationArchitectureOrderTest {
    @Test void frozenIdentityHasStableImmutableIterationOrder() {
        var source = new LinkedHashMap<String, String>();
        source.put("Snapshot", "snap");
        source.put("Repository", "repo");
        source.put("Workspace", "work");
        var frozen = new FrozenReformulationArchitecture(new DiagramModel("Frozen", List.of(), List.of(),
                new DiagramLayout("LR", true)), source, List.of(), List.of(), true, List.of(), List.of());
        source.put("Snapshot", "changed");
        assertThat(frozen.identity().keySet()).containsExactly("Snapshot", "Repository", "Workspace");
        assertThat(frozen.identity()).containsEntry("Snapshot", "snap");
        assertThatThrownBy(() -> frozen.identity().put("added", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
