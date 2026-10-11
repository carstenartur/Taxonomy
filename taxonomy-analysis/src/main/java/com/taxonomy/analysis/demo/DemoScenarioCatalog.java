package com.taxonomy.analysis.demo;

import com.taxonomy.extension.api.llm.ProviderId;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Immutable, classpath-owned registry of versioned product-demo scenarios. */
@Component
public class DemoScenarioCatalog {
    public static final String INDEX = "demo-scenarios/index.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String command;
    private final String defaultScenario;
    private final Map<String, DemoScenario> scenarios;

    public DemoScenarioCatalog() {
        this(path -> DemoScenarioCatalog.class.getClassLoader().getResourceAsStream(path));
    }

    public DemoScenarioCatalog(Function<String, InputStream> resources) {
        JsonNode index;
        try (InputStream stream = resources.apply(INDEX)) {
            if (stream == null) throw new IllegalStateException("Missing " + INDEX);
            index = JSON.readTree(stream);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read " + INDEX, failure);
        }
        if (!"taxonomy-product-demo-index-v1".equals(index.path("schema").asString()))
            throw new IllegalStateException("Unsupported demo scenario index");
        this.command = DemoScenario.text(index, "command");
        this.defaultScenario = DemoScenario.text(index, "defaultScenario");
        var loaded = new LinkedHashMap<String, DemoScenario>();
        for (String id : DemoScenario.strings(index, "scenarios")) loaded.put(id, DemoScenario.load(id, resources));
        if (!loaded.containsKey(defaultScenario)) throw new IllegalStateException("Default demo scenario is not registered");
        this.scenarios = Collections.unmodifiableMap(loaded);
    }

    public String command() { return command; }

    public DemoScenario defaultScenario() { return scenarios.get(defaultScenario); }

    public Optional<DemoScenario> find(String id) { return Optional.ofNullable(scenarios.get(id)); }

    public List<DemoScenario> scenarios() { return new ArrayList<>(scenarios.values()); }

    /** A demo provider is registered only for an existing scenario and an authored iteration. */
    public boolean supports(ProviderId provider) {
        return DemoProviderIds.parse(provider)
                .flatMap(binding -> find(binding.scenarioId()).filter(s -> s.iterations().containsKey(binding.iteration())))
                .isPresent();
    }

    public DemoScenario require(DemoProviderIds.Binding binding) {
        var scenario = find(binding.scenarioId()).orElseThrow(() ->
                new IllegalArgumentException("Unknown demo scenario: " + binding.scenarioId()));
        scenario.iteration(binding.iteration());
        return scenario;
    }
}
