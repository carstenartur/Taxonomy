package com.taxonomy.interop;

import com.taxonomy.extension.api.integration.IntegrationContracts.ExchangeDocument;
import java.util.Map;
import java.util.TreeMap;

/** Host-owned artifact binding, kept outside editable exchange metadata and binary content. */
public final class IntegrationFileBinding {
    private IntegrationFileBinding() { }
    public static Map<String, String> plugin(ExchangeDocument document) {
        var result = new TreeMap<String, String>();
        for (String key : PluginExchangeConnector.BINDING_KEYS) {
            String value = document.metadata().get(key);
            if (value != null) result.put(key, value);
        }
        if (!result.isEmpty() && result.size() != PluginExchangeConnector.BINDING_KEYS.size())
            throw IntegrationProblem.conflict("INTEGRATION_PLUGIN_CHANGED");
        return Map.copyOf(result);
    }
}
