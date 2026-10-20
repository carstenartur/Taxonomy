package com.taxonomy.analysis.recovery;

import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.dto.AnalysisRequest;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.extension.api.plugin.PluginInvocation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.json.JsonWriteFeature;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisEvidenceFingerprintTest {
    private static volatile Object observed;

    @Test
    void rawFramesPreserveNullAllUtf16CodeUnitsAndRandomSurrogateBoundaries() throws Exception {
        var cases = new ArrayList<>(Arrays.asList(null, "", "ASCII", "\0\n\r\t\"\\", "ä中😀",
                "\ud800", "\udc00", "\ud800x\udc00"));
        var everyCodeUnit = new StringBuilder(65_536);
        for (int c = 0; c < 65_536; c++) everyCodeUnit.append((char) c);
        cases.add(everyCodeUnit.toString());
        for (int edge : new int[]{8_191, 8_192, 16_383, 16_384, 16_385, 32_767}) {
            cases.add("x".repeat(edge) + "\ud800\udc00");
            cases.add("x".repeat(edge) + "\ud800X\udc00");
            cases.add("x".repeat(edge) + "\ud800");
        }
        var random = new Random(81_251_019);
        for (int i = 0; i < 300; i++) {
            char[] value = new char[random.nextInt(40_000)];
            for (int c = 0; c < value.length; c++) value[c] = (char) random.nextInt(65_536);
            cases.add(new String(value));
        }
        for (String value : cases) assertThat(AnalysisCheckpointSession.digest("before", value, null, "after"))
                .isEqualTo(legacy("before", value, null, "after"));
        String high = "a".repeat(20_000) + "\ud800";
        String low = "\udc00" + "b".repeat(20_000);
        assertThat(AnalysisCheckpointSession.digest(high, low, high, low)).isEqualTo(legacy(high, low, high, low));
        assertThat(AnalysisCheckpointSession.digest("ab", "c"))
                .isNotEqualTo(AnalysisCheckpointSession.digest("a", "bc"));
    }

    @Test
    void jsonFrameUsesTheSameConfiguredCharacterSerializationAndUtf8Replacement() throws Exception {
        var mappers = List.of(new ObjectMapper(),
                JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build(),
                JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build(),
                JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build(),
                JsonMapper.builder().enable(SerializationFeature.WRAP_ROOT_VALUE).build());
        var values = new ArrayList<>(Arrays.asList(null, "", Map.of("b", "中😀\ud800", "a", List.of(1, 2, 3)),
                "x".repeat(8_191) + "\ud800\udc00", "x".repeat(16_383) + "\ud800X\udc00"));
        var random = new Random(81_251_019);
        for (int i = 0; i < 30; i++) {
            char[] value = new char[random.nextInt(20_000)];
            for (int c = 0; c < value.length; c++) value[c] = (char) random.nextInt(65_536);
            values.add(new String(value));
        }
        for (ObjectMapper mapper : mappers) for (Object value : values) {
            assertThat(AnalysisEvidenceFingerprint.digestJson(mapper, new String[]{"scope", null}, value, "policy"))
                    .isEqualTo(legacy("scope", null, mapper.writeValueAsString(value), "policy"));
        }
    }

    @Test
    void largeRawFramesAreBoundedWithoutMakingOrdinaryProviderPromptsMoreExpensive() throws Exception {
        for (int size : new int[]{1_024, 4_096, 8_192, 16_000}) for (String unit : List.of("a", "中", "😀", "\ud800")) {
            String value = unit.repeat(size / unit.length());
            assertThat(AnalysisCheckpointSession.digest(value)).isEqualTo(legacy(value));
            Checked old = () -> observed = legacy(value);
            Checked current = () -> observed = AnalysisCheckpointSession.digest(value);
            for (int i = 0; i < 10; i++) { old.run(); current.run(); }
            assertThat(allocated(current, 20)).as("small prompt %s characters U+%s", size,
                    Integer.toHexString(unit.charAt(0))).isLessThanOrEqualTo(allocated(old, 20) + 4_096);
        }
        String large = "ASCII中😀\ud800".repeat(200_000);
        Checked old = () -> observed = legacy(large, null);
        Checked current = () -> observed = AnalysisCheckpointSession.digest(large, null);
        assertThat(AnalysisCheckpointSession.digest(large, null)).isEqualTo(legacy(large, null));
        for (int i = 0; i < 3; i++) { old.run(); current.run(); }
        assertThat(allocated(current, 3)).isLessThan(allocated(old, 3) / 10);
    }

    @Test
    void authorityAndSignatureKeepTheirExactIdentityWithoutFullCatalogueBuffers() throws Exception {
        var mapper = new ObjectMapper();
        var node = new TaxonomyNodeDto();
        node.setCode("CI-1060");
        node.setNameEn("Flood observations");
        // Typed catalogue fields exercise the real duplicated description alias and JSON escaping.
        node.setDescriptionEn("Water \"level\"\n中😀\ud800".repeat(150_000));
        var tree = List.of(node);
        var llm = mock(LlmService.class);
        when(llm.recoveryPolicyFingerprint("MOCK")).thenReturn("policy");
        var service = new AnalysisContinuationService(null, null, llm, null, mapper);
        var request = new AnalysisRequest();
        request.setBusinessText("Retain exact flood evidence");
        request.setIncludeArchitectureView(true);
        request.setMaxArchitectureNodes(50);
        request.setProvider("MOCK");
        Method authority = AnalysisContinuationService.class.getDeclaredMethod(
                "authority", String.class, PluginInvocation.class, List.class);
        authority.setAccessible(true);
        Method signature = AnalysisContinuationService.class.getDeclaredMethod(
                "signature", AnalysisRequest.class, List.class);
        signature.setAccessible(true);
        var actualAuthority = authority.invoke(service, "MOCK", null, tree);
        Method catalogueHash = actualAuthority.getClass().getDeclaredMethod("catalogueHash");
        catalogueHash.setAccessible(true);
        String json = mapper.writeValueAsString(tree);
        assertThat(catalogueHash.invoke(actualAuthority)).isEqualTo(legacy(json));
        assertThat(signature.invoke(service, request, tree)).isEqualTo(legacy(
                "resumable-scoring-v1", request.getBusinessText(), "true", "50", "policy", json, ""));
        Checked legacyCalls = () -> {
            observed = legacy(mapper.writeValueAsString(tree));
            observed = legacy("resumable-scoring-v1", request.getBusinessText(), "true", "50", "policy",
                    mapper.writeValueAsString(tree), "");
        };
        Checked actualCalls = () -> {
            observed = authority.invoke(service, "MOCK", null, tree);
            observed = signature.invoke(service, request, tree);
        };
        for (int i = 0; i < 3; i++) { legacyCalls.run(); actualCalls.run(); }
        long legacyBytes = allocated(legacyCalls, 3);
        long actualBytes = allocated(actualCalls, 3);
        System.out.printf("Framed authority/signature allocation: legacy=%d bounded=%d%n", legacyBytes, actualBytes);
        assertThat(actualBytes).as("authority/signature allocate %s bytes versus legacy %s", actualBytes, legacyBytes)
                .isLessThan(legacyBytes / 10);
    }

    private static String legacy(String... values) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (String value : values) {
            byte[] bytes = Objects.toString(value, "").getBytes(StandardCharsets.UTF_8);
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static long allocated(Checked operation, int repetitions) throws Exception {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(bean.isThreadAllocatedMemorySupported()).as("JVM allocation accounting is supported").isTrue();
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < repetitions; i++) operation.run();
        long after = bean.getThreadAllocatedBytes(thread);
        assertThat(before).isNotNegative();
        assertThat(after).isGreaterThanOrEqualTo(before);
        return after - before;
    }

    @FunctionalInterface
    private interface Checked { void run() throws Exception; }
}
