package com.taxonomy.composition.plugins;

import com.taxonomy.extension.runtime.PluginOperationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = PluginAdministrationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class PluginOperationExceptionHandler {
    public record Problem(String code) { }
    @ExceptionHandler(PluginOperationException.class)
    ResponseEntity<Problem> rejected(PluginOperationException failure) {
        return ResponseEntity.status(failure.code().startsWith("INVALID_") ? 400 : 409).body(new Problem(failure.code()));
    }
}
