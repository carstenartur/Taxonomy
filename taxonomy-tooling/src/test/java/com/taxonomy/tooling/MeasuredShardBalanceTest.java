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
}
