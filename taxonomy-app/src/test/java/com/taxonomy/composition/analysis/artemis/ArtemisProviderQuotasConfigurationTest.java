package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.service.LlmProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ArtemisProviderQuotasConfigurationTest {
    private final ArtemisProviderQuotasConfiguration config=new ArtemisProviderQuotasConfiguration();
    @Test void explicitCredentialGroupBindsExactDurableLimitsAndAliases() {
        var env=environment().withProperty("taxonomy.analysis.provider-quotas.provider-groups.custom-openai","credential-a");
        var settings=config.artemisProviderQuotaSettings(env);
        assertThat(settings.providerGroups()).containsEntry(LlmProvider.OPENAI.id(),"credential-a")
                .containsEntry(LlmProvider.CUSTOM_OPENAI.id(),"credential-a");
        assertThat(settings.policies().get("credential-a").rpm()).isEqualTo(2);
        assertThat(settings.policies().get("credential-a").rpd()).isEqualTo(3);
        assertThat(settings.policies().get("credential-a").clockSkewMillis()).isEqualTo(1000);
        assertThat(settings.tokenBudgets()).isEmpty();
    }
    @Test void localModeCannotPretendToProvideClusterWideLimits() {
        assertThatThrownBy(()->config.artemisProviderQuotaSettings(environment()
                .withProperty("taxonomy.analysis.transport.mode","local"))).hasMessageContaining("artemis");
    }
    @Test void misspelledLimitAndUnreferencedGroupCannotSilentlyDisableOrInventAQuota() {
        assertThatThrownBy(()->config.artemisProviderQuotaSettings(environment()
                .withProperty("taxonomy.analysis.provider-quotas.groups.credential-a.rmp","1"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->config.artemisProviderQuotaSettings(environment()
                .withProperty("taxonomy.analysis.provider-quotas.groups.other.rpm","1"))).hasMessageContaining("coverage");
    }
    @Test void activeTpmRequiresAnExplicitVersionedInputAndOutputContract() {
        assertThatThrownBy(()->config.artemisProviderQuotaSettings(environment()
                .withProperty("taxonomy.analysis.provider-quotas.groups.credential-a.tpm","150"))).isInstanceOf(RuntimeException.class);
        var env=environment().withProperty("taxonomy.analysis.provider-quotas.groups.credential-a.tpm","150")
                .withProperty("taxonomy.analysis.provider-quotas.groups.credential-a.input-upper-bound","80")
                .withProperty("taxonomy.analysis.provider-quotas.groups.credential-a.output-cap","20")
                .withProperty("taxonomy.analysis.provider-quotas.groups.credential-a.token-adapter","fixture-text-budget");
        assertThat(config.artemisProviderQuotaSettings(env).tokenBudgets().get("credential-a").maximumTokens()).isEqualTo(100);
    }
    private MockEnvironment environment() {
        return new MockEnvironment().withProperty("taxonomy.analysis.transport.mode","artemis")
                .withProperty("taxonomy.analysis.provider-quotas.provider-groups.OPENAI","credential-a")
                .withProperty("taxonomy.analysis.provider-quotas.groups.credential-a.rpm","2")
                .withProperty("taxonomy.analysis.provider-quotas.groups.credential-a.rpd","3");
    }
}
