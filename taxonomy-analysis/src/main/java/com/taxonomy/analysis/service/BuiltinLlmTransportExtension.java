package com.taxonomy.analysis.service;

import com.taxonomy.extension.api.llm.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/** Built-in provider contribution; policy-owned gateways are resolved only when executed. */
abstract class BuiltinLlmTransportExtension implements LlmTransportExtension {
    @Autowired private ObjectProvider<LlmGatewayRegistry> gateways;
    @Override public final LlmTransport transport() {
        return new LlmTransport() {
            private LlmTransport delegate() {
                if (gateways == null) throw new IllegalStateException("Provider runtime is not initialized");
                return new ProviderTransportView(gateways.getObject().getGatewayById(new ProviderId(providerName())));
            }
            public String providerName() { return descriptor().providerId(); }
            public String sendHttpRequest(String prompt, String key) { return delegate().sendHttpRequest(prompt, key); }
            public String extractResponseText(String raw) { return delegate().extractResponseText(raw); }
        };
    }
}
