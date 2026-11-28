package com.taxonomy.dsl.export;

import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.mapper.ModelToAstMapper;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.api.project.ProjectDataProvider;
import com.taxonomy.extension.runtime.PluginCatalog;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

public class ProjectDataSeedTest {
    private static final UUID CURRENT = UUID.fromString("561ae52b-d444-4ef1-9eed-429ce756844d");
    private static final String SELECTED = "authority/directory";
    private static final ProjectDataProvider.Unit FIRST = new ProjectDataProvider.Unit("{001}", "Records", null);
    private static final ProjectDataProvider.Unit SECOND = new ProjectDataProvider.Unit("urn:org:ü:02", "Records", "{001}");

    public static ProjectDataProvider directory(String id, UUID revision, List<ProjectDataProvider.Unit> units) {
        return new ProjectDataProvider() {
            public String id() { return id; }
            public String displayName() { return "Public directory"; }
            public Dataset dataset() { return new Dataset("2026-11-28", revision, units); }
        };
    }
    public static void register(PluginCatalog catalog, String owner, ProjectDataProvider directory) {
        catalog.publish(new PluginDescriptor(new PluginIdentity(owner, "1.0.0", "a".repeat(64)),
                ">=1.6.0 & <2.0.0", List.of(), Set.of(), PluginMode.STARTUP), List.of(directory));
    }

    @Test void initializationRetainsExactNativeIdsAndParentAcrossSerializationAndIndependentReimport() {
        var catalog = new PluginCatalog();
        register(catalog, "authority", directory("directory", null, List.of(SECOND, FIRST)));
        var datasets = new ProjectDataSeed(catalog);
        var initial = datasets.initial("test", SELECTED, CURRENT, ignored -> fail("Unexpected catalogue switch"));
        var model = new AstToModelMapper().map(new TaxDslParser().parse(initial));
        assertThat(model.getCatalogueRevision()).isEqualTo(CURRENT.toString());
        assertThat(model.getOrganizations()).hasSize(2).extracting(unit -> unit.extensions().get("x-source-id"))
                .containsExactlyInAnyOrder(FIRST.sourceId(), SECOND.sourceId());
        var parent = model.getOrganizations().stream().filter(unit -> unit.parentId() == null).findFirst().orElseThrow();
        assertThat(parent.extensions()).containsEntry("x-source-id", FIRST.sourceId());
        assertThat(model.getOrganizations()).anySatisfy(child -> assertThat(child.parentId()).isEqualTo(parent.id()));
        String roundtrip = new TaxDslSerializer().serialize(new ModelToAstMapper().toDocument(model, "test"));
        var review = datasets.planImport(roundtrip, SELECTED);
        assertThat(review.source()).isEqualTo(roundtrip);
        assertThat(review.added()).isZero();
        assertThat(review.existing()).isEqualTo(2);
        register(catalog, "neighbor", directory("second", null, List.of(FIRST, SECOND)));
        var other = datasets.planImport(roundtrip, "neighbor/second");
        assertThat(other.added()).isEqualTo(2);
        assertThat(new TaxDslParser().parse(other.source()).blocksOfKind("organization")).hasSize(4);
        assertThatThrownBy(() -> datasets.planImport(roundtrip, "neighbor/directory")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void ambiguousOrInvalidSourcesAndConflictingPinnedCatalogueFailBeforeAnyImport() {
        for (var invalid : List.of(List.of(FIRST, FIRST),
                List.of(new ProjectDataProvider.Unit("child", "Records", "absent")),
                List.of(new ProjectDataProvider.Unit("child", "Records", "child")))) {
            var catalog = new PluginCatalog();
            register(catalog, "authority", directory("directory", null, invalid));
            assertThatThrownBy(() -> new ProjectDataSeed(catalog).initial("test", SELECTED, CURRENT, ignored -> {}))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var catalog = new PluginCatalog();
        register(catalog, "authority", directory("directory", CURRENT, List.of(FIRST)));
        var datasets = new ProjectDataSeed(catalog);
        assertThatThrownBy(() -> datasets.planImport("meta {\n  namespace: \"unbound\";\n}\n", SELECTED))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("catalogue revision");
        String existing = datasets.initial("test", SELECTED, CURRENT, ignored -> {});
        assertThatThrownBy(() -> datasets.planImport(existing.replace("x-source-id:", "x-replaced-source-id:"), SELECTED))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("conflicts");
    }
}
