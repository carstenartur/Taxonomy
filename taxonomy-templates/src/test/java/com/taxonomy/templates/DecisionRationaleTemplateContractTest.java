package com.taxonomy.templates;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionRationaleTemplateContractTest {

    private final OoxmlTemplatePackageCodec codec =
            new OoxmlTemplatePackageCodec();
    private final DecisionRationaleTemplateContract contract =
            new DecisionRationaleTemplateContract();

    @Test
    void bundledDefaultIsAValidMacroFreeDecisionReportTemplate() throws Exception {
        OoxmlTemplatePackageCodec.PackageData packageData;
        try (InputStream input = resource()) {
            packageData = codec.unpack(input);
        }

        assertThatCode(() -> contract.validate(packageData.parts()))
                .doesNotThrowAnyException();
        assertThat(packageData.parts()).containsKeys(
                "[Content_Types].xml",
                "word/document.xml",
                "word/styles.xml",
                "word/header1.xml",
                "word/footer1.xml");
        String documentXml = new String(
                packageData.parts().get("word/document.xml"),
                StandardCharsets.UTF_8);
        assertThat(documentXml)
                .contains(DecisionRationaleTemplateContract.TITLE_TOKEN)
                .contains(DecisionRationaleTemplateContract.REQUIREMENT_TOKEN)
                .contains(DecisionRationaleTemplateContract.BODY_MARKER);
    }

    @Test
    void contractRejectsAWordTemplateWhoseBodyMarkerWasRemoved() throws Exception {
        OoxmlTemplatePackageCodec.PackageData packageData;
        try (InputStream input = resource()) {
            packageData = codec.unpack(input);
        }
        TreeMap<String, byte[]> invalid = new TreeMap<>(packageData.parts());
        String documentXml = new String(
                invalid.get("word/document.xml"), StandardCharsets.UTF_8)
                .replace(DecisionRationaleTemplateContract.BODY_MARKER, "removed");
        invalid.put("word/document.xml",
                documentXml.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> contract.validate(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(DecisionRationaleTemplateContract.BODY_MARKER);
    }

    private static InputStream resource() {
        InputStream input = DecisionRationaleTemplateContractTest.class
                .getResourceAsStream(
                        "/" + DecisionRationaleTemplateContract.DEFAULT_RESOURCE);
        assertThat(input).as("bundled default decision report template").isNotNull();
        return input;
    }
}
