package com.taxonomy.build;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DriverOutcomeChecksTest {
 @Test void acceptsEveryScenarioExactlyOnce() throws Exception {
  var report=new ObjectMapper().readTree("{\"outcome\":\"passed\",\"scenarioCount\":2,\"groups\":[{\"scenarios\":[{\"suite\":\"ui\",\"id\":\"one\",\"outcome\":\"passed\"},{\"suite\":\"ui\",\"id\":\"two\",\"outcome\":\"passed\"}]}]}");
  assertEquals(Set.of("ui/one","ui/two"),UiScenarioIT.validateOutcomes(report,List.of("ui/one","ui/two")).keySet());
 }
 @Test void missingDuplicateAndFailedScenariosFailClosed() throws Exception {
  for(String cases:List.of("{\"suite\":\"ui\",\"id\":\"one\",\"outcome\":\"passed\"}","{\"suite\":\"ui\",\"id\":\"one\",\"outcome\":\"passed\"},{\"suite\":\"ui\",\"id\":\"one\",\"outcome\":\"passed\"}","{\"suite\":\"ui\",\"id\":\"one\",\"outcome\":\"failed\"},{\"suite\":\"ui\",\"id\":\"two\",\"outcome\":\"passed\"}")) {
   var report=new ObjectMapper().readTree("{\"outcome\":\"passed\",\"scenarioCount\":2,\"groups\":[{\"scenarios\":["+cases+"]}]}");
   assertThrows(AssertionError.class,()->UiScenarioIT.validateOutcomes(report,List.of("ui/one","ui/two")));
  }
 }
 @Test void missingOrFailedProductRoundTripCannotBecomeGreen() throws Exception {
  for(String results:List.of("{\"product\":\"StrictDoc\",\"result\":\"passed\"}","{\"product\":\"StrictDoc\",\"result\":\"passed\"},{\"product\":\"Archi\",\"result\":\"failed\"}")) {
   var report=new ObjectMapper().readTree("{\"status\":\"passed\",\"results\":["+results+"]}");
   assertThrows(AssertionError.class,()->InteroperabilityProductsIT.validateOutcomes(report));
  }
 }
 @Test void sharedFailurePreservesPassedFailedAndUnexecutedIndividualOutcomes() throws Exception {
  var mapper=new ObjectMapper();
  var outcomes=Map.of("StrictDoc",mapper.readTree("{\"result\":\"passed\"}"),"Archi",mapper.readTree("{\"result\":\"failed\"}"));
  var failedDriver=new ExternalDriverSupport.Result(1,"one product failed");
  assertDoesNotThrow(()->ExternalDriverSupport.assertObserved(outcomes,"StrictDoc","result",null,failedDriver));
  assertThrows(AssertionError.class,()->ExternalDriverSupport.assertObserved(outcomes,"Archi","result",null,failedDriver));
  assertThrows(org.opentest4j.TestAbortedException.class,()->ExternalDriverSupport.assertObserved(outcomes,"unexecuted","result",null,failedDriver));
 }
 @Test void realDriverFailureIsAJUnitFailure() {
  assertThrows(AssertionError.class,()->ExternalDriverSupport.command(List.of("bash","-c","exit 7"),"expected-negative-driver.log",1));
 }
}
