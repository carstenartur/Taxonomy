package com.nato.taxonomy.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages prompt templates for LLM taxonomy analysis.
 * Loads default templates from {@code classpath:prompts/*.txt} at startup
 * and supports runtime overrides without redeployment.
 */
@Service
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);

    /** Human-readable names for known taxonomy root codes. */
    private static final Map<String, String> TAXONOMY_NAMES;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("BP", "Business Processes");
        m.put("BR", "Business Roles");
        m.put("CP", "Capabilities");
        m.put("CI", "COI Services");
        m.put("CO", "Communications Services");
        m.put("CR", "Core Services");
        m.put("IP", "Information Products");
        m.put("UA", "User Applications");
        TAXONOMY_NAMES = Collections.unmodifiableMap(m);
    }

    /** File-based defaults loaded at startup (key = taxonomy code or "default"). */
    private final Map<String, String> defaults = new HashMap<>();

    /** In-memory runtime overrides (survive the request but not a restart). */
    private final Map<String, String> overrides = new ConcurrentHashMap<>();

    @PostConstruct
    public void loadDefaults() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath:prompts/*.txt");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) continue;
                String code = filename.endsWith(".txt")
                        ? filename.substring(0, filename.length() - 4)
                        : filename;
                try (InputStream is = resource.getInputStream()) {
                    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    defaults.put(code, content);
                    log.debug("Loaded prompt template: {}", filename);
                } catch (IOException e) {
                    log.warn("Failed to load prompt template '{}': {}", filename, e.getMessage());
                }
            }
            log.info("Loaded {} prompt template(s) from classpath:prompts/", defaults.size());
        } catch (IOException e) {
            log.warn("Could not scan classpath:prompts/ for templates: {}", e.getMessage());
        }
    }

    /**
     * Returns the effective template for a taxonomy code:
     * override → taxonomy-specific default → "default" fallback.
     */
    public String getTemplate(String taxonomyCode) {
        if (overrides.containsKey(taxonomyCode)) {
            return overrides.get(taxonomyCode);
        }
        return getDefaultTemplate(taxonomyCode);
    }

    /**
     * Always returns the file-based default (ignoring overrides).
     * Falls back to "default" template if no taxonomy-specific file exists.
     */
    public String getDefaultTemplate(String taxonomyCode) {
        if (defaults.containsKey(taxonomyCode)) {
            return defaults.get(taxonomyCode);
        }
        return defaults.getOrDefault("default", "");
    }

    /** Stores a runtime override for the given taxonomy code. */
    public void setTemplate(String taxonomyCode, String template) {
        overrides.put(taxonomyCode, template);
    }

    /** Removes the runtime override; subsequent calls revert to the file-based default. */
    public void resetTemplate(String taxonomyCode) {
        overrides.remove(taxonomyCode);
    }

    /** Returns {@code true} if a runtime override exists for this taxonomy code. */
    public boolean isOverridden(String taxonomyCode) {
        return overrides.containsKey(taxonomyCode);
    }

    /**
     * Renders the effective prompt by substituting all {@code {{...}}} placeholders.
     *
     * @param taxonomyCode the taxonomy root code (e.g. "BP")
     * @param businessText the user-entered business requirement text
     * @param nodeList     the formatted list of taxonomy nodes
     * @return the rendered prompt string ready to send to the LLM
     */
    public String renderPrompt(String taxonomyCode, String businessText, String nodeList) {
        String name = TAXONOMY_NAMES.getOrDefault(taxonomyCode, taxonomyCode);
        String template = getTemplate(taxonomyCode);
        return template
                .replace("{{BUSINESS_TEXT}}", businessText)
                .replace("{{NODE_LIST}}", nodeList)
                .replace("{{TAXONOMY_NAME}}", name);
    }

    /**
     * Renders the leaf-justification prompt by substituting all placeholders.
     *
     * @param businessText   the original business requirement text
     * @param leafCode       the code of the leaf node to justify
     * @param pathDescription formatted path from root to leaf with scores and inline reasons
     * @param crossRefs       formatted list of other high-scoring nodes for cross-references
     * @return the rendered prompt string ready to send to the LLM
     */
    public String renderLeafJustificationPrompt(String businessText, String leafCode,
                                                String pathDescription, String crossRefs) {
        String template = defaults.getOrDefault("justify-leaf", "");
        if (template.isBlank()) {
            // Fallback inline template if the file is missing
            template = "You are an expert in NATO C3 taxonomy classification.\n"
                    + "Explain in 3-5 sentences why the taxonomy path ending at {{LEAF_CODE}} "
                    + "best matches the following business requirement.\n\n"
                    + "Business Requirement: {{BUSINESS_TEXT}}\n\n"
                    + "Selected path (root → leaf) with scores:\n{{PATH_DESCRIPTION}}\n"
                    + "Other high-scoring nodes for cross-reference:\n{{CROSS_REFERENCES}}\n\n"
                    + "Provide a coherent justification that explains why this path was chosen, "
                    + "how the leaf node relates to the requirement, and note any relevant "
                    + "connections to the cross-referenced nodes.";
        }
        return template
                .replace("{{BUSINESS_TEXT}}", businessText)
                .replace("{{LEAF_CODE}}", leafCode)
                .replace("{{PATH_DESCRIPTION}}", pathDescription)
                .replace("{{CROSS_REFERENCES}}", crossRefs);
    }

    /**
     * Returns a list of all known template codes (both defaults and any that only have overrides).
     */
    public List<String> getAllTemplateCodes() {
        List<String> codes = new ArrayList<>(defaults.keySet());
        for (String code : overrides.keySet()) {
            if (!codes.contains(code)) {
                codes.add(code);
            }
        }
        Collections.sort(codes);
        return codes;
    }

    /** Returns the human-readable taxonomy name for a code, or the code itself if unknown. */
    public String getTaxonomyName(String taxonomyCode) {
        return TAXONOMY_NAMES.getOrDefault(taxonomyCode, taxonomyCode);
    }
}
