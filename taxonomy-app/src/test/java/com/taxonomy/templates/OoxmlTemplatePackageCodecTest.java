package com.taxonomy.templates;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OoxmlTemplatePackageCodecTest {

    private final OoxmlTemplatePackageCodec codec =
            new OoxmlTemplatePackageCodec();
    private Map<String, byte[]> validParts;

    @BeforeEach
    void loadBundledTemplate() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/" + DecisionRationaleTemplateContract.DEFAULT_RESOURCE)) {
            assertThat(input).isNotNull();
            validParts = codec.unpack(input).parts();
        }
    }

    @Test
    void deterministicPackageRoundTripPreservesEveryOoxmlPart() throws Exception {
        byte[] first = codec.pack(validParts);
        byte[] second = codec.pack(validParts);

        assertThat(second).containsExactly(first);
        OoxmlTemplatePackageCodec.PackageData roundTrip =
                codec.unpack(new ByteArrayInputStream(first));
        assertThat(roundTrip.parts().keySet())
                .containsExactlyInAnyOrderElementsOf(validParts.keySet());
        validParts.forEach((path, content) ->
                assertThat(roundTrip.parts().get(path))
                        .as(path)
                        .containsExactly(content));
    }

    @Test
    void rejectsMalformedMainDocumentXml() throws Exception {
        Map<String, byte[]> invalid = mutableParts();
        invalid.put("word/document.xml",
                "<w:document".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> codec.unpack(
                new ByteArrayInputStream(rawZip(invalid))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("word/document.xml");
    }

    @Test
    void rejectsPackageWithoutRootOfficeDocumentRelationship() throws Exception {
        Map<String, byte[]> invalid = mutableParts();
        String relationships = text(invalid, "_rels/.rels")
                .replace("/officeDocument\"", "/notOfficeDocument\"");
        assertThat(relationships).contains("notOfficeDocument");
        invalid.put("_rels/.rels", relationships.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> codec.unpack(
                new ByteArrayInputStream(rawZip(invalid))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root officeDocument relationship");
    }

    @Test
    void rejectsInternalRelationshipToMissingPackagePart() throws Exception {
        Map<String, byte[]> invalid = mutableParts();
        String relationships = text(invalid, "word/_rels/document.xml.rels")
                .replace("header1.xml", "missing-header.xml");
        assertThat(relationships).contains("missing-header.xml");
        invalid.put("word/_rels/document.xml.rels",
                relationships.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> codec.unpack(
                new ByteArrayInputStream(rawZip(invalid))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing package part");
    }

    @Test
    void rejectsCaseCollidingZipEntries() throws Exception {
        List<ArchivePart> entries = new ArrayList<>();
        validParts.forEach((path, content) ->
                entries.add(new ArchivePart(path, content)));
        entries.add(new ArchivePart(
                "WORD/document.xml",
                validParts.get("word/document.xml")));

        assertThatThrownBy(() -> codec.unpack(
                new ByteArrayInputStream(rawZip(entries))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("case-colliding");
    }

    private Map<String, byte[]> mutableParts() {
        return new LinkedHashMap<>(validParts);
    }

    private static String text(Map<String, byte[]> parts, String path) {
        assertThat(parts).containsKey(path);
        return new String(parts.get(path), StandardCharsets.UTF_8);
    }

    private static byte[] rawZip(Map<String, byte[]> parts) throws Exception {
        List<ArchivePart> entries = new ArrayList<>();
        parts.forEach((path, content) ->
                entries.add(new ArchivePart(path, content)));
        return rawZip(entries);
    }

    private static byte[] rawZip(List<ArchivePart> parts) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (ArchivePart part : parts) {
                zip.putNextEntry(new ZipEntry(part.path()));
                zip.write(part.content());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private record ArchivePart(String path, byte[] content) {
    }
}
