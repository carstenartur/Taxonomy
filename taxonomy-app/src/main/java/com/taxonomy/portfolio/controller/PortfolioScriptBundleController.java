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
 * that stable URL avoids a template fork while ensuring product request
 * normalization runs before the contextual decision and job-center fetch
 * adapters.</p>
 */
@Controller
public class PortfolioScriptBundleController {

    private static final String PRODUCT_REQUEST_NORMALIZER =
            "static/js/portfolio/portfolio-product-request-normalizer.js";
    private static final String GUIDED =
            "static/js/portfolio/portfolio-guided-decisions.js";
    private static final String ASYNC =
            "static/js/portfolio/taxonomy-portfolio-async.js";

    @GetMapping(value = "/js/portfolio/taxonomy-portfolio-async.js",
            produces = "application/javascript")
    @ResponseBody
    public ResponseEntity<String> portfolioEnhancementBundle() throws IOException {
        String productRequestNormalizer = read(PRODUCT_REQUEST_NORMALIZER);
        String guided = read(GUIDED);
        String async = read(ASYNC);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/javascript"))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(productRequestNormalizer + "\n;\n" + guided + "\n;\n" + async);
    }

    private static String read(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        }
    }
}
