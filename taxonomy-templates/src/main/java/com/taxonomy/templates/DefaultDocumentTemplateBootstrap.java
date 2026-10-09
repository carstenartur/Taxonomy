package com.taxonomy.templates;

import com.taxonomy.templates.api.DocumentTemplates;
import com.taxonomy.templates.api.TemplateConflictException;
import com.taxonomy.templates.api.TemplateContribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Seeds contributed families without ever overwriting an organisation's edited version. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public final class DefaultDocumentTemplateBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DefaultDocumentTemplateBootstrap.class);
    private final DocumentTemplates templates;
    private final List<TemplateContribution> contributions;

    public DefaultDocumentTemplateBootstrap(DocumentTemplates templates, List<TemplateContribution> contributions) {
        this.templates = Objects.requireNonNull(templates, "templates");
        this.contributions = List.copyOf(TemplateContributions.index(contributions).values());
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        seedIfMissing();
    }

    void seedIfMissing() throws IOException {
        for (var contribution : contributions) {
            String id = contribution.templateId();
            if (templates.exists(id)) continue;
            try (var input = Objects.requireNonNull(contribution.content().open(), "template content")) {
                var created = templates.upload(id, contribution.displayName(), input, null,
                        "taxonomy-bootstrap", "Seed bundled template " + id);
                log.info("Seeded document template {} at commit {}", id, created.headCommit());
            } catch (TemplateConflictException race) {
                // Another instance may win creation. Never force-update its version.
                if (!templates.exists(id)) throw race;
                log.info("Document template {} was seeded concurrently", id);
            } catch (IOException | IllegalArgumentException | NullPointerException unavailable) {
                if (contribution.required()) {
                    throw new IllegalStateException("Required bundled document template is unavailable: " + id,
                            unavailable);
                }
                log.warn("Optional bundled document template {} is unavailable", id);
            }
        }
    }
}
