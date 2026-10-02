package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxonomyNodeRootMembershipTest {
    private static class Catalogue extends TaxonomyService {
        private int lookups;
        Catalogue() { super(null, null, null); }
        @Override public TaxonomyNode getNodeByCode(String code) {
            lookups++;
            if ("missing".equals(code)) return null;
            var node = new TaxonomyNode();
            node.setCode(code);
            node.setTaxonomyRoot("rootless".equals(code) ? null : "BP");
            return node;
        }
    }

    @Test
    void acceptsRealMembershipRegardlessOfCodePrefixWithOneResolution() {
        var catalogue = new Catalogue();
        assertThatCode(() -> catalogue.validateNodeRootMembership("IP-looking-selected", Set.of("BP")))
                .doesNotThrowAnyException();
        assertThat(catalogue.lookups).isEqualTo(1);
    }

    @Test
    void rejectsKnownNodeFromAnotherRootRegardlessOfCodePrefix() {
        var catalogue = new Catalogue();
        assertThatThrownBy(() -> catalogue.validateNodeRootMembership("IP-looking-selected", Set.of("IP")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside selected taxonomy roots")
                .hasMessageContaining("IP-looking-selected");
        assertThat(catalogue.lookups).isEqualTo(1);
    }

    @Test
    void distinguishesUnknownIdentityFromOtherRootRejection() {
        var catalogue = new Catalogue();
        assertThatThrownBy(() -> catalogue.validateNodeRootMembership("missing", Set.of("BP")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown catalogue node")
                .hasMessageContaining("missing");
        assertThat(catalogue.lookups).isEqualTo(1);
    }

    @Test
    void rejectsExistingNodeWithoutRootMembership() {
        var catalogue = new Catalogue();
        assertThatThrownBy(() -> catalogue.validateNodeRootMembership("rootless", Set.of("BP")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside selected taxonomy roots");
        assertThat(catalogue.lookups).isEqualTo(1);
    }
}
