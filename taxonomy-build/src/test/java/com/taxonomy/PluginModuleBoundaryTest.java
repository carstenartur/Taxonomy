package com.taxonomy;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import static org.assertj.core.api.Assertions.*;

/** Physical module boundaries are measured from production sources and direct Maven dependencies. */
class PluginModuleBoundaryTest {
    private static final Set<String> SDKS=Set.of("taxonomy-domain","taxonomy-reporting-api","taxonomy-templates-api","taxonomy-extension-api","taxonomy-dsl","taxonomy-export");
    @Test void realDistributionHasOneOwnerPerJavaPackage() throws Exception {
        var owners=new TreeMap<String,Set<String>>();var packages=Pattern.compile("(?m)^package\\s+([a-zA-Z0-9_.]+)\\s*;");
        for(var module:modules().entrySet()) {
            var sources=module.getValue().resolve("src/main/java");if(!Files.isDirectory(sources))continue;
            try(var files=Files.walk(sources)) {
                for(var file:files.filter(f->f.toString().endsWith(".java")).toList()) {
                    var match=packages.matcher(Files.readString(file));
                    if(match.find())owners.computeIfAbsent(match.group(1),k->new TreeSet<>()).add(module.getKey());
                }
            }
        }
        assertThat(splitPackages(owners)).isEmpty();
    }
    @Test void realPublicSdksDependOnlyOnFrameworkFreeReactorArtifacts() throws Exception {
        var graph=new TreeMap<String,Set<String>>();var modules=modules();
        for(var module:modules.entrySet()) {
            var document=read(module.getValue().resolve("pom.xml"));var dependencies=new TreeSet<String>();
            for(var child=document.getDocumentElement().getFirstChild();child!=null;child=child.getNextSibling()) {
                if(!(child instanceof Element element)||!element.getTagName().equals("dependencies"))continue;
                for(var node=element.getFirstChild();node!=null;node=node.getNextSibling()) {
                    if(!(node instanceof Element dependency))continue;
                    String scope=text(dependency,"scope"),id=text(dependency,"artifactId");
                    if(!scope.equals("test")&&modules.containsKey(id))dependencies.add(id);
                }
            }
            graph.put(module.getKey(),dependencies);
        }
        assertThat(sdkViolations(graph)).isEmpty();
    }
    @Test void aSplitPackageFixtureFailsEvenWithoutAnImportBetweenOwners() {
        assertThat(splitPackages(Map.of("example.api",Set.of("sdk","host")))).containsExactly("example.api: [host, sdk]");
    }
    @Test void anSdkToHostFixtureFailsEvenIfTheMavenGraphIsAcyclic() {
        assertThat(sdkViolations(Map.of("taxonomy-extension-api",Set.of("taxonomy-app")))).containsExactly("taxonomy-extension-api -> taxonomy-app");
    }
    private static List<String> splitPackages(Map<String,Set<String>> owners) {
        return owners.entrySet().stream().filter(e->e.getValue().size()>1).map(e->e.getKey()+": "+new TreeSet<>(e.getValue())).sorted().toList();
    }
    private static List<String> sdkViolations(Map<String,Set<String>> graph) {
        return graph.entrySet().stream().filter(e->SDKS.contains(e.getKey())).flatMap(e->e.getValue().stream()
                .filter(target->!SDKS.contains(target)).map(target->e.getKey()+" -> "+target)).sorted().toList();
    }
    private static Map<String,Path> modules() throws Exception {
        Path root=Path.of("").toAbsolutePath();while(root!=null&&!Files.exists(root.resolve(".github/architecture-contexts.json")))root=root.getParent();
        if(root==null)throw new IllegalStateException("Repository root not found");
        var result=new TreeMap<String,Path>();var modules=read(root.resolve("pom.xml")).getElementsByTagName("module");
        for(int i=0;i<modules.getLength();i++) {var path=root.resolve(modules.item(i).getTextContent().trim());var pom=read(path.resolve("pom.xml"));
            for(var child=pom.getDocumentElement().getFirstChild();child!=null;child=child.getNextSibling())
                if(child instanceof Element e&&e.getTagName().equals("artifactId"))result.put(e.getTextContent().trim(),path);
        }
        return result;
    }
    private static org.w3c.dom.Document read(Path path) throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        return factory.newDocumentBuilder().parse(path.toFile());
    }
    private static String text(Element element,String name) {var values=element.getElementsByTagName(name);return values.getLength()==0?"":values.item(0).getTextContent().trim();}
}
