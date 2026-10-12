package com.taxonomy.composition.backup;

import com.taxonomy.dsl.parser.TaxDslParser;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.assertj.core.api.Assertions.*;

class PortableDocumentSourceReferencesTest {
    private static final String BASE = """
            requirement fixture-requirement {
              text: "Current requirement";
            }
            source fixture-source {
              title: "Selected source";
            }
            source fixture-other-source {
              title: "Other source";
            }
            sourceVersion fixture-version {
              source: fixture-source;
            }
            sourceVersion fixture-other-version {
              source: fixture-other-source;
            }
            sourceFragment fixture-fragment {
              sourceVersion: fixture-version;
            }
            """;
    @Test void closesExactDocumentReferencesIncludingImplicitFragmentVersion() throws Exception {
        verify(BASE + link("fixture-requirement", "fixture-source", "", "fixture-fragment"));
    }
    @Test void rejectsMissingRequirementAndContradictorySourceOrVersion() {
        for (String document : new String[] {
                BASE + link("foreign-requirement", "fixture-source", "", "fixture-fragment"),
                BASE + link("fixture-requirement", "fixture-other-source", "", "fixture-fragment"),
                BASE + link("fixture-requirement", "fixture-source", "fixture-other-version", "fixture-fragment") })
            assertThatThrownBy(() -> verify(document)).isInstanceOf(IOException.class);
    }
    @Test void rejectsCyclesAndFragmentParentsFromAnotherVersion() {
        assertThatThrownBy(() -> verify(BASE.replace("sourceVersion: fixture-version;\n}",
                "sourceVersion: fixture-version;\n  parentFragment: fixture-fragment;\n}"))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> verify(BASE + """
                sourceFragment fixture-child {
                  sourceVersion: fixture-other-version;
                  parentFragment: fixture-fragment;
                }
                """)).isInstanceOf(IOException.class);
    }
    private static void verify(String dsl) throws IOException {
        PortableDocumentSourceReferences.requireClosed(new TaxDslParser().parse(dsl, "fixture.taxdsl"));
    }
    private static String link(String requirement, String source, String version, String fragment) {
        return "requirementSourceLink fixture-link {\n  requirement: " + requirement + ";\n  source: " + source
                + ";\n" + (version.isEmpty() ? "" : "  sourceVersion: " + version + ";\n")
                + "  sourceFragment: " + fragment + ";\n}\n";
    }
}
