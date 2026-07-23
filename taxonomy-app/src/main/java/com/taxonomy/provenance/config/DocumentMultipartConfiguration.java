package com.taxonomy.provenance.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps servlet multipart rejection aligned with the application upload policy. */
@Configuration
public class DocumentMultipartConfiguration {

    private static final long REQUEST_OVERHEAD_BYTES = 1024L * 1024L;

    @Bean
    MultipartConfigElement taxonomyMultipartConfig(DocumentImportLimits limits) {
        long fileLimit = limits.getMaxUploadBytes();
        long requestLimit;
        try {
            requestLimit = Math.addExact(fileLimit, REQUEST_OVERHEAD_BYTES);
        } catch (ArithmeticException error) {
            requestLimit = Long.MAX_VALUE;
        }
        return new MultipartConfigElement("", fileLimit, requestLimit, 0);
    }
}