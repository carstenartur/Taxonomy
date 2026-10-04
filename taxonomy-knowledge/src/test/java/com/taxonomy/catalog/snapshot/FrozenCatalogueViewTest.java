package com.taxonomy.catalog.snapshot;

import com.sun.management.ThreadMXBean;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrozenCatalogueViewTest {
    private static volatile int consumed;

    @Test
    void detachedReadsPreserveFullNavigationAndIsolateMutableGraphs() {
        var view = view(2);
        TaxonomyNode leaf = view.node("fixture-leaf-a");
        TaxonomyNode parent = leaf.getParent();
        assertThat(parent.getCode()).isEqualTo("fixture-branch");
        assertThat(parent.getChildren()).extracting(TaxonomyNode::getCode)
                .containsExactly("fixture-leaf-a", "fixture-leaf-b");
        assertThat(parent.getChildren().getFirst()).isSameAs(leaf);
        assertThat(parent.getParent().getChildren()).extracting(TaxonomyNode::getCode)
                .containsExactly("fixture-branch", "fixture-unrelated-0", "fixture-unrelated-1");
        assertThat(parent.getParent().getChildren().getFirst()).isSameAs(parent);

        leaf.setNameEn("Caller title");
        parent.getChildren().removeFirst();
        parent.getChildren().add(leaf);
        assertThat(parent.getChildren()).extracting(TaxonomyNode::getCode)
                .containsExactly("fixture-leaf-b", "fixture-leaf-a");
        assertThat(view.node("fixture-leaf-a").getNameEn()).isEqualTo("Alpha");
        assertThat(view.children("fixture-branch")).extracting(TaxonomyNode::getCode)
                .containsExactly("fixture-leaf-a", "fixture-leaf-b");
        assertThat(view.rootNodes().getFirst().getChildren().getFirst().getChildren()).hasSize(2);
        assertThatThrownBy(() -> view.children("fixture-branch").clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.node("unknown-fixture-node"))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pointReadsDoNotAllocateUnrelatedBranches(boolean childrenRead) {
        var small = view(8);
        var wide = view(1024);
        ThreadMXBean allocations = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(allocations.isThreadAllocatedMemorySupported()).isTrue();
        boolean enabled = allocations.isThreadAllocatedMemoryEnabled();
        if (!enabled) allocations.setThreadAllocatedMemoryEnabled(true);
        try {
            read(small, childrenRead, 32);
            read(wide, childrenRead, 32);
            long smallBytes = allocated(allocations, small, childrenRead);
            long wideBytes = allocated(allocations, wide, childrenRead);
            // Both queries touch the same three-node branch and its root. Extra siblings
            // must not multiply allocation; allow constant JVM/instrumentation overhead.
            assertThat(wideBytes).as("allocated bytes for a fixed query in a wide frozen root")
                    .isLessThanOrEqualTo(smallBytes * 4 + 8192);
        } finally {
            if (!enabled) allocations.setThreadAllocatedMemoryEnabled(false);
        }
    }

    private static long allocated(ThreadMXBean allocations, FrozenCatalogueView view, boolean childrenRead) {
        long thread = Thread.currentThread().threadId();
        long before = allocations.getThreadAllocatedBytes(thread);
        read(view, childrenRead, 24);
        return allocations.getThreadAllocatedBytes(thread) - before;
    }

    private static void read(FrozenCatalogueView view, boolean childrenRead, int repetitions) {
        int sum = 0;
        for (int i = 0; i < repetitions; i++) {
            TaxonomyNode node = childrenRead ? view.children("fixture-branch").getFirst()
                    : view.node("fixture-leaf-a");
            sum += node.getNameEn().length();
            assertThat(node.getCode()).isEqualTo("fixture-leaf-a");
        }
        consumed = sum;
    }

    private static FrozenCatalogueView view(int unrelated) {
        List<RootCatalogueSnapshot.Node> nodes = new ArrayList<>();
        nodes.add(node("CP", null, "Capabilities"));
        nodes.add(node("fixture-branch", "CP", "Branch"));
        nodes.add(node("fixture-leaf-b", "fixture-branch", "Beta"));
        nodes.add(node("fixture-leaf-a", "fixture-branch", "Alpha"));
        for (int i = 0; i < unrelated; i++) nodes.add(node("fixture-unrelated-" + i, "CP", "Unrelated " + i));
        var source = new CatalogueSourceIdentity("repository", "workspace", "branch", "commit");
        var snapshot = new RootCatalogueSnapshot(2, source, "CP", nodes,
                new CatalogueOverlayService.OverlayMetadata(false, "frozen", "fixture", "v1", null, 0),
                CatalogueSnapshotServiceTest.provenance(null));
        return new FrozenCatalogueView(source, Set.of("CP"), List.of(snapshot));
    }

    private static RootCatalogueSnapshot.Node node(String code, String parent, String name) {
        TaxonomyNode node = CatalogueSnapshotServiceTest.node(code, "CP", parent, name);
        if (parent != null && !parent.equals("CP")) node.setLevel(2);
        return RootCatalogueSnapshot.Node.capture(node,
                new CatalogueOverlayService.NodeMetadata("CATEGORY", List.of(), 1, false, null), false);
    }
}
