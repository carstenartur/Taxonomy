package com.taxonomy.tooling;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MeasuredShardBalanceTest {
 @Test void movedSuitesHaveOneRequiredOwnerAndSourceTag() throws Exception {
  Path root=JavascriptJUnitBridge.root();
  var catalogue=FlatJson.parseObject(Files.readString(root.resolve(".mvn/verification-suites.json")));
  var plan=(Map<?,?>)catalogue.get("applicationTestShards");
  var groups=(Map<?,?>)plan.get("groups");
  var moves=Map.of("browser",List.of("com.taxonomy.CoreUiAcceptanceIT","com.taxonomy.PortfolioClientRoutingIT","com.taxonomy.CompleteCopilotSessionIT","com.taxonomy.SeleniumPostgresContainerIT","com.taxonomy.ArchitectureWorkbenchUiIT","com.taxonomy.PortfolioMatrixReviewIT"),"recovery",List.of("com.taxonomy.PortfolioVersioningRecoveryIT","com.taxonomy.workspace.service.WorkspaceRestartSelectionIT","com.taxonomy.ProductionPersistenceRestartIT","com.taxonomy.backup.runtime.BackupWriterCoverageIT","com.taxonomy.interop.publication.IntegrationPublicationRestartTest"));
  for(var move:moves.entrySet()) for(String suite:move.getValue()) {
   long owners=groups.values().stream().map(group -> (List<?>)((Map<?,?>)group).get("tests")).filter(tests -> tests.contains(suite)).count();
   assertEquals(1,owners,suite);
   var group=(Map<?,?>)groups.get(move.getKey());
   assertTrue(((List<?>)group.get("tests")).contains(suite),suite);
   String source=Files.readString(root.resolve("taxonomy-app/src/test/java/"+suite.replace('.','/')+".java"));
   assertTrue(source.contains("@Tag(\""+group.get("tag")+"\")"),suite);
  }
 }

 @Test void auditedRuntimeAndDomainClassesHaveExactOwnersAndKeepCoreAnchors() throws Exception {
  Path root=JavascriptJUnitBridge.root();
  var plan=AppTestShardVerifier.object(AppTestShardVerifier.read(root.resolve(".mvn/verification-suites.json")).get("applicationTestShards"));
  var groups=AppTestShardVerifier.object(plan.get("groups"));
  var assignments=AppTestShardVerifier.catalogueAssignments(root.resolve("taxonomy-app/src/test/java"),plan);
  {
   var expected=Set.of(
    "com.taxonomy.backup.recovery.NativeInstallationPostgresRecoveryIT",
    "com.taxonomy.KeycloakSecurityContainerIT",
    "com.taxonomy.ObservabilityContainerIT",
    "com.taxonomy.OnnxSeleniumIT",
    "com.taxonomy.LocalOnnxPipelineIT",
    "com.taxonomy.DiagnosticsContainerIT",
    "com.taxonomy.DiagnosticsPostgresContainerIT",
    "com.taxonomy.TaxonomyLargeResultBudgetIT",
    "com.taxonomy.PortfolioAnalysisUiDiagnosticIT",
    "com.taxonomy.DiagnosticsWithApiKeyContainerIT");
   assertTrue(groups.containsKey("runtime"), "Missing runtime owner");
   var group=AppTestShardVerifier.object(groups.get("runtime"));
   assertEquals(expected,new HashSet<>(AppTestShardVerifier.strings(group.get("tests"))));
   assertEquals(true,group.get("requiresEmbeddingModel"));
   for(String suite:expected) assertEquals("runtime",assignments.get(suite),suite);
  }
  {
   var expected=Set.of(
    "com.taxonomy.portfolio.reformulation.AdoptedLineageRealReanalysisTest",
    "com.taxonomy.portfolio.reformulation.ReformulationEncodingTest",
    "com.taxonomy.portfolio.reformulation.ReformulationDispatchClaimTest",
    "com.taxonomy.interop.ScenarioArchiMateImportTest",
    "com.taxonomy.portfolio.reformulation.ReformulationPositiveReviewGuardTest",
    "com.taxonomy.portfolio.reformulation.ReformulationEvidenceRoundTripTest",
    "com.taxonomy.portfolio.reformulation.ReformulationQuestionWorkflowTest",
    "com.taxonomy.workspace.controller.WorkspaceControllerTest",
    "com.taxonomy.composition.analysis.artemis.ArtemisProviderRateQuotasTest",
    "com.taxonomy.composition.analysis.artemis.ArtemisAnalysisTransportTest",
    "com.taxonomy.portfolio.reformulation.ReformulationReportTest",
    "com.taxonomy.interop.publication.IntegrationPublicationRecoveryTest",
    "com.taxonomy.interop.publication.IntegrationPublicationPartialTest",
    "com.taxonomy.portfolio.reformulation.InventoryVariantConcurrencyTest",
    "com.taxonomy.portfolio.reformulation.ReformulationIsolationTest",
    "com.taxonomy.portfolio.reformulation.ReformulationSynthesisTest",
    "com.taxonomy.interop.SparxIntegrationFlowTest",
    "com.taxonomy.portfolio.reformulation.ReformulationCancellationApiTest",
    "com.taxonomy.workspace.storage.JgitStorageHibernateIntegrationTest",
    "com.taxonomy.portfolio.PortfolioPartialDiagnosticsTest",
    "com.taxonomy.composition.analysis.artemis.ArtemisProviderPermitProvisionerTest",
    "com.taxonomy.composition.analysis.artemis.ArtemisProductionExecutionTest",
    "com.taxonomy.portfolio.ProjectRequirementAnalysisServiceTest",
    "com.taxonomy.portfolio.PortfolioAnalysisCoverageRegressionTest",
    "com.taxonomy.interop.IntegrationFlowTest",
    "com.taxonomy.portfolio.SolutionProductPortfolioIntegrationTest",
    "com.taxonomy.portfolio.reformulation.ReformulationTargetedSynthesisTest",
    "com.taxonomy.portfolio.PortfolioPageRoutesTest",
    "com.taxonomy.interop.publication.IntegrationPublicationApiTest",
    "com.taxonomy.interop.publication.IntegrationPublicationSecurityTest",
    "com.taxonomy.portfolio.PortfolioAnalysisWorkQueueClaimTest",
    "com.taxonomy.portfolio.PortfolioAnalysisClaimPersistenceServiceTest",
    "com.taxonomy.interop.publication.IntegrationPublicationProjectionBudgetTest",
    "com.taxonomy.portfolio.PortfolioReportServiceTest",
    "com.taxonomy.portfolio.reformulation.ReformulationDecisionActionContractTest",
    "com.taxonomy.workspace.controller.WorkspaceScopedEndpointIsolationTest",
    "com.taxonomy.portfolio.RequirementTenantIsolationTest",
    "com.taxonomy.portfolio.reformulation.ReformulationBasePathContractTest",
    "com.taxonomy.portfolio.ProjectAnalysisSingleRequirementMvcContractTest",
    "com.taxonomy.portfolio.PortfolioApiContractTest",
    "com.taxonomy.portfolio.PortfolioAnalysisRecoveryAndLimitsTest",
    "com.taxonomy.composition.analysis.artemis.ArtemisProviderPermitQuarantineTest",
    "com.taxonomy.portfolio.inventory.InventoryReviewConcurrencyTest",
    "com.taxonomy.portfolio.PortfolioGitServiceTest",
    "com.taxonomy.portfolio.reformulation.ReformulationListContractTest");
   assertTrue(groups.containsKey("domain-integration"), "Missing domain-integration owner");
   var group=AppTestShardVerifier.object(groups.get("domain-integration"));
   assertEquals(expected,new HashSet<>(AppTestShardVerifier.strings(group.get("tests"))));
   assertEquals(true,group.get("requiresEmbeddingModel"));
   for(String suite:expected) assertEquals("domain-integration",assignments.get(suite),suite);
  }
  for(String anchor:List.of("com.taxonomy.composition.demo.ProductDemoEndToEndTest","com.taxonomy.ArchitectureExceptionLedgerTest")) assertEquals("core",assignments.get(anchor));
 }
}
