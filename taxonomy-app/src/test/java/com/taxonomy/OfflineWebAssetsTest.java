package com.taxonomy;

import com.taxonomy.dsl.validation.RelationMetamodel;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

import static org.assertj.core.api.Assertions.assertThat;

/** Ensures that the primary browser application can be rendered without public CDNs. */
class OfflineWebAssetsTest {

    @Test
    void renderedRelationSelectorsContainEveryMetamodelTypeExactlyOnceAndPreserveTheirDefaults() throws Exception {
        var options = relationOptions(renderIndex());
        var types = RelationMetamodel.relationTypes().stream().sorted().toList();
        assertThat(types).contains("REQUIRES");
        assertThat(options.keySet()).containsExactlyInAnyOrder("newRelType", "relationsTypeFilter", "proposeRelationType");
        for (String id : List.of("newRelType", "proposeRelationType")) {
            assertThat(options.get(id)).extracting(Option::value).as(id).containsExactlyInAnyOrderElementsOf(types);
            assertThat(options.get(id).stream().filter(Option::selected)).extracting(Option::value)
                    .as("default selection for %s", id).containsExactly("REALIZES");
        }
        var filterValues = new ArrayList<String>();
        filterValues.add("");
        filterValues.addAll(types);
        assertThat(options.get("relationsTypeFilter")).extracting(Option::value).containsExactlyInAnyOrderElementsOf(filterValues);
        assertThat(options.get("relationsTypeFilter").stream().filter(Option::selected)).extracting(Option::value)
                .containsExactly("");
    }

    private record Option(String value, boolean selected) {}

