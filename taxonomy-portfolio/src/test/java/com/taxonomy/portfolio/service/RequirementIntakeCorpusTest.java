package com.taxonomy.portfolio.service;

import com.taxonomy.analysis.service.LlmGatewayRegistry;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.analysis.service.PromptTemplateService;
import com.taxonomy.dto.AnalysisLanguage;
import com.taxonomy.model.WorkspaceOverlayScope;
import com.taxonomy.portfolio.dto.RequirementIntakeDtos.Candidate;
import com.taxonomy.portfolio.dto.RequirementIntakeDtos.Classification;
import com.taxonomy.portfolio.dto.RequirementIntakeDtos.Request;
import com.taxonomy.portfolio.dto.RequirementIntakeDtos.Response;
import com.taxonomy.portfolio.dto.RequirementIntakeDtos.Scope;
import com.taxonomy.portfolio.dto.RequirementIntakeDtos.Status;
import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.repository.ArchitectureProjectRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementReadRow;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspacePortfolioDocumentPort;
import com.taxonomy.workspace.service.WorkspaceSourceDiscoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequirementIntakeCorpusTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USER = "corpus-reviewer";
    private static final String REPOSITORY = "intake-home";
    private static final String BRANCH = "draft";
    private static final long PROJECT = 1L;
    private static final String SOURCE_COMMIT = "a".repeat(40);
    private static final String REQUIREMENT = "REQUIREMENT";
    private static final String ELEMENT = "ARCHITECTURE_ELEMENT";
    private static final String RELATION = "ARCHITECTURE_RELATION";
    private static final WorkspaceContext WORKSPACE = new WorkspaceContext(USER, null, BRANCH, REPOSITORY);
    private static final Scope SCOPE = new Scope(REPOSITORY, null, WorkspaceOverlayScope.keyFor(null), BRANCH);

    record Requirement(String key, Long id, Long versionId, String text) {}
    record Example(String id, String kind, String key, Classification expected, String input, String inputEvidence,
                   String sourceEvidence) {
        String candidateKind() { return kind == null ? REQUIREMENT : kind; }
        boolean positive() { return expected != Classification.NONE; }
    }
    record RequirementCorpus(List<Requirement> requirements, List<Example> cases) {}
    record ArchitectureCorpus(String repositoryId, String branch, List<Example> cases) {}
    record Corpus(RequirementCorpus requirements, ArchitectureCorpus architecture, String sourceDsl) {
        List<Example> cases() {
            return Stream.concat(requirements.cases().stream(), architecture.cases().stream()).toList();
        }
    }
    record Observation(Example example, Status status, String code, String provider, String model,
                       Candidate candidate, Classification actual, Integer rank, boolean retrieved,
                       boolean identityPreserved, boolean evidence, Integer falseMatches) {
        boolean classified() { return actual == example.expected(); }
        boolean topThree() { return example.positive() && classified() && rank != null && rank <= 3; }
    }

    @Test
    void measuresOfflineFallbackAndScopedRetrievalAcrossTheFullCorpus() throws IOException {
        var corpus = readCorpus();
        assertThat(corpus.cases()).hasSize(50).extracting(Example::id).doesNotHaveDuplicates();
        assertThat(corpus.requirements().cases()).hasSize(30).extracting(Example::expected)
                .contains(Classification.values());
        for (String kind : List.of(ELEMENT, RELATION)) {
            assertThat(corpus.architecture().cases()).filteredOn(example -> kind.equals(example.candidateKind())).hasSize(10)
                    .filteredOn(Example::positive).hasSize(5);
        }

        var offline = run(corpus, false);
        assertThat(offline).allMatch(result -> result.status() == Status.UNAVAILABLE
                && result.identityPreserved() && result.retrieved() && result.falseMatches() == null);
        assertThat(offline).filteredOn(Observation::classified).extracting(result -> result.example().id())
                .containsExactly("duplicate-exact-alarm", "duplicate-exact-export");
        assertThat(offline).filteredOn(Observation::topThree).extracting(Observation::rank).containsExactly(2);
        assertThat(offline).filteredOn(Observation::evidence).hasSize(2);

        if (Boolean.getBoolean("taxonomy.intake.evaluateLive")) {
            var live = run(corpus, true);
            assertThat(live).as("Every live provider review must complete. See target/intake-evaluation/live.csv.")
                    .allMatch(result -> result.status() == Status.COMPLETE);
            assertThat(live).allMatch(result -> result.identityPreserved() && result.retrieved());
        }
    }

    private static Corpus readCorpus() throws IOException {
        try (var requirements = fixture("reuse-corpus.json");
             var architecture = fixture("architecture-reuse-corpus.json");
             var source = fixture("architecture-reuse-source.taxdsl")) {
            return new Corpus(JSON.readValue(requirements, RequirementCorpus.class),
                    JSON.readValue(architecture, ArchitectureCorpus.class),
                    new String(source.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static InputStream fixture(String name) {
        return Objects.requireNonNull(RequirementIntakeCorpusTest.class.getResourceAsStream("/intake/" + name));
    }

    private static List<Observation> run(Corpus corpus, boolean live) throws IOException {
        // Replace only the persistence boundaries. Scope checks, commit capture,
        // DSL discovery, provider validation, quote checks and fallback run as shipped.
        var requirements = mock(ProjectRequirementRepository.class);
        var projects = mock(ArchitectureProjectRepository.class);
        var portfolio = mock(ProjectPortfolioService.class);
        var repositories = mock(SystemRepositoryRepository.class);
        var membership = mock(RepositoryMembershipService.class);
        var documents = mock(WorkspacePortfolioDocumentPort.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RequirementIntakePolicy> policies = mock(ObjectProvider.class);
        when(policies.orderedStream()).thenAnswer(call -> Stream.empty());
        var candidates = new RequirementIntakeCandidates(projects, requirements, portfolio,
                new WorkspaceSourceDiscoveryService(repositories, membership, mock(UserWorkspaceRepository.class)),
                documents, policies);
        var project = mock(ArchitectureProject.class);
        when(project.getId()).thenReturn(PROJECT);
        when(project.getProjectKey()).thenReturn("INTAKE");
        when(portfolio.requireProject(PROJECT, USER, WORKSPACE)).thenReturn(project);
        var scopeKey = PortfolioScope.key(USER, WORKSPACE);
        var rows = new ArrayList<ProjectRequirementReadRow>();
        for (var source : corpus.requirements().requirements()) {
            var row = new ProjectRequirementReadRow(source.id(), PROJECT, source.key(), source.key(), null, 0, null,
                    null, null, USER, source.versionId(), null, null, null, source.versionId(), 1, source.text(),
                    RequirementIntakeService.inputHash(source.text()), null, USER, null, null, null, null, null, null, null);
            rows.add(row);
            when(requirements.findReadRowByIdAndProjectIdAndScopeKey(source.id(), PROJECT, scopeKey)).thenReturn(Optional.of(row));
        }
        when(requirements.findIntakeRows(eq(scopeKey), eq(PROJECT), any())).thenReturn(new SliceImpl<>(rows));
        when(requirements.findIntakeRows(eq(scopeKey), isNull(), any())).thenReturn(new SliceImpl<>(List.of()));
        when(projects.findByScopeKeyOrderByUpdatedAtDesc(eq(scopeKey), any())).thenReturn(new SliceImpl<>(List.of()));

        var home = repository(REPOSITORY, BRANCH, "Intake home");
        var architecture = repository(corpus.architecture().repositoryId(), corpus.architecture().branch(), "Municipal operations");
        var hidden = repository("private-copy", corpus.architecture().branch(), architecture.getDisplayName());
        when(repositories.findByLifecycleStateOrderByDisplayNameAsc(eq(RepositoryLifecycleState.ACTIVE), any()))
                .thenReturn(new SliceImpl<>(List.of(architecture, hidden)));
        when(repositories.findByRepositoryId(REPOSITORY)).thenReturn(Optional.of(home));
        when(repositories.findByRepositoryId(architecture.getRepositoryId())).thenReturn(Optional.of(architecture));
        when(repositories.findByRepositoryId(hidden.getRepositoryId())).thenReturn(Optional.of(hidden));
        when(membership.canRead(home, USER)).thenReturn(true);
        when(membership.canRead(architecture, USER)).thenReturn(true);
        var sourceContext = new WorkspaceContext(USER, null, architecture.getDefaultBranch(), architecture.getRepositoryId());
        var hiddenContext = new WorkspaceContext(USER, null, hidden.getDefaultBranch(), hidden.getRepositoryId());
        var document = mock(WorkspacePortfolioDocumentPort.DocumentHandle.class);
        when(documents.resolveRepository(sourceContext)).thenReturn(document);
        when(document.getHeadCommit(architecture.getDefaultBranch())).thenReturn(SOURCE_COMMIT);
        when(document.getDslAtCommit(SOURCE_COMMIT)).thenReturn(corpus.sourceDsl());

        List<Observation> observations;
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("intake-evaluation",
                    live ? Map.of("llm.mock", false) : Map.of("llm.mock", true, "llm.provider", "GEMINI")));
            context.registerBean(LlmProviderConfig.class, () -> new LlmProviderConfig(null));
            context.refresh();
            var config = context.getBean(LlmProviderConfig.class);
            String model = live ? configuredModel(context, config) : null;
            var requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofSeconds(10));
            requestFactory.setReadTimeout(Duration.ofSeconds(Long.getLong("taxonomy.intake.liveReadTimeoutSeconds", 180L)));
            var gateways = new LlmGatewayRegistry(config, new RestTemplate(requestFactory), JSON, null, requestFactory, null, null);
            var templates = new PromptTemplateService();
            templates.loadDefaults();
            var service = new RequirementIntakeService(candidates, new RequirementIntakeMatcher(config, gateways, JSON, templates));
            int parallelism = live ? Integer.getInteger("taxonomy.intake.liveParallelism", 1) : 1;
            assertThat(parallelism).as("Live evaluation parallelism").isBetween(1, 4);
            observations = reviewCases(corpus, live ? selectedLiveCases(corpus) : corpus.cases(), service, model, parallelism);
        }
        verify(documents, never()).resolveRepository(hiddenContext);
        verify(document, never()).getDslAtHead(any());
        writeResults(live ? "live" : "offline", observations);
        return observations;
    }

    private static List<Example> selectedLiveCases(Corpus corpus) {
        String selection = System.getProperty("taxonomy.intake.liveCases");
        if (selection == null) return corpus.cases();
        var ids = Arrays.stream(selection.split(",", -1)).map(String::strip).toList();
        var examples = corpus.cases().stream().filter(example -> ids.contains(example.id())).toList();
        assertThat(examples).extracting(Example::id).containsExactlyInAnyOrderElementsOf(ids);
        return examples;
    }

    private static List<Observation> reviewCases(Corpus corpus, List<Example> examples, RequirementIntakeService service,
                                                 String model, int parallelism) {
        if (parallelism == 1) {
            return examples.stream().map(example -> reviewCase(corpus, service, example, model)).toList();
        }
        var tasks = examples.stream().<Callable<Observation>>map(example ->
                () -> reviewCase(corpus, service, example, model)).toList();
        // The fixtures are fully stubbed before review. Every worker reads the same
        // immutable rows/commit through the shared, normally concurrent service.
        try (var workers = Executors.newFixedThreadPool(parallelism)) {
            var results = new ArrayList<Observation>();
            for (var task : workers.invokeAll(tasks)) results.add(task.get());
            return results;
        } catch (ExecutionException failure) {
            throw new AssertionError("Intake evaluation case failed", failure.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Intake evaluation interrupted", interrupted);
        }
    }

    private static Observation reviewCase(Corpus corpus, RequirementIntakeService service, Example example, String model) {
        Long projectId = REQUIREMENT.equals(example.candidateKind()) ? PROJECT : null;
        var response = service.review(new Request(projectId, null, example.input(), AnalysisLanguage.ENGLISH), USER, WORKSPACE);
        return observe(corpus, example, response, model);
    }

    private static String configuredModel(AnnotationConfigApplicationContext context, LlmProviderConfig config) {
        assertThat(!context.getEnvironment().getProperty("llm.provider", "").isBlank())
                .as("Choose the evaluation provider explicitly").isTrue();
        var provider = config.getActiveProviderId();
        assertThat(provider).isNotEqualTo(LlmProvider.LOCAL_ONNX.id());
        assertThat(config.isScenarioReplay(provider)).isFalse();
        assertThat(config.isProviderConfigured(provider)).as("Evaluation provider is configured").isTrue();
        if (provider.equals(LlmProvider.GEMINI.id())) {
            String url = config.getGeminiUrl();
            return url.substring(url.lastIndexOf('/') + 1, url.lastIndexOf(':'));
        }
        return config.getOpenAiCompatibleModel(provider);
    }

    private static SystemRepository repository(String id, String branch, String title) {
        var repository = new SystemRepository();
        repository.setRepositoryId(id);
        repository.setDefaultBranch(branch);
        repository.setDisplayName(title);
        return repository;
    }

    private static Observation observe(Corpus corpus, Example example, Response response, String model) {
        assertThat(response.scope()).isEqualTo(SCOPE);
        assertThat(response.coverage().truncated()).isFalse();
        assertThat(response.coverage().admitted()).isEqualTo(response.candidates().size());
        assertThat(response.candidates()).extracting(Candidate::id).doesNotHaveDuplicates();
        assertThat(response.candidates()).extracting(Candidate::repositoryId).doesNotContain("private-copy");

        var matching = response.candidates().stream().filter(candidate -> example.candidateKind().equals(candidate.kind()))
                .filter(candidate -> example.key().equals(Objects.requireNonNullElse(candidate.requirementKey(),
                        Objects.toString(candidate.sourceObjectId(), "")))).toList();
        assertThat(matching).hasSizeLessThanOrEqualTo(1);
        Candidate candidate = matching.isEmpty() ? null : matching.getFirst();
        boolean identity = candidate != null && expectedIdentity(corpus, example, candidate);
        // The UI skips explicit NONE and otherwise presents the response in order.
        var visible = response.candidates().stream().filter(row -> row.classification() != Classification.NONE).toList();
        Integer rank = candidate != null && visible.contains(candidate) ? visible.indexOf(candidate) + 1 : null;
        Integer falseMatches = !example.positive() && response.status() == Status.COMPLETE
                ? (int) visible.stream().filter(row -> row.classification() != null && example.candidateKind().equals(row.kind())).count()
                : null;
        return new Observation(example, response.status(), response.code(), response.provider(), model,
                candidate, candidate == null ? null : candidate.classification(), rank,
                candidate != null, identity, hasSupportingQuotes(example, candidate), falseMatches);
    }

    private static boolean expectedIdentity(Corpus corpus, Example example, Candidate candidate) {
        if (REQUIREMENT.equals(example.candidateKind())) {
            var source = corpus.requirements().requirements().stream()
                    .filter(row -> row.key().equals(example.key())).findFirst().orElseThrow();
            return candidate.id().equals("requirement:" + source.id()) && candidate.repositoryId().equals(REPOSITORY)
                    && candidate.workspaceId() == null && candidate.branch().equals(BRANCH)
                    && Objects.equals(candidate.projectId(), PROJECT) && Objects.equals(candidate.requirementId(), source.id())
                    && Objects.equals(candidate.requirementVersionId(), source.versionId()) && candidate.text().equals(source.text())
                    && Objects.equals(candidate.contentHash(), RequirementIntakeService.inputHash(source.text()));
        }
        String prefix = ELEMENT.equals(example.candidateKind()) ? "architecture-element:" : "architecture-relation:";
        return candidate.id().equals(prefix + corpus.architecture().repositoryId() + ":" + example.key())
                && candidate.repositoryId().equals(corpus.architecture().repositoryId()) && candidate.workspaceId() == null
                && candidate.branch().equals(corpus.architecture().branch()) && SOURCE_COMMIT.equals(candidate.sourceCommit())
                && example.key().equals(candidate.sourceObjectId()) && candidate.requirementId() == null
                && candidate.requirementVersionId() == null && candidate.actions().equals(List.of("VIEW"))
                && Objects.equals(candidate.contentHash(), RequirementIntakeService.inputHash(candidate.text()));
    }

    private static boolean hasSupportingQuotes(Example example, Candidate candidate) {
        if (!example.positive() || candidate == null) return false;
        assertThat(example.inputEvidence()).isNotBlank();
        assertThat(example.sourceEvidence()).isNotBlank();
        assertThat(example.input()).contains(example.inputEvidence());
        assertThat(candidate.text()).contains(example.sourceEvidence());
        if (candidate.classification() != example.expected()) return false;
        var quotes = candidate.quotes();
        if (quotes.size() == 1 && example.input().equals(candidate.text())) {
            return quotes.getFirst().contains(example.inputEvidence()) && quotes.getFirst().contains(example.sourceEvidence());
        }
        return quotes.size() == 2 && quotes.get(0).contains(example.inputEvidence())
                && quotes.get(1).contains(example.sourceEvidence());
    }

    private static void writeResults(String mode, List<Observation> observations) throws IOException {
        Path directory = Path.of("target", "intake-evaluation");
        Files.createDirectories(directory);
        var csv = new StringBuilder("case,kind,expected_source_key,expected,status,code,provider,model,candidate_id,repository,branch,source_commit,requirement_version,classification,visible_rank,top_three,supporting_quotes,false_matches,retrieved,identity_preserved\n");
        for (var result : observations) {
            var candidate = result.candidate();
            csv.append(csvRow(result.example().id(), result.example().candidateKind(), result.example().key(), result.example().expected(),
                    result.status(), result.code(), result.provider(), result.model(),
                    candidate == null ? null : candidate.id(), candidate == null ? null : candidate.repositoryId(),
                    candidate == null ? null : candidate.branch(), candidate == null ? null : candidate.sourceCommit(),
                    candidate == null ? null : candidate.requirementVersionId(), result.actual(), result.rank(),
                    result.topThree(), result.evidence(), result.falseMatches(), result.retrieved(), result.identityPreserved()));
        }
        Files.writeString(directory.resolve(mode + ".csv"), csv);

        var summary = new StringBuilder("kind,cases,completed,positive,positive_completed,top_three,supporting_quotes,negative,negative_completed,false_matches,negative_cases_with_false_matches,retrieved,identity_preserved\n");
        for (String kind : List.of(REQUIREMENT, ELEMENT, RELATION, "ALL")) {
            var rows = observations.stream().filter(row -> "ALL".equals(kind) || kind.equals(row.example().candidateKind())).toList();
            long complete = rows.stream().filter(row -> row.status() == Status.COMPLETE).count();
            long positive = rows.stream().filter(row -> row.example().positive()).count();
            long negativeCompleted = rows.stream().filter(row -> row.falseMatches() != null).count();
            var falseCounts = rows.stream().map(Observation::falseMatches).filter(Objects::nonNull).toList();
            summary.append(csvRow(kind, rows.size(), complete, positive, complete - negativeCompleted,
                    rows.stream().filter(Observation::topThree).count(), rows.stream().filter(Observation::evidence).count(),
                    rows.size() - positive, negativeCompleted,
                    falseCounts.isEmpty() ? null : falseCounts.stream().mapToInt(Integer::intValue).sum(),
                    falseCounts.isEmpty() ? null : falseCounts.stream().filter(count -> count > 0).count(),
                    rows.stream().filter(Observation::retrieved).count(),
                    rows.stream().filter(Observation::identityPreserved).count()));
        }
        Files.writeString(directory.resolve(mode + "-summary.csv"), summary);
    }

    private static String csvRow(Object... values) {
        return Stream.of(values).map(value -> "\"" + Objects.toString(value, "").replace("\"", "\"\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "", "\n"));
    }
}
