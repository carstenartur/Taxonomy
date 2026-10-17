package com.taxonomy.build;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** One JUnit execution per planned browser scenario, sharing the existing application groups. */
@EnabledIfSystemProperty(named="taxonomy.ui.skip",matches="false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UiScenarioIT {
    private Map<String,JsonNode> outcomes;
    private Throwable failure;
    private JsonNode report;
    private ExternalDriverSupport.Result execution;
    @TestFactory Stream<DynamicTest> browserScenarios() throws Exception {
        String plan=ExternalDriverSupport.run("run-ui-suite.mjs",List.of("--list-scenarios"),"ui-plan.log",1);
        String line=plan.lines().filter(value -> value.startsWith("JUNIT_UI_SCENARIOS ")).findFirst().orElseThrow();
        List<String> expected=new ArrayList<>();
        new ObjectMapper().readTree(line.substring("JUNIT_UI_SCENARIOS ".length())).forEach(value -> expected.add(value.asText()));
        assertFalse(expected.isEmpty(),"No browser scenarios selected");
        assertEquals(expected.size(),new HashSet<>(expected).size(),"Duplicate browser scenario owner");
        Stream<DynamicTest> cases=expected.stream().map(name -> DynamicTest.dynamicTest(name,() -> {
            executeOnce(expected);
            ExternalDriverSupport.assertObserved(outcomes,name,"outcome",failure,execution);
        }));
        return Stream.concat(cases,Stream.of(DynamicTest.dynamicTest("complete driver and scenario inventory",()-> {
            executeOnce(expected);
            if(failure!=null) throw new AssertionError("Required browser driver failed",failure);
            assertEquals(0,execution.exitCode(),"Browser driver failed; see target/junit-drivers/ui-scenarios.log");
            validateOutcomes(report,expected);
        })));
    }
    private static Map<String,JsonNode> readOutcomes(JsonNode report) {
        Map<String,JsonNode> actual=new LinkedHashMap<>();
        for(JsonNode group:report.path("groups")) for(JsonNode scenario:group.path("scenarios")) {
            String key=scenario.path("suite").asText()+"/"+scenario.path("id").asText();
            assertNull(actual.put(key,scenario),"Duplicate executed browser scenario: "+key);
        }
        return actual;
    }
    static Map<String,JsonNode> validateOutcomes(JsonNode report,List<String> expected) {
        var actual=readOutcomes(report);
        assertEquals(new HashSet<>(expected),actual.keySet(),"Missing or unexpected browser scenario execution");
        assertEquals(expected.size(),report.path("scenarioCount").asInt());
        assertEquals("passed",report.path("outcome").asText());
        for(var scenario:actual.values()) assertEquals("passed",scenario.path("outcome").asText());
        return actual;
    }
    private synchronized void executeOnce(List<String> expected) {
        if(outcomes!=null || failure!=null) return;
        try {
            Path output=ExternalDriverSupport.root().resolve(System.getenv().getOrDefault("TAXONOMY_UI_OUTPUT_ROOT","target/ui-verification")).normalize();
            Files.deleteIfExists(output.resolve("timings.json"));
            execution=ExternalDriverSupport.observe("run-ui-suite.mjs","ui-scenarios.log",35);
            report=new ObjectMapper().readTree(Files.readString(output.resolve("timings.json")));
            outcomes=readOutcomes(report);
        } catch(Throwable error) { failure=error; }
    }
}
