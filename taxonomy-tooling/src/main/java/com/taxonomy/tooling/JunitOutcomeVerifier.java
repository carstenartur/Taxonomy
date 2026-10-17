package com.taxonomy.tooling;

import org.w3c.dom.Element;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Recounts original reactor reports after all Maven test executions have finished. */
final class JunitOutcomeVerifier {
    private static final List<String> COUNTERS=List.of("tests","failures","errors","skipped");
    private static final List<String> OUTCOMES=List.of("failure","error","skipped");
    private static final Set<String> RETRIES=Set.of("rerunFailure","rerunError","flakyFailure","flakyError");
    static int run(String[] arguments,Path root,PrintStream output,PrintStream error) {
        if(arguments.length!=0) { error.println("Usage: check-junit-outcomes"); return 2; }
        try {
            List<Path> modules=new ArrayList<>(List.of(root));
            if(Files.isRegularFile(root.resolve("pom.xml"))) {
                Element project=XmlSupport.parse(root.resolve("pom.xml")).getDocumentElement();
                for(Element module:XmlSupport.children(XmlSupport.child(project,"modules"),"module")) modules.add(root.resolve(XmlSupport.text(module)));
            }
            long files=0,tests=0;
            for(Path module:modules) for(String directory:List.of("surefire-reports","failsafe-reports")) {
                Path reports=module.resolve("target").resolve(directory);
                if(!Files.isDirectory(reports)) continue;
                try(var paths=Files.list(reports)) {
                    for(Path report:paths.filter(p -> p.getFileName().toString().matches("TEST-.*\\.xml")).sorted().toList()) {
                        try { tests+=validate(XmlSupport.parse(report).getDocumentElement()).get("tests"); files++; }
                        catch(IllegalArgumentException failure) { throw new IllegalArgumentException(report+": "+failure.getMessage(),failure); }
                    }
                }
            }
            // Native browser callbacks are original JUnit executions, separate
            // from the ordinary tooling reports and from quality staging copies.
            Path browserReports=root.resolve(System.getenv().getOrDefault("TAXONOMY_UI_OUTPUT_ROOT","target/ui-verification")).normalize();
            if(Files.isDirectory(browserReports)) try(var paths=Files.walk(browserReports)) {
                for(Path report:paths.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().matches("TEST-.*\\.xml")).sorted().toList()) {
                    try { tests+=validate(XmlSupport.parse(report).getDocumentElement()).get("tests"); files++; }
                    catch(IllegalArgumentException failure) { throw new IllegalArgumentException(report+": "+failure.getMessage(),failure); }
                }
            }
            if(files==0 || tests==0) throw new IllegalArgumentException("No executed reactor JUnit report evidence");
            output.printf("Validated %d original reactor JUnit XML files and %d testcase occurrences%n",files,tests);
            return 0;
        } catch(IOException|IllegalArgumentException failure) {error.println(failure.getMessage());return 1;}
    }
    private static Map<String,Long> validate(Element document) {
        Map<String,Long> actual=suite(document);
        if(XmlSupport.descendants(document,"testcase").size()!=actual.get("tests")) throw new IllegalArgumentException("Testcase outside a supported suite");
        for(int i=0;i<OUTCOMES.size();i++) if(XmlSupport.descendants(document,OUTCOMES.get(i)).size()!=actual.get(COUNTERS.get(i+1))) throw new IllegalArgumentException("Misplaced terminal outcome: "+OUTCOMES.get(i));
        for(String retry:RETRIES) if(!XmlSupport.descendants(document,retry).isEmpty()) throw new IllegalArgumentException("Rerun/flaky evidence is not publishable");
        return actual;
    }
    private static Map<String,Long> suite(Element suite) {
        String tag=suite.getLocalName();
        if(!Set.of("testsuite","testsuites").contains(tag)) throw new IllegalArgumentException("Expected testsuite/testsuites root");
        if(suite.hasAttribute("flakes") && !suite.getAttribute("flakes").equals("0")) throw new IllegalArgumentException("Nonzero/invalid flaky suite counter");
        Map<String,Long> actual=new LinkedHashMap<>();COUNTERS.forEach(key -> actual.put(key,0L));
        for(var child=suite.getFirstChild();child!=null;child=child.getNextSibling()) if(child instanceof Element element) {
            if(Set.of("testsuite","testsuites").contains(element.getLocalName())) {
                var nested=suite(element);COUNTERS.forEach(key -> actual.merge(key,nested.get(key),Long::sum));
            } else if("testcase".equals(element.getLocalName())) {
                actual.merge("tests",1L,Long::sum);
                long outcomes=0;
                for(int i=0;i<OUTCOMES.size();i++) {long count=XmlSupport.children(element,OUTCOMES.get(i)).size();outcomes+=count;actual.merge(COUNTERS.get(i+1),count,Long::sum);}
                if(outcomes>1) throw new IllegalArgumentException("Multiple terminal testcase outcomes");
            }
        }
        if(tag.equals("testsuite") || COUNTERS.stream().anyMatch(suite::hasAttribute)) for(String key:COUNTERS) {
            String declared=suite.getAttribute(key);
            if(!declared.matches("[0-9]+") || Long.parseLong(declared)!=actual.get(key)) throw new IllegalArgumentException("Inconsistent JUnit "+key+"="+declared+", actual="+actual.get(key));
        }
        return actual;
    }
}
