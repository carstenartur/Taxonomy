package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.extension.api.llm.ProviderId;
import com.taxonomy.analysis.service.ProviderRateQuotas.TokenBudget;
import java.util.Map;

record ArtemisProviderQuotaSettings(String destinationPrefix, Map<ProviderId, String> providerGroups,
                                    Map<String, ProviderQuotaLedger.Policy> policies,
                                    Map<String, TokenBudget> tokenBudgets, long maximumWaitMillis) {
    ArtemisProviderQuotaSettings {
        new ArtemisProviderPermitSettings(destinationPrefix, providerGroups, maximumWaitMillis);
        providerGroups = Map.copyOf(providerGroups); policies = Map.copyOf(policies); tokenBudgets = Map.copyOf(tokenBudgets);
        if (providerGroups.isEmpty() || !policies.keySet().equals(new java.util.HashSet<>(providerGroups.values()))) {
            throw new IllegalArgumentException("Explicit provider quota group/policy coverage is required");
        }
        var tokenGroups = new java.util.HashSet<String>();
        for (var entry : policies.entrySet()) {
            String group = entry.getKey(); var policy = entry.getValue();
            ArtemisProviderPermitSettings.validateGroup(group);
            if (policy.tpm() > 0) {
                tokenGroups.add(group); var budget = tokenBudgets.get(group);
                if (budget == null || budget.inputUpperBound() != policy.inputUpperBound()
                        || budget.maximumOutputTokens() != policy.outputCap()) {
                    throw new IllegalArgumentException("TPM policy requires its exact token budget contract");
                }
            }
        }
        if (!tokenGroups.equals(tokenBudgets.keySet())) throw new IllegalArgumentException("Unexpected token quota budget group");
    }
    String group(com.taxonomy.extension.api.llm.ProviderId provider) {
        String group = providerGroups.get(provider);
        if (group == null) throw new com.taxonomy.analysis.service.ProviderRateQuotas.UnavailableException("No explicit provider rate quota group", null);
        return group;
    }
    String queueName(String group) { ArtemisProviderPermitSettings.validateGroup(group); return destinationPrefix + "." + group; }
}
