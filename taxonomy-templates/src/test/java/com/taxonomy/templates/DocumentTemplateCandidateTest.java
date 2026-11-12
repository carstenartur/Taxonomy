package com.taxonomy.templates;

import com.taxonomy.templates.api.RevisionPinnedTemplateReportPreview;
import com.taxonomy.templates.api.TemplateConflictException;
import com.taxonomy.templates.api.TemplateFile;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTemplateCandidateTest {
    @Test
    void sampleAndActivationUseExactSubmittedBytesAndRetainPreviousRevision() throws Exception {
        try (var active = repository("active"); var staged = repository("candidates")) {
            var templates = templates(active);
            byte[] original = resource();
            String id = TemplateTestFixture.TEMPLATE_ID;
            var base = templates.upload(id, "Before", new ByteArrayInputStream(original), null, "admin", "Initial");
            var candidates = service(staged, templates, preview(false));
            var candidate = candidates.stage(id, "Revised", base.headCommit(), original, "admin");

            assertThat(candidate.ready()).isTrue();
            assertThat(templates.describeCurrent(id).headCommit()).isEqualTo(base.headCommit());
            assertThat(candidates.original(id, candidate.version())).containsExactly(original);
            try (var sample = new org.apache.poi.xwpf.usermodel.XWPFDocument(
                    new ByteArrayInputStream(candidates.sample(id, candidate.version())))) {
                assertThat(sample.getParagraphs().getFirst().getText()).isEqualTo("Preview of " + id);
            }
            assertThatThrownBy(() -> candidates.original("another-template", candidate.version()))
                    .isInstanceOf(com.taxonomy.templates.api.TemplateNotFoundException.class);

            var restarted = service(staged, templates, preview(false));
            Callable<Boolean> activate = () -> {
                try {
                    restarted.activate(id, candidate.version(), "admin");
                    return true;
                } catch (TemplateConflictException stale) {
                    return false;
                }
            };
            try (var workers = Executors.newFixedThreadPool(2)) {
                var results = workers.invokeAll(List.of(activate, activate));
                assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(true, false);
            }
            assertThat(templates.describeCurrent(id).displayName()).isEqualTo("Revised");
            assertThat(templates.history(id)).hasSize(2);
            assertThat(templates.download(id, base.headCommit()).manifest().displayName()).isEqualTo("Before");
            assertThat(restarted.original(id, candidate.version())).containsExactly(original);
        }
    }

    @Test
    void failedRenderAndAnInterveningWriteCannotPromoteOtherBytes() throws Exception {
        try (var active = repository("active"); var staged = repository("candidates")) {
            var templates = templates(active);
            byte[] bytes = resource();
            String id = TemplateTestFixture.TEMPLATE_ID;
            String base = templates.upload(id, "Initial", new ByteArrayInputStream(bytes), null,
                    "admin", "Initial").headCommit();
            var failing = service(staged, templates, preview(true));
            var rejected = failing.stage(id, "Failed sample", base, bytes, "admin");
            assertThat(rejected.ready()).isFalse();
            assertThatThrownBy(() -> failing.activate(id, rejected.version(), "admin"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private");
            var passing = service(staged, templates, preview(false));
            var candidate = passing.stage(id, "Superseded", base, bytes, "admin");
            var intervening = templates.upload(id, "Intervening", new ByteArrayInputStream(bytes), base,
                    "other-admin", "Intervening");
            assertThatThrownBy(() -> passing.activate(id, candidate.version(), "admin")).isInstanceOf(TemplateConflictException.class);
            assertThat(templates.describeCurrent(id).headCommit()).isEqualTo(intervening.headCommit());
            assertThatThrownBy(() -> passing.stage(id, "Malformed", intervening.headCommit(), new byte[]{1}, "admin"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static DocumentTemplateCandidateService service(InMemoryRepository staged, DocumentTemplateService templates,
                                                            RevisionPinnedTemplateReportPreview preview) {
        return new DocumentTemplateCandidateService(new DocumentTemplateCandidateStore(staged), templates,
                TemplateTestFixture.contributions(preview));
    }

    private static DocumentTemplateService templates(InMemoryRepository repository) {
        return new DocumentTemplateService(new DocumentTemplateGitRepository(repository), new OoxmlTemplatePackageCodec(),
                List.of(new TemplateTestFixture()));
    }

    private static InMemoryRepository repository(String name) {
        return new InMemoryRepository(new DfsRepositoryDescription(name));
    }

    private static byte[] resource() throws Exception {
        try (var stream = TemplateTestFixture.class.getResourceAsStream("/" + TemplateTestFixture.DEFAULT_RESOURCE)) {
            return stream.readAllBytes();
        }
    }

    private static RevisionPinnedTemplateReportPreview preview(boolean fail) {
        return new RevisionPinnedTemplateReportPreview() {
            public byte[] renderPreview() { throw new AssertionError("Active fallback selected"); }
            public byte[] renderPreview(TemplateFile input) {
                assertThat(input.content()).isNotEmpty();
                if (fail) throw new IllegalArgumentException("private sample details");
                try (var sample = new org.apache.poi.xwpf.usermodel.XWPFDocument();
                     var output = new java.io.ByteArrayOutputStream()) {
                    sample.createParagraph().createRun().setText("Preview of " + input.manifest().templateId());
                    sample.write(output);
                    return output.toByteArray();
                } catch (java.io.IOException unexpected) {
                    throw new java.io.UncheckedIOException(unexpected);
                }
            }
        };
    }
}
