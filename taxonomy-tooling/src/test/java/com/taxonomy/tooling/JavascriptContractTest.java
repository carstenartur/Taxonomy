package com.taxonomy.tooling;

import org.junit.jupiter.api.*;
import org.opentest4j.TestAbortedException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Each original JS callback is a real JUnit execution; JS provides browser/VM drivers only. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JavascriptContractTest {
    private final List<JavascriptJUnitBridge> drivers = new ArrayList<>();
    @TestFactory Stream<DynamicNode> javascriptContracts() throws Exception {
        Path root=JavascriptJUnitBridge.root();
        List<String> sources=Files.readAllLines(root.resolve(".mvn/javascript-junit-sources.txt")).stream().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        String mode=System.getProperty("taxonomy.javascript.mode","contracts");
        String filter=System.getProperty("taxonomy.javascript.filter","");
        String requested=System.getProperty("taxonomy.javascript.sources","");
        Set<String> selected=requested.isBlank() ? Set.of() : Set.of(requested.split(","));
        assertTrue(sources.containsAll(selected),"Requested unowned JavaScript source");
        List<DynamicNode> result=new ArrayList<>();
        for(String source:sources) {
            boolean browser=source.contains(".browser.test.");
            if(browser != mode.equals("browser") || (!filter.isBlank() && !source.contains(filter)) || (!selected.isEmpty() && !selected.contains(source))) continue;
            Iterator<DynamicNode> cases=new Iterator<>() {
                int index;
                JavascriptJUnitBridge bridge;
                private void initialize() {
                    if(bridge==null) try {
                        bridge=new JavascriptJUnitBridge(root.resolve(source)); drivers.add(bridge);
                    } catch(Exception error) { throw new IllegalStateException("Cannot discover owned callbacks: "+source,error); }
                }
                final Deque<JavascriptJUnitBridge.Outcome> nested=new ArrayDeque<>();
                public boolean hasNext() {
                    initialize();
                    boolean available=index<bridge.names().size() || !nested.isEmpty();
                    if(!available) try { bridge.close(); } catch(Exception error) { throw new IllegalStateException("JavaScript cleanup failed: "+source,error); }
                    return available;
                }
                public DynamicNode next() {
                    initialize();
                    if(!nested.isEmpty()) {
                        var outcome=nested.removeFirst();
                        return DynamicTest.dynamicTest(outcome.name(), () -> assertOutcome(outcome));
                    }
                    int selected=index++;
                    return DynamicTest.dynamicTest(bridge.names().get(selected), () -> {
                        var outcomes=bridge.execute(selected);
                        assertFalse(outcomes.isEmpty(),"Driver dropped callback outcome");
                        nested.addAll(outcomes.subList(1,outcomes.size()));
                        assertOutcome(outcomes.getFirst());
                    });
                }
            };
            result.add(DynamicContainer.dynamicContainer(source, () -> cases));
        }
        assertFalse(result.isEmpty(),"No owned JavaScript sources selected");
        return result.stream();
    }
    static void assertOutcome(JavascriptJUnitBridge.Outcome outcome) {
        if(outcome.status().equals("skipped")) throw new TestAbortedException(outcome.detail());
        assertEquals("passed",outcome.status(),outcome.name()+"\n"+outcome.detail());
    }
    @AfterAll void closeDrivers() throws Exception {
        Exception failure=null;
        for(var bridge:drivers) try { bridge.close(); } catch(Exception error) { if(failure==null) failure=error; else failure.addSuppressed(error); }
        if(failure!=null) throw failure;
    }
}
