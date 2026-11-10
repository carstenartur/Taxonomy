package com.taxonomy;

import com.taxonomy.dsl.validation.RelationMetamodel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.StringReader;
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
                .contains("/webjars/d3/7.9.0/dist/d3.min.js")
                .contains("/webjars/jspdf/4.2.1/dist/jspdf.umd.min.js")
                .contains("/webjars/svg2pdf.js/2.2.0/dist/svg2pdf.umd.min.js")
                .contains("/css/taxonomy-ergonomics.css")
                .containsOnlyOnce("src=\"/webjars/d3/7.9.0/dist/d3.min.js\"")
                .doesNotContain("th:replace", "<th:block");
    }

    @Test
    void everyVersionedTemplateAssetExistsInTheResolvedWebJars() {
        assertPackagedResource("/META-INF/resources/webjars/bootstrap/5.3.8/dist/css/bootstrap.min.css");
        assertPackagedResource("/META-INF/resources/webjars/bootstrap/5.3.8/dist/js/bootstrap.bundle.min.js");
        assertPackagedResource("/META-INF/resources/webjars/d3/7.9.0/dist/d3.min.js");
        assertPackagedResource("/META-INF/resources/webjars/jspdf/4.2.1/dist/jspdf.umd.min.js");
        assertPackagedResource("/META-INF/resources/webjars/svg2pdf.js/2.2.0/dist/svg2pdf.umd.min.js");
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
