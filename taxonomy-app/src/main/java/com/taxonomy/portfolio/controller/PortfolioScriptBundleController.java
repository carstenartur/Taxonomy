package com.taxonomy.portfolio.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Serves the portfolio enhancement layers in their required deterministic order.
 *
 * <p>The page already references {@code taxonomy-portfolio-async.js}. Keeping
 * that stable URL avoids a template fork while ensuring request and response
 * normalization run before the contextual decision and job-center fetch
 * adapters.</p>
 */
@Controller
public class PortfolioScriptBundleController {

    private static final String PRODUCT_REQUEST_NORMALIZER =
            "static/js/portfolio/portfolio-product-request-normalizer.js";
    private static final String ANALYSIS_RESPONSE_NORMALIZER =
            "static/js/portfolio/portfolio-analysis-response-normalizer.js";
    private static final String GUIDED =
            "static/js/portfolio/portfolio-guided-decisions.js";
    private static final String ASYNC =
            "static/js/portfolio/taxonomy-portfolio-async.js";
    private static final String ASYNC_CLOSING_MARKER = "\n})();";
    private static final String JOB_REGISTRATION_BRIDGE = """

    // Stable bridge for the preceding response adapter. Job storage, polling
    // and rendering remain private to this module; callers can only submit a
    // canonical same-origin job resource and its server representation.
    window.taxonomyPortfolioRegisterJob = function (jobUrl, job) {
        if (!jobUrl || !job || !job.id) return false;
        const resolved = new URL(jobUrl, window.location.href);
        if (resolved.origin !== window.location.origin
                || !/^\\/api\\/projects\\/\\d+\\/analysis-jobs\\/[^/]+$/.test(resolved.pathname)) {
            return false;
        }
        registerJob(resolved.toString(), job);
        return true;
    };
""";

    @GetMapping(value = "/js/portfolio/taxonomy-portfolio-async.js",
            produces = "application/javascript")
    @ResponseBody
    public ResponseEntity<String> portfolioEnhancementBundle() throws IOException {
        String productRequestNormalizer = read(PRODUCT_REQUEST_NORMALIZER);
        String analysisResponseNormalizer = read(ANALYSIS_RESPONSE_NORMALIZER);
        String guided = read(GUIDED);
        String async = exposeJobRegistrationBridge(read(ASYNC));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/javascript"))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(productRequestNormalizer + "\n;\n"
                        + analysisResponseNormalizer + "\n;\n"
                        + guided + "\n;\n" + async);
    }

    static String exposeJobRegistrationBridge(String asyncScript) {
        int closing = asyncScript.lastIndexOf(ASYNC_CLOSING_MARKER);
        if (closing < 0) {
            throw new IllegalStateException("Portfolio job-center script has no closing IIFE marker");
        }
        return asyncScript.substring(0, closing)
                + JOB_REGISTRATION_BRIDGE
                + asyncScript.substring(closing);
    }

    private static String read(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        }
    }
}
