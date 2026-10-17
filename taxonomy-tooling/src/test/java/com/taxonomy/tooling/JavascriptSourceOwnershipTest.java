package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JavascriptSourceOwnershipTest {
    @TempDir Path fixture;
    @Test void everyJavascriptRegressionSourceHasExactlyOneExplicitJUnitOwner() throws Exception {
        Path root=JavascriptJUnitBridge.root();
        var owners=Files.readAllLines(root.resolve(".mvn/javascript-junit-sources.txt")).stream().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        assertOwned(root,owners);
    }
    @Test void newCommonJsOrOtherModuleRegressionsCannotSilentlyGoUnselected() throws Exception {
        Path script=fixture.resolve(".github/scripts/unowned.test.cjs"); Files.createDirectories(script.getParent());
        Files.writeString(script,"require('node:test').test('negative',()=>{});");
        Path other=fixture.resolve("taxonomy-other/src/test/js/regression.js");Files.createDirectories(other.getParent());
        Files.writeString(other,"import test from 'node:test'; test('negative',()=>{});");
        assertThrows(AssertionError.class,()->assertOwned(fixture,List.of()));
        assertOwned(fixture,List.of(".github/scripts/unowned.test.cjs","taxonomy-other/src/test/js/regression.js"));
        assertThrows(AssertionError.class,()->assertOwned(fixture,List.of(".github/scripts/unowned.test.cjs",".github/scripts/unowned.test.cjs","taxonomy-other/src/test/js/regression.js")));
    }
    private static void assertOwned(Path root,List<String> owners) throws Exception {
        assertEquals(owners.size(),new HashSet<>(owners).size(),"Duplicate JavaScript owner");
        Set<String> sources=new TreeSet<>();
        List<Path> directories=new ArrayList<>(List.of(root.resolve(".github/scripts")));
        try(var modules=Files.list(root)) {
            directories.addAll(modules.filter(Files::isDirectory).map(path -> path.resolve("src/test/js")).filter(Files::isDirectory).toList());
        }
        for(Path directory:directories) if(Files.isDirectory(directory)) try(var paths=Files.walk(directory)) {
            for(Path path:paths.filter(Files::isRegularFile).filter(p -> p.toString().matches(".*\\.(mjs|cjs|js)$")).toList()) {
                String name=path.getFileName().toString(), source=Files.readString(path);
                boolean declarations=source.matches("(?s).*(?:from\\s*['\"]node:test['\"]|require\\(\\s*['\"]node:test['\"]\\s*\\)).*");
                if(name.contains(".test.") || (directory.endsWith("scripts") && name.startsWith("test-")) || declarations) sources.add(root.relativize(path).toString());
            }
        }
        assertEquals(sources,new TreeSet<>(owners),"Unowned or stale JavaScript test source; add its JUnit owner in .mvn/javascript-junit-sources.txt");
    }
}
