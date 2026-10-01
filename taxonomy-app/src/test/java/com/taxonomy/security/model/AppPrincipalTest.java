package com.taxonomy.security.model;

import com.taxonomy.backup.PrincipalId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AppPrincipalTest {
    @Test void requiresBothADurableIdentityAndAnExplicitNonblankScope() {
        var id = PrincipalId.create();
        assertThatNullPointerException().isThrownBy(() -> new AppPrincipal(null, "alice", true));
        for (String scope : new String[]{null, "", " \t\n"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new AppPrincipal(id, scope, true));
        }
        for (String scope : new String[]{"alice", "principal-" + id.value()}) {
            var principal = new AppPrincipal(id, scope, false);
            assertThat(principal.id()).isEqualTo(id);
            assertThat(principal.scopeKey()).isEqualTo(scope);
            assertThat(principal.enabled()).isFalse();
        }
    }
}
