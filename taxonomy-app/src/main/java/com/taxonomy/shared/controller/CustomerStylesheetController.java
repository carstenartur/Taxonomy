package com.taxonomy.shared.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** An operator-owned stylesheet supplements the application's bundled styles. */
@Controller
@ControllerAdvice
public class CustomerStylesheetController {
    static final int MAX_STYLESHEET_BYTES = 512 * 1024;
    private static final Logger log = LoggerFactory.getLogger(CustomerStylesheetController.class);
    private static final MediaType CSS = new MediaType("text", "css", StandardCharsets.UTF_8);
    private final String configuredFile;

    public CustomerStylesheetController(@Value("${taxonomy.ui.custom-stylesheet:${TAXONOMY_UI_CUSTOM_STYLESHEET:}}") String configuredFile) {
        this.configuredFile = configuredFile == null ? "" : configuredFile.trim();
    }

    @ModelAttribute("customerStylesheetEnabled")
    public boolean customerStylesheetEnabled() {
        return !configuredFile.isEmpty();
    }

    @GetMapping(value = "/css/customer-theme.css", produces = "text/css")
    @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Read the optional customer stylesheet",
            description = "Returns the configured operator-owned UTF-8 CSS without caching, or an empty stylesheet when unconfigured. A missing, unreadable, oversized or invalid file returns 503 while bundled application styles remain available.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Configured stylesheet or empty CSS when unconfigured")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Configured stylesheet is unavailable or invalid")
    public ResponseEntity<String> stylesheet() {
        if (!customerStylesheetEnabled()) return css(HttpStatus.OK, "");
        try (var input = Files.newInputStream(Path.of(configuredFile))) {
            byte[] bytes = input.readNBytes(MAX_STYLESHEET_BYTES + 1);
            if (bytes.length > MAX_STYLESHEET_BYTES) throw new IOException("Stylesheet exceeds the size limit");
            String content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return css(HttpStatus.OK, content);
        } catch (IOException | InvalidPathException error) {
            log.warn("Customer stylesheet could not be read: {}", error.getMessage());
            return css(HttpStatus.SERVICE_UNAVAILABLE, "/* Customer stylesheet unavailable. */");
        }
    }

    private static ResponseEntity<String> css(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(CSS).cacheControl(CacheControl.noStore()).body(body);
    }
}