    private static Map<String, List<Option>> relationOptions(String html) throws Exception {
        Map<String, List<Option>> options = new LinkedHashMap<>();
        var selectors = Set.of("newRelType", "relationsTypeFilter", "proposeRelationType");
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            private String current;
            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                if (tag == HTML.Tag.SELECT) {
                    String id = (String) attributes.getAttribute(HTML.Attribute.ID);
                    current = id != null && selectors.contains(id) ? id : null;
                    if (current != null) options.put(current, new ArrayList<>());
                } else if (tag == HTML.Tag.OPTION && current != null) {
                    options.get(current).add(new Option((String) attributes.getAttribute(HTML.Attribute.VALUE),
                            attributes.isDefined(HTML.Attribute.SELECTED)));
                }
            }
            @Override public void handleEndTag(HTML.Tag tag, int position) {
                if (tag == HTML.Tag.SELECT) current = null;
            }
        }, true);
        return options;
    }

    @Test
    void indexUsesOnlyPackagedBrowserLibraries() {
        String html = renderIndex();

        assertThat(html)
                .doesNotContain("cdn.jsdelivr.net")
                .doesNotContain("cdnjs.cloudflare.com")
                .doesNotContain("unpkg.com")
                .contains("/webjars/bootstrap/5.3.8/dist/css/bootstrap.min.css")
                .contains("/webjars/bootstrap/5.3.8/dist/js/bootstrap.bundle.min.js")
                .contains("/webjars/d3/7.9.2/dist/d3.min.js")
                .contains("/webjars/jspdf/4.2.1/dist/jspdf.umd.min.js")
                .contains("/webjars/svg2pdf.js/2.2.0/dist/svg2pdf.umd.min.js")
                .contains("/css/taxonomy-ergonomics.css")
                .containsOnlyOnce("src=\"/webjars/d3/7.9.2/dist/d3.min.js\"")
                .doesNotContain("th:replace", "<th:block");
    }

    @Test
    void everyVersionedTemplateAssetExistsInTheResolvedWebJars() {
        assertPackagedResource("/META-INF/resources/webjars/bootstrap/5.3.8/dist/css/bootstrap.min.css");
        assertPackagedResource("/META-INF/resources/webjars/bootstrap/5.3.8/dist/js/bootstrap.bundle.min.js");
        assertPackagedResource("/META-INF/resources/webjars/d3/7.9.2/dist/d3.min.js");
        assertPackagedResource("/META-INF/resources/webjars/jspdf/4.2.1/dist/jspdf.umd.min.js");
        assertPackagedResource("/META-INF/resources/webjars/svg2pdf.js/2.2.0/dist/svg2pdf.umd.min.js");
    }

    @Test
    void selectedBrowserLibraryRangesHaveFixedManagedVersions() throws Exception {
        Map<String, Document> selected = selectedWebJarPoms();
        Map<String, String> managed = managedWebJarVersions();
        Set<String> rangedTargets = new LinkedHashSet<>();
        for (var source : selected.entrySet()) {
            for (Node dependency : nodes(source.getValue(),
                    "/project/dependencies/dependency[groupId='org.webjars.npm']")) {
                String scope = text(dependency, "scope");
                if (!scope.isEmpty() && !scope.equals("compile") && !scope.equals("runtime")) continue;
                String target = text(dependency, "artifactId");
                if (text(dependency, "optional").equals("true") && !selected.containsKey(target)) continue;
                assertThat(selected).as("selected dependency of %s", source.getKey()).containsKey(target);
                String version = text(dependency, "version");
                if (version.startsWith("[") || version.startsWith("(")) rangedTargets.add(target);
            }
        }
        assertThat(rangedTargets).as("the actual WebJar closure must exercise range resolution").isNotEmpty();
        Set<String> unmanaged = new LinkedHashSet<>(rangedTargets);
        unmanaged.removeAll(managed.keySet());
        assertThat(unmanaged).as("WebJar ranges must not depend on remote version metadata").isEmpty();
        for (String target : rangedTargets) {
            String version = managed.get(target);
            assertThat(version).as("fixed managed version for %s", target)
                    .isNotBlank().doesNotContain("${", "[", "]", "(", ")", ",")
                    .isNotIn("LATEST", "RELEASE");
            assertThat(text(selected.get(target), "/project/version"))
                    .as("actually selected WebJar version for %s", target).isEqualTo(version);
        }
    }

    @Test
    void optionalJsPdfHelpersRemainOutsideTheSelectedBrowserLibraries() throws Exception {
        Map<String, Document> selected = selectedWebJarPoms();
        Set<String> optional = new LinkedHashSet<>();
        for (Node dependency : nodes(selected.get("jspdf"),
                "/project/dependencies/dependency[groupId='org.webjars.npm' and optional='true']")) {
            optional.add(text(dependency, "artifactId"));
        }
        assertThat(optional).as("jsPDF's declared optional browser integrations").isNotEmpty();
        assertThat(selected.keySet()).doesNotContainAnyElementsOf(optional);
    }

    private static Map<String, Document> selectedWebJarPoms() throws Exception {
        Map<String, Document> selected = new LinkedHashMap<>();
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) classpath = System.getProperty("java.class.path");
        assertThat(classpath).as("actual test classpath").isNotBlank();
        Set<Path> inspected = new LinkedHashSet<>();
        String prefix = "META-INF/maven/org.webjars.npm/";
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
            Path path = Path.of(entry).toAbsolutePath().normalize();
            if (!entry.endsWith(".jar") || !Files.isRegularFile(path) || !inspected.add(path)) continue;
            // A JAR need not contain directory entries for its embedded Maven POM.
            try (var jar = new JarFile(path.toFile())) {
                for (var pomEntry : jar.stream().filter(candidate -> {
                    String name = candidate.getName();
                    return name.startsWith(prefix) && name.endsWith("/pom.xml")
                            && !name.substring(prefix.length(), name.length() - "/pom.xml".length()).contains("/");
                }).toList()) {
                    try (InputStream input = jar.getInputStream(pomEntry)) {
                        Document pom = readPom(input);
                        assertThat(text(pom, "/project/groupId")).isEqualTo("org.webjars.npm");
                        String artifact = text(pom, "/project/artifactId");
                        assertThat(selected.putIfAbsent(artifact, pom))
                                .as("one selected WebJar version for %s", artifact).isNull();
                    }
                }
            }
        }
        assertThat(selected).as("actual browser library POMs on the test classpath")
                .containsKeys("bootstrap", "d3", "jspdf", "svg2pdf.js");
        return selected;
    }

    private static Map<String, String> managedWebJarVersions() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("taxonomy-app/pom.xml"))) root = root.getParent();
        assertThat(root).as("Taxonomy repository root").isNotNull();
        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, String> managed = new LinkedHashMap<>();
        for (Path path : List.of(root.resolve("pom.xml"), root.resolve("taxonomy-app/pom.xml"))) {
            try (InputStream input = Files.newInputStream(path)) {
                Document pom = readPom(input);
                for (Node property : nodes(pom, "/project/properties/*")) {
                    properties.put(property.getNodeName(), property.getTextContent().trim());
                }
                for (Node dependency : nodes(pom,
                        "/project/dependencyManagement/dependencies/dependency[groupId='org.webjars.npm']")) {
                    managed.put(text(dependency, "artifactId"), text(dependency, "version"));
                }
            }
        }
        for (var entry : managed.entrySet()) {
            String version = entry.getValue();
            for (int depth = 0; depth < 10 && version.startsWith("${") && version.endsWith("}"); depth++) {
                version = properties.get(version.substring(2, version.length() - 1));
                assertThat(version).as("resolved managed property for %s", entry.getKey()).isNotBlank();
            }
            entry.setValue(version);
        }
        return managed;
    }

    private static Document readPom(InputStream input) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(input);
    }

    private static List<Node> nodes(Node context, String expression) throws Exception {
        var values = (NodeList) XPathFactory.newInstance().newXPath()
                .evaluate(expression, context, XPathConstants.NODESET);
        List<Node> result = new ArrayList<>();
        for (int i = 0; i < values.getLength(); i++) result.add(values.item(i));
        return result;
    }

    private static String text(Node context, String expression) throws Exception {
        return XPathFactory.newInstance().newXPath().evaluate(expression, context).trim();
    }

    private void assertPackagedResource(String path) {
        assertThat(getClass().getResource(path))
                .as("versioned browser asset %s must be present in the resolved WebJar", path)
                .isNotNull();
    }

    private String renderIndex() {
        return IndexPageFixture.render();
    }
}
