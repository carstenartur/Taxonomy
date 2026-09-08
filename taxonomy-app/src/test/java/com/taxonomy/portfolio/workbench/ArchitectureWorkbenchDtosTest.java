package com.taxonomy.portfolio.workbench;

import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.ElementMetadata;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.Projection;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.RelationMetadata;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.SnapshotProvenance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureWorkbenchDtosTest {
    private static final Instant CREATED = Instant.parse("2026-08-05T12:00:00Z");
    private static final SnapshotProvenance PROVENANCE =
            new SnapshotProvenance(99L, "persisted-catalogue", "repository-a");

    @Test
    void absentLegacyMetadataDoesNotDiscardPersistedSnapshotAuthority() {
        Projection projection = projection(null, null, null);

        assertThat(projection.elements()).isEmpty();
        assertThat(projection.relations()).isEmpty();
        assertThat(projection.warnings()).isEmpty();
        assertThat(projection.snapshotId()).isEqualTo("snapshot-1");
        assertThat(projection.exportProvenance()).isEqualTo(PROVENANCE);
    }

    @Test
    void snapshotMetadataCannotChangeThroughCallerOwnedCollections() {
        ElementMetadata element = new ElementMetadata("CP", "Capabilities", "CP", 90,
                0.9, 0.8, "DIRECT", "CP", "Persisted selection", true,
                ReviewStatus.CONFIRMED, null, null, "alice", CREATED, "Reviewed");
        RelationMetadata relation = new RelationMetadata("CP", "CR", "SUPPORTS",
                "ASSERTED", "DIRECT", 0.8, 0.8, "Persisted relation",
                ReviewStatus.CONFIRMED, "alice", CREATED, "Reviewed");
        Map<String, ElementMetadata> elements = new HashMap<>(Map.of("CP", element));
        Map<String, RelationMetadata> relations = new HashMap<>(Map.of(relation.signature(), relation));
        List<String> warnings = new ArrayList<>(List.of("Persisted warning"));
        Projection projection = projection(elements, relations, warnings);

        elements.clear();
        relations.clear();
        warnings.clear();

        assertThat(projection.elements()).containsExactlyEntriesOf(Map.of("CP", element));
        assertThat(projection.relations()).containsExactlyEntriesOf(Map.of(relation.signature(), relation));
        assertThat(projection.warnings()).containsExactly("Persisted warning");
        assertThat(projection.exportProvenance()).isEqualTo(PROVENANCE);
        assertThatThrownBy(() -> projection.elements().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> projection.relations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> projection.warnings().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static Projection projection(Map<String, ElementMetadata> elements,
                                         Map<String, RelationMetadata> relations,
                                         List<String> warnings) {
        return new Projection(42L, "P-001", "Project", 7L, "REQ-001", "Requirement",
                "Persisted requirement text", "snapshot-1", AnalysisStatus.SUCCESS, CREATED,
                "provider", "model", "workspace-a", "branch-a", "persisted-commit",
                null, null, elements, relations, warnings, PROVENANCE);
    }
}
