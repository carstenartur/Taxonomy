package com.taxonomy.templates;

import com.sun.management.ThreadMXBean;
import com.taxonomy.templates.api.OoxmlTemplateMediaTypes;
import com.taxonomy.templates.api.TemplateManifest;
import com.taxonomy.templates.api.TemplateFile;
import com.taxonomy.templates.api.TemplateDescriptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Measured read envelopes on a real canonical DOTX, with cold service handles for every operation. */
class DocumentTemplateScaleBudgetTest {
    private static final long MIB = 1024L * 1024L;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest(name = "{0} templates, {1} repository revisions")
    @CsvSource({"10, 100", "10, 1000", "100, 100", "100, 1000"})
    void measureColdAndWarmOperations(int templates, int revisions) throws Exception {
        var codec = new OoxmlTemplatePackageCodec();
        try (var git = new DocumentTemplateRepositoryReadCostTest.CountingRepository();
             var input = TemplateTestFixture.class.getResourceAsStream("/" + TemplateTestFixture.DEFAULT_RESOURCE)) {
            var parts = codec.unpack(input).parts();
            String fixtureHash = OoxmlTemplatePackageCodec.packageSha256(parts);
            long partBytes = parts.values().stream().mapToLong(value -> value.length).sum();
            var writer = new DocumentTemplateGitRepository(git);
            var versions = new LinkedHashMap<String, String>();
            // Older templates are left untouched while later templates change.
            // Cold list must traverse history to find every template's own ETag.
            for (int i = 0; i < templates; i++) {
                String id = String.format(Locale.ROOT, "template-%03d", i);
                for (int j = 0; j < revisions / templates; j++) {
                    var manifest = new TemplateManifest(1, id, id + " version " + j, id + ".dotx",
                            OoxmlTemplateMediaTypes.DOTX_MEDIA_TYPE, "2026-08-22T16:00:00Z", "fixture",
                            partBytes, parts.size(), fixtureHash);
                    versions.put(id, writer.commit(manifest, parts, versions.get(id), "fixture", "Revision " + j).commitId());
                }
            }

            var measurements = new ArrayList<Map<String, Object>>();
            for (String operation : List.of("list", "history", "download", "PROPFIND")) {
                var cache = new DocumentTemplateMaterializationCache();
                var service = new DocumentTemplateService(new DocumentTemplateGitRepository(git), codec,
                        List.of(), new OoxmlActiveContentValidator(), cache);
                var servlet = new DocumentTemplateWebDavServlet(service, new DocumentTemplateWebDavLockManager());
                String first = "template-000";
                for (String state : List.of("cold", "warm")) {
                    git.resetCounts();
                    var threads = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
                    assertThat(threads.isThreadAllocatedMemoryEnabled()).isTrue();
                    long beforeBytes = threads.getCurrentThreadAllocatedBytes();
                    long before = System.nanoTime();
                    Object result = switch (operation) {
                        case "list" -> service.list();
                        case "history" -> service.history(first);
                        case "download" -> service.downloadCurrent(first);
                        case "PROPFIND" -> DocumentTemplateRepositoryReadCostTest.propfind(servlet);
                        default -> throw new IllegalStateException(operation);
                    };
                    long elapsed = System.nanoTime() - before;
                    long allocated = threads.getCurrentThreadAllocatedBytes() - beforeBytes;
                    var sample = new LinkedHashMap<String, Object>();
                    sample.put("operation", operation);
                    sample.put("cache", state);
                    sample.put("latencyNanos", elapsed);
                    sample.put("threadAllocatedBytes", allocated);
                    sample.put("commitOpens", git.commitOpens);
                    sample.put("treeOpens", git.treeOpens);
                    sample.put("retainedLengthEntries", cache.lengthEntries());
                    sample.put("retainedArchives", cache.entries());
                    sample.put("retainedArchiveBytes", cache.sizeBytes());
                    measurements.add(sample);
                    System.out.println("template-read-budget " + templates + "/" + revisions + " " + sample);

                    assertThat(elapsed).as("read timeout").isLessThan(Duration.ofSeconds(30).toNanos());
                    assertThat(allocated).as("request-thread allocated bytes").isBetween(1L, (state.equals("warm") ? 64 : 768) * MIB);
                    assertThat(cache.lengthEntries()).isLessThanOrEqualTo(DocumentTemplateMaterializationCache.MAX_LENGTH_ENTRIES);
                    assertThat(cache.sizeBytes()).isLessThanOrEqualTo(DocumentTemplateMaterializationCache.DEFAULT_MAX_BYTES);
                    assertThat(cache.entries()).isLessThanOrEqualTo(DocumentTemplateMaterializationCache.DEFAULT_MAX_ENTRIES);
                    assertThat(git.commitOpens).as("history plus current representations")
                            .isLessThanOrEqualTo(revisions + 2 * templates + 4);
                    if (operation.equals("list")) {
                        assertThat((List<?>) result).hasSize(templates).allSatisfy(value -> {
                            var template = (TemplateDescriptor) value;
                            assertThat(template.headCommit()).isEqualTo(versions.get(template.templateId()));
                        });
                    } else if (operation.equals("history")) {
                        assertThat((List<?>) result).hasSize(revisions / templates);
                    } else if (operation.equals("download")) {
                        var file = (TemplateFile) result;
                        assertThat(file.commitId()).isEqualTo(versions.get(first));
                        assertThat(file.content()).isEqualTo(codec.pack(parts));
                    } else {
                        for (String version : versions.values()) assertThat((String) result).contains(version);
                    }
                }
            }
            var evidence = Map.of("schemaVersion", 1, "templates", templates, "repositoryRevisions", revisions,
                    "revisionsPerTemplate", revisions / templates, "fixturePartSha256", fixtureHash,
                    "source", System.getenv().getOrDefault("GITHUB_SHA", "local-worktree"),
                    "jvm", System.getProperty("java.runtime.version"), "maxHeapBytes", Runtime.getRuntime().maxMemory(),
                    "measurements", measurements);
            Path destination = Path.of("target", "template-scale-evidence", templates + "-" + revisions + ".json");
            Files.createDirectories(destination.getParent());
            Files.write(destination, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(evidence));
        }
    }
}
