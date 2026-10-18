package com.taxonomy.analysis.recovery;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.taxonomy.dto.LlmCallDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.*;

class AnalysisCheckpointPayloadCodecTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final AnalysisCheckpointPayloadCodec codec = new AnalysisCheckpointPayloadCodec(json);
    private AnalysisCheckpointPayloadCodec.Layout encoded() { return codec.parseLayout(codec.encodeNodes(List.of("BP-1017"))); }

    @Test void legacyLayoutNeverInterpretsTheContentsOfFreeFormEvidenceAsAnEnvelope() {
        var legacy = codec.parseLayout("[\"BP-1017\",\"IP-1116\"]");
        assertThat(legacy.encoded()).isFalse();
        assertThat(legacy.nodes()).containsExactly("BP-1017", "IP-1116");
        String looksEncoded = "{\"format\":\"gzip-utf16be-v1\",\"characters\":1,\"sha256\":\"untrusted\",\"data\":\"raw source text\"}";
        assertThat(codec.decodePrompt(legacy, looksEncoded)).isEqualTo(looksEncoded);
        assertThat(codec.decodeDetail(legacy, looksEncoded, "anything")).isEqualTo(looksEncoded);
    }

    @Test void encodedLayoutRetainsExactNodeOrderAndHasAnImmutableList() {
        var input = new ArrayList<>(List.of("BP-1529", "IP-1116", "CI-1052"));
        String stored = codec.encodeNodes(input); input.clear();
        var layout = codec.parseLayout(stored);
        assertThat(layout.encoded()).isTrue();
        assertThat(layout.nodes()).containsExactly("BP-1529", "IP-1116", "CI-1052");
        assertThatThrownBy(() -> layout.nodes().add("foreign")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(codec.encodeNodes(layout.nodes())).isEqualTo(stored);
    }

    @ParameterizedTest @ValueSource(strings = {
            "null", "{}", "\"BP\"", "[1]", "[null]", "[\"BP\"] []",
            "{\"format\":\"future-v9\",\"nodes\":[\"BP\"]}",
            "{\"format\":\"future-v9\",\"format\":\"future-v9\",\"nodes\":[\"BP\"]}"})
    void malformedOrUnknownLayoutsNeverFallBackToLegacy(String stored) {
        assertThatThrownBy(() -> codec.parseLayout(stored)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void nullEvidenceRemainsNullInBothLayouts() {
        assertThat(codec.encodePrompt(null)).isNull();
        assertThat(codec.encodeDetail(null, "source")).isNull();
        for (var layout : List.of(encoded(), codec.parseLayout("[]"))) {
            assertThat(codec.decodePrompt(layout, null)).isNull();
            assertThat(codec.decodeDetail(layout, null, "source")).isNull();
        }
    }

    @ParameterizedTest @MethodSource("sourceTexts")
    void fullTextPreservesEveryJavaCodeUnit(String source) {
        String stored = codec.encodePrompt(source);
        assertThat(codec.decodePrompt(encoded(), stored)).isEqualTo(source);
        assertThat(codec.encodePrompt(source)).isEqualTo(stored);
    }
    static Stream<String> sourceTexts() {
        return Stream.of("", "Original requirement", "A\0B\r\n\t\b\f\\\"Grün Ω 🌊", "orphan high \ud800 tail", "orphan low \udfff tail");
    }

    @Test void theEntireUtf16CodeUnitSpaceSurvivesStorage() {
        char[] all = new char[65_536]; for (int i = 0; i < all.length; i++) all[i] = (char) i;
        String source = new String(all);
        assertThat(codec.decodePrompt(encoded(), codec.encodePrompt(source))).isEqualTo(source);
    }

    @Test void theExistingTwoMillionDecodedCharacterBoundaryIsExact() {
        String maximum = "Ω".repeat(2_000_000);
        assertThat(codec.decodePrompt(encoded(), codec.encodePrompt(maximum))).isEqualTo(maximum);
        assertThatThrownBy(() -> codec.encodePrompt(maximum + "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.encodeDetail(maximum + "x", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void equalCanonicalDetailPromptUsesAnExactReferenceAndPreservesAllOtherEvidence() {
        String prompt = "original \"prompt\"\n" + "full candidate and ancestor descriptions ".repeat(1_000);
        String original = detail(prompt, "raw response with \"prompt\": marker", Map.of("IP-1116", "uncertain\nwith complete quotation"));
        String stored = codec.encodeDetail(original, prompt);
        assertThat(json.readTree(stored).path("format").asString()).contains("prompt-ref");
        assertThat(codec.decodeDetail(encoded(), stored, prompt)).isEqualTo(original);
        assertThatThrownBy(() -> codec.decodeDetail(encoded(), stored, prompt + "changed")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @MethodSource("detailPrompts")
    void absentOrDifferentDetailPromptsRemainExact(String detailPrompt) {
        String original = detail(detailPrompt, "response", Map.of());
        String stored = codec.encodeDetail(original, "retained question");
        assertThat(json.readTree(stored).path("format").asString()).doesNotContain("prompt-ref");
        assertThat(codec.decodeDetail(encoded(), stored, "any other question")).isEqualTo(original);
    }
    static Stream<String> detailPrompts() { return Stream.of(null, "", "another source"); }

    @Test void noncanonicalWhitespaceUnknownFieldsAndMalformedDetailsArePreservedFully() {
        String canonical = detail("same", "response", Map.of());
        for (String original : List.of(" " + canonical + "\n", "{\"unknown\":7,\"prompt\":\"same\"}", "broken JSON {source quote")) {
            String stored = codec.encodeDetail(original, "same");
            assertThat(json.readTree(stored).path("format").asString()).doesNotContain("prompt-ref");
            assertThat(codec.decodeDetail(encoded(), stored, null)).isEqualTo(original);
        }
    }

    @Test void futureDtoSerializationCannotChangeAnAlreadyWrittenDetailReference() {
        String prompt = "quote \" \\ \u000b \ud800 🌊";
        String original = detail(prompt, "retained raw response", Map.of("BP-1017", "full reason"));
        String stored = codec.encodeDetail(original, prompt);
        assertThat(json.readTree(stored).path("format").asString()).contains("prompt-ref");
        var futureMapper = JsonMapper.builder().addMixIn(LlmCallDetail.class, FutureDetailSerialization.class).build();
        var futureReader = new AnalysisCheckpointPayloadCodec(futureMapper);
        assertThat(futureReader.decodeDetail(encoded(), stored, prompt)).isEqualTo(original);
    }
    abstract static class FutureDetailSerialization { @JsonIgnore abstract String getPrompt(); @JsonIgnore abstract Map<String, Double> getScores(); }

    @Test void repeatedPromptMarkersInOtherFieldsCannotSelectTheWrongJsonString() {
        String prompt = "{\"prompt\":\"inside the requirement\"}";
        String original = detail(prompt, "{\"prompt\":\"inside raw response\"}", Map.of("prompt", "nested reason"));
        assertThat(codec.decodeDetail(encoded(), codec.encodeDetail(original, prompt), prompt)).isEqualTo(original);
    }

    @ParameterizedTest @ValueSource(strings = {"format", "characters", "sha256", "data", "unknown", "fractional", "negative", "oversized"})
    void alteredFullPayloadMetadataOrCompressedDataIsRejected(String mutation) {
        String stored = change(codec.encodePrompt("retained source"), node -> {
            switch (mutation) {
                case "format" -> node.put("format", "future-v9");
                case "characters" -> node.put("characters", 1);
                case "sha256" -> node.put("sha256", "0".repeat(64));
                case "data" -> node.put("data", "not base64!");
                case "unknown" -> node.put("ignoredField", true);
                case "fractional" -> node.put("characters", 1.5);
                case "negative" -> node.put("characters", -1);
                case "oversized" -> node.put("characters", Integer.MAX_VALUE);
            }
        });
        assertThatThrownBy(() -> codec.decodePrompt(encoded(), stored)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void compressedBombAndOddCodeUnitByteCountsAreRejected() throws Exception {
        String shortPayload = codec.encodePrompt("x");
        for (byte[] bytes : List.of(new byte[]{1}, new byte[4_000_002])) {
            String poisoned = change(shortPayload, node -> node.put("data", gzip(bytes)));
            assertThatThrownBy(() -> codec.decodePrompt(encoded(), poisoned)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void truncatedConcatenatedOrTrailingGzipDataIsRejected() {
        String valid = codec.encodePrompt("source");
        byte[] raw = Base64.getDecoder().decode(json.readTree(valid).path("data").asString());
        byte[] concatenated = new byte[raw.length * 2]; System.arraycopy(raw, 0, concatenated, 0, raw.length); System.arraycopy(raw, 0, concatenated, raw.length, raw.length);
        for (byte[] changed : List.of(Arrays.copyOf(raw, raw.length - 1), Arrays.copyOf(raw, raw.length + 1), concatenated)) {
            String poisoned = change(valid, node -> node.put("data", Base64.getEncoder().encodeToString(changed)));
            assertThatThrownBy(() -> codec.decodePrompt(encoded(), poisoned)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void duplicateEnvelopePropertiesAndAdditionalJsonDocumentsAreRejected() {
        String valid = codec.encodePrompt("source");
        String duplicate = valid.substring(0, valid.length() - 1) + ",\"characters\":6}";
        for (String value : List.of(duplicate, valid + " {}"))
            assertThatThrownBy(() -> codec.decodePrompt(encoded(), value)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"offset", "length", "hash", "missing-prompt", "escaped-length"})
    void invalidReferenceMetadataOrSourceCannotProduceDifferentEvidence(String mutation) {
        String prompt = "original source"; String original = detail(prompt, "answer", Map.of());
        String stored = codec.encodeDetail(original, prompt);
        String bad = switch (mutation) {
            case "offset" -> change(stored, node -> node.put("promptOffset", Integer.MAX_VALUE));
            case "length" -> change(stored, node -> node.put("remainderCharacters", -1));
            case "hash" -> change(stored, node -> node.put("sha256", "0".repeat(64)));
            default -> stored;
        };
        String source = mutation.equals("missing-prompt") ? null : mutation.equals("escaped-length") ? "\0".repeat(2_000_000) : prompt;
        assertThatThrownBy(() -> codec.decodeDetail(encoded(), bad, source)).isInstanceOf(IllegalArgumentException.class);
    }

    private String detail(String prompt, String response, Map<String, String> reasons) {
        var detail = new LlmCallDetail(); detail.setPrompt(prompt); detail.setRawResponse(response);
        detail.setProvider("Custom OpenAI-compatible"); detail.setScores(Map.of()); detail.setReasons(reasons);
        return json.writeValueAsString(detail);
    }
    private String change(String value, Consumer<ObjectNode> change) {
        var node = (ObjectNode) json.readTree(value); change.accept(node); return json.writeValueAsString(node);
    }
    private static String gzip(byte[] bytes) {
        try { var bytesOut = new ByteArrayOutputStream(); try (var out = new GZIPOutputStream(bytesOut)) { out.write(bytes); } return Base64.getEncoder().encodeToString(bytesOut.toByteArray()); }
        catch (java.io.IOException impossible) { throw new AssertionError(impossible); }
    }
}
