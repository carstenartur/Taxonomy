package com.taxonomy.portfolio.service;

import com.taxonomy.analysis.service.LlmGatewayRegistry;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.dto.AnalysisLanguage;
import com.taxonomy.portfolio.dto.RequirementIntakeDtos.Candidate;
import com.taxonomy.portfolio.dto.RequirementIntakeDtos.Classification;
import com.taxonomy.portfolio.dto.RequirementIntakeDtos.Coverage;
import com.taxonomy.portfolio.dto.RequirementIntakeDtos.Request;
import com.taxonomy.portfolio.dto.RequirementIntakeDtos.Response;
import com.taxonomy.portfolio.dto.RequirementIntakeDtos.Scope;
import com.taxonomy.portfolio.dto.RequirementIntakeDtos.Status;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequirementIntakeCorpusTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USER = "corpus-reviewer";
    private static final WorkspaceContext WORKSPACE = new WorkspaceContext(USER, "corpus", "draft", "example");
    private static final Scope SCOPE = new Scope("example", "corpus", "corpus", "draft");

    record Requirement(String key, String text) {}
    record Example(String id, String key, Classification expected, String input, String inputEvidence,
                   String sourceEvidence) {}
    record Corpus(List<Requirement> requirements, List<Example> cases) {}
    record Observation(Example example, Status status, String provider, Classification actual, int rank,
                       boolean evidence, int falseMatches) {
        boolean classified() { return actual == example.expected(); }
        boolean topThree() { return example.expected() != Classification.NONE && classified() && rank > 0 && rank <= 3; }
    }

    @Test
    void measuresReuseAndEvidenceAcrossTwentyCuratedCases() throws IOException {
        Corpus corpus;
        try (var input = getClass().getResourceAsStream("/intake/reuse-corpus.json")) {
            corpus = JSON.readValue(Objects.requireNonNull(input), Corpus.class);
        }
        assertThat(corpus.cases()).hasSize(20).extracting(Example::id).doesNotHaveDuplicates();
        for (var label : List.of(Classification.DUPLICATE, Classification.ADDITION, Classification.CONFLICT, Classification.NONE)) {
            assertThat(corpus.cases()).filteredOn(example -> example.expected() == label).hasSize(5);
        }
        var offline = run(corpus, false);
        assertThat(offline).allMatch(result -> result.status() == Status.UNAVAILABLE);
        assertThat(offline).filteredOn(Observation::classified).extracting(result -> result.example().id())
                .containsExactly("duplicate-exact-alarm", "duplicate-exact-export");
        assertThat(offline).filteredOn(Observation::topThree).extracting(Observation::rank).containsExactly(2);
        assertThat(offline).filteredOn(Observation::evidence).hasSize(2);
        assertThat(offline).allMatch(result -> result.falseMatches() == 0);

        if (Boolean.getBoolean("taxonomy.intake.evaluateLive")) {
            var live = run(corpus, true);
            assertThat(live).as("A missing provider result is not a negative classification. See target/intake-evaluation/live.csv.")
                    .allMatch(result -> result.status() == Status.COMPLETE);
        }
    }

    private List<Observation> run(Corpus corpus, boolean live) throws IOException {
        var rows = new ArrayList<Candidate>();
        for (var requirement : corpus.requirements()) {
            long id = rows.size() + 1L;
            rows.add(new Candidate("requirement:" + id, "REQUIREMENT", SCOPE.repositoryId(), SCOPE.workspaceId(),
                    SCOPE.branch(), 1L, "EVALUATION", id, id + 100, requirement.key(), requirement.key(),
                    requirement.text(), RequirementIntakeService.inputHash(requirement.text()), null, null,
                    List.of(), List.of("NEW", "CHANGE", "LINK", "VIEW")));
        }
        // Only the data-store boundary is replaced. The intake service and gateway
        // still validate the classifications and quotes they actually return.
        var frozen = new RequirementIntakeCandidates.Frozen(SCOPE, 1L, rows, List.of(),
                new Coverage(rows.size(), rows.size(), false), true);
        var candidates = mock(RequirementIntakeCandidates.class);
        when(candidates.freeze(1L, USER, WORKSPACE)).thenReturn(frozen);
        when(candidates.discover(any(), eq(USER))).thenReturn(frozen);

        var observations = new ArrayList<Observation>();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("intake-evaluation",
                    live ? Map.of("llm.mock", false) : Map.of("llm.mock", true, "llm.provider", "GEMINI")));
            context.registerBean(LlmProviderConfig.class, () -> new LlmProviderConfig(null));
            context.refresh();
            var config = context.getBean(LlmProviderConfig.class);
            if (live) {
                assertThat(context.getEnvironment().getProperty("llm.provider")).as("Choose the evaluation provider explicitly").isNotBlank();
                var provider = config.getActiveProviderId();
                assertThat(provider).isNotEqualTo(LlmProvider.LOCAL_ONNX.id());
                assertThat(config.isProviderConfigured(provider)).as("Evaluation provider is configured").isTrue();
            }
            var requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofSeconds(10));
            requestFactory.setReadTimeout(Duration.ofSeconds(60));
            var gateways = new LlmGatewayRegistry(config, new RestTemplate(requestFactory), JSON, null, requestFactory, null, null);
            var service = new RequirementIntakeService(candidates, new RequirementIntakeMatcher(config, gateways, JSON));
            for (var example : corpus.cases()) {
                var source = rows.stream().filter(row -> row.requirementKey().equals(example.key())).findFirst().orElseThrow();
                if (example.expected() != Classification.NONE) {
                    assertThat(example.inputEvidence()).isNotBlank();
                    assertThat(example.sourceEvidence()).isNotBlank();
                    assertThat(example.input()).contains(example.inputEvidence());
                    assertThat(source.text()).contains(example.sourceEvidence());
                }
                var response = service.review(new Request(1L, null, example.input(), AnalysisLanguage.ENGLISH), USER, WORKSPACE);
                observations.add(observe(example, response));
            }
        }
        writeResults(live ? "live" : "offline", observations);
        return observations;
    }

    private static Observation observe(Example example, Response response) {
        // The intake panel hides NONE and leaves the other rows in response order.
        var visible = response.candidates().stream().filter(candidate -> candidate.classification() != Classification.NONE).toList();
        int rank = 0;
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i).requirementKey().equals(example.key())) { rank = i + 1; break; }
        }
        var candidate = response.candidates().stream()
                .filter(row -> row.requirementKey().equals(example.key())).findFirst().orElseThrow();
        int falseMatches = example.expected() == Classification.NONE
                ? (int) visible.stream().filter(row -> row.classification() != null).count() : 0;
        var quotes = candidate.quotes();
        boolean evidence = example.expected() != Classification.NONE && candidate.classification() == example.expected()
                && (quotes.size() == 2 && quotes.get(0).contains(example.inputEvidence()) && quotes.get(1).contains(example.sourceEvidence())
                    || quotes.size() == 1 && example.input().equals(candidate.text())
                    && quotes.getFirst().contains(example.inputEvidence()) && quotes.getFirst().contains(example.sourceEvidence()));
        return new Observation(example, response.status(), response.provider(), candidate.classification(), rank, evidence, falseMatches);
    }

    private static void writeResults(String mode, List<Observation> observations) throws IOException {
        Path location = Path.of("target", "intake-evaluation", mode + ".csv");
        Files.createDirectories(location.getParent());
        var csv = new StringBuilder("case,expected,status,provider,classification,visible_rank,top_three,supporting_quotes,false_matches\n");
        for (var result : observations) {
            csv.append(String.join(",", result.example().id(), result.example().expected().name(), result.status().name(),
                    Objects.toString(result.provider(), ""), Objects.toString(result.actual(), ""),
                    result.rank() == 0 ? "" : Integer.toString(result.rank()), Boolean.toString(result.topThree()),
                    Boolean.toString(result.evidence()), Integer.toString(result.falseMatches()))).append('\n');
        }
        Files.writeString(location, csv);
        System.out.printf("Intake %s: %d/20 provider results, %d/15 classified in top 3, %d/15 with supporting quotes, %d false matches on 5 negative cases%n",
                mode, observations.stream().filter(result -> result.status() == Status.COMPLETE).count(),
                observations.stream().filter(Observation::topThree).count(),
                observations.stream().filter(Observation::evidence).count(),
                observations.stream().mapToInt(Observation::falseMatches).sum());
    }
}
