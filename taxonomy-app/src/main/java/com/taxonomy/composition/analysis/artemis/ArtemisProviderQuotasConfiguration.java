package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.ProviderRateQuotas;
import com.taxonomy.extension.api.llm.ProviderId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit opt-in; configuring workers never seeds or refills durable quota state. */
@com.taxonomy.shared.features.ConditionalOnFeature({"analysis"})
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="taxonomy.analysis.provider-quotas.enabled",havingValue="true")
public class ArtemisProviderQuotasConfiguration {
    @Bean ArtemisProviderQuotaSettings artemisProviderQuotaSettings(Environment environment) {
        if(!ArtemisAnalysisSettings.artemisMode(environment.getProperty("taxonomy.analysis.transport.mode","local")))
            throw new IllegalStateException("Cluster provider quotas require artemis analysis transport");
        String prefix="taxonomy.analysis.provider-quotas.";
        var binder=Binder.get(environment);
        var configured=binder.bind(prefix+"provider-groups",Bindable.mapOf(String.class,String.class)).orElse(Map.of());
        Map<ProviderId,String> groups=new LinkedHashMap<>();
        configured.forEach((key,group)->{
            ProviderId literal=new ProviderId(key);
            ProviderId id=LlmProvider.builtin(new ProviderId(key.replace('-','_'))).map(LlmProvider::id).orElse(literal);
            if(groups.putIfAbsent(id,group)!=null) throw new IllegalArgumentException("Duplicate provider quota group: "+id);
        });
        var limits=binder.bind(prefix+"groups",Bindable.mapOf(String.class,ArtemisProviderQuotaPolicy.class),
                new NoUnboundElementsBindHandler(BindHandler.DEFAULT)).orElse(Map.of());
        Map<String,ProviderQuotaLedger.Policy> policies=new LinkedHashMap<>();
        Map<String,ProviderRateQuotas.TokenBudget> tokens=new LinkedHashMap<>();
        limits.forEach((group,policy)->{
            policies.put(group,policy.ledgerPolicy());
            if(policy.tokenBudget()!=null)tokens.put(group,policy.tokenBudget());
        });
        return new ArtemisProviderQuotaSettings(environment.getProperty(prefix+"destination-prefix","taxonomy.analysis.provider-quotas"),
                groups,policies,tokens,environment.getProperty(prefix+"maximum-wait-ms",Long.class,120_000L));
    }
    @Bean ProviderRateQuotas artemisProviderRateQuotas(ArtemisAnalysisConnection connection,
            ArtemisProviderQuotaSettings settings,ObjectMapper json) {
        return new ArtemisProviderRateQuotas(connection,settings,json,System::currentTimeMillis);
    }
}
