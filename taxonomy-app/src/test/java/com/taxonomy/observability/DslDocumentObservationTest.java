package com.taxonomy.observability;

import com.taxonomy.architecture.repository.ArchitectureDslDocumentRepository;
import com.taxonomy.composition.dsl.service.DslDocumentOperationsFacade;
import com.taxonomy.dsl.diff.ModelDiff;
import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.versioning.service.*;
import com.taxonomy.workspace.service.*;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DslDocumentObservationTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private final TaxonomyObservationConfiguration.TaxonomyObservationBeanPostProcessor processor;
    private final DslMaterializeService materialize = mock(DslMaterializeService.class);
    private final DslGitRepositoryFactory repositories = mock(DslGitRepositoryFactory.class);
    private final DslGitRepository git = mock(DslGitRepository.class);
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final RepositoryStateService state = mock(RepositoryStateService.class);
    private final RepositoryContext context = RepositoryContext.workspace("repository","workspace","review","alice");
    private final DslOperationsFacade workspace;
    private final DslDocumentOperationsFacade documents;
    private final ModelDiff diff = new ModelDiff(List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
    private static final String BEFORE = "a".repeat(40), AFTER = "b".repeat(40);

    DslDocumentObservationTest() {
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        processor = new TaxonomyObservationConfiguration.TaxonomyObservationBeanPostProcessor(() -> observations);
        workspace = (DslOperationsFacade) processor.postProcessAfterInitialization(new DslOperationsFacade(
                repositories,mock(CommitIndexService.class),mock(ConflictDetectionService.class),
                mock(RepositoryStateGuard.class),state,resolver,mock(WorkspaceArchitectureVersionPort.class)),"workspace");
        documents = (DslDocumentOperationsFacade) processor.postProcessAfterInitialization(new DslDocumentOperationsFacade(
                mock(TaxDslExportService.class),materialize,mock(ArchitectureDslDocumentRepository.class),workspace),"documents");
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(context);
        when(repositories.resolveRepository(context)).thenReturn(git);
    }
    @Test
    void materializationMethodsEachRecordOneTimer() {
        var result = new DslMaterializeService.MaterializeResult(true,List.of(),List.of(),0,0,2L);
        when(materialize.materialize("private DSL","private path","review","commit")).thenReturn(result);
        when(materialize.materializeIncremental(1L,2L)).thenReturn(result);
        assertThat(documents.materialize("private DSL","private path","review","commit")).isSameAs(result);
        assertThat(documents.materializeIncremental(1L,2L)).isSameAs(result);
        assertTimer("materialize","success",1);
        assertTimer("materializeIncremental","success",1);
        assertThat(meters.find("taxonomy.repository.operation").timers()).hasSize(2);
    }
    @Test
    void gitDiffUsesRequestRepositoryAndRecordsOnlyOneOperation() throws Exception {
        when(git.diffBetween(BEFORE,AFTER)).thenReturn(diff);
        assertThat(documents.diffBetween(BEFORE,AFTER)).isSameAs(diff);
        verify(state).ensureWorkspaceState("alice");
        verify(repositories).resolveRepository(context);
        verifyNoInteractions(materialize);
        assertTimer("diffBetween","success",1);
        assertThat(meters.find("taxonomy.repository.operation").timers()).hasSize(1);
    }
    @Test
    void archiveDiffDoesNotResolveGitAndRecordsOnlyOneOperation() throws Exception {
        when(materialize.diffDocuments(1L,2L)).thenReturn(diff);
        assertThat(documents.diffBetween("1","2")).isSameAs(diff);
        verifyNoInteractions(repositories,state,resolver,git);
        assertTimer("diffBetween","success",1);
    }
    @Test
    void failuresPropagateWithBoundedErrorOutcome() throws Exception {
        var failure = new IllegalStateException("private DSL username alice repository");
        when(git.diffBetween(BEFORE,AFTER)).thenThrow(failure);
        when(materialize.diffDocuments(1L,2L)).thenThrow(failure);
        when(materialize.materialize("private",null,null,null)).thenThrow(failure);
        when(materialize.materializeIncremental(null,2L)).thenThrow(failure);
        assertThatThrownBy(() -> documents.diffBetween(BEFORE,AFTER)).isSameAs(failure);
        assertThatThrownBy(() -> documents.diffBetween("1","2")).isSameAs(failure);
        assertThatThrownBy(() -> documents.materialize("private",null,null,null)).isSameAs(failure);
        assertThatThrownBy(() -> documents.materializeIncremental(null,2L)).isSameAs(failure);
        assertTimer("diffBetween","error",2);
        assertTimer("materialize","error",1);
        assertTimer("materializeIncremental","error",1);
    }
    @Test
    void workspaceGitDiffIsNotSeparatelyTimedAndSemanticSubclassMatchingStaysExact() throws Exception {
        when(git.diffBetween(BEFORE,AFTER)).thenReturn(diff);
        assertThat(workspace.diffBetween(BEFORE,AFTER)).isSameAs(diff);
        assertThat(meters.find("taxonomy.repository.operation").timers()).isEmpty();
        var semantic = new SemanticDslOperationsFacade(repositories,mock(CommitIndexService.class),
                mock(ConflictDetectionService.class),mock(RepositoryStateGuard.class),state,resolver,
                mock(SemanticGitMergeService.class),mock(VersioningPortfolioGitPort.class),mock(WorkspaceArchitectureVersionPort.class));
        assertThat(processor.postProcessAfterInitialization(semantic,"semantic")).isSameAs(semantic);
    }
    @Test
    void javaAgentInventoryResolvesAndMovedOperationsHaveOnlyCompositionFacadeTarget() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("observability/javaagent.properties"))) root = root.getParent();
        var properties = new Properties();
        try(var reader = Files.newBufferedReader(root.resolve("observability/javaagent.properties"))) { properties.load(reader); }
        String inventory = properties.getProperty("otel.instrumentation.methods.include");
        for(String entry:inventory.split(";")) {
            int bracket = entry.indexOf('[');
            Class<?> owner = Class.forName(entry.substring(0,bracket));
            Set<String> methods = Arrays.stream(owner.getMethods()).map(java.lang.reflect.Method::getName).collect(java.util.stream.Collectors.toSet());
            List<String> included = Arrays.asList(entry.substring(bracket+1,entry.length()-1).split(","));
            assertThat(methods).containsAll(included);
            if(owner.equals(DslOperationsFacade.class)) assertThat(included).doesNotContain("materialize","materializeIncremental","diffBetween");
        }
        assertThat(inventory).contains("com.taxonomy.composition.dsl.service.DslDocumentOperationsFacade[materialize,materializeIncremental,diffBetween]");
    }
    private void assertTimer(String operation,String outcome,long count) {
        var timer = meters.get("taxonomy.repository.operation").tag("taxonomy.operation",operation).tag("outcome",outcome).timer();
        assertThat(timer.count()).isEqualTo(count);
        assertThat(timer.getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey)
                .containsExactlyInAnyOrder("taxonomy.component","taxonomy.operation","outcome","error");
        assertThat(timer.getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getValue)
                .contains("repository",operation,outcome).doesNotContain("alice","workspace","private DSL","private path");
    }
}
