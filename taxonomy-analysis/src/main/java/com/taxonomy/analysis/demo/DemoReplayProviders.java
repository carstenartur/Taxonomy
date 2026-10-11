package com.taxonomy.analysis.demo;

import com.taxonomy.analysis.service.ScenarioReplayProviders;
import com.taxonomy.extension.api.llm.ProviderId;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Exposes the authored product-demo scenarios as run-bound scenario-replay providers. */
@Component
public class DemoReplayProviders implements ScenarioReplayProviders {
    private final DemoScenarioCatalog catalog;
    private final Map<ProviderId, Replay> replays = new ConcurrentHashMap<>();

    public DemoReplayProviders(DemoScenarioCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override public boolean owns(ProviderId provider) { return DemoProviderIds.isDemo(provider); }

    @Override public Optional<Replay> find(ProviderId provider) {
        return DemoProviderIds.parse(provider)
                .filter(binding -> catalog.supports(binding.providerId()))
                .map(binding -> replays.computeIfAbsent(binding.providerId(), id -> replay(binding)));
    }

    private Replay replay(DemoProviderIds.Binding binding) {
        var scenario = catalog.require(binding);
        String iteration = binding.iteration();
        return new Replay(binding.providerId(),
                "scenario-replay-" + scenario.id() + "-v" + scenario.version() + "-" + iteration.toLowerCase(Locale.ROOT),
                "replay:demo-scenarios/" + scenario.id(),
                "Scenario replay " + scenario.id() + " " + iteration,
                scenario.fingerprint() + ":" + iteration,
                new DemoReplyBook(scenario, iteration)::reply);
    }
}
