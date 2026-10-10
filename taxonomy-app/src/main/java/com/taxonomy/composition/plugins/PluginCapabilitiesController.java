package com.taxonomy.composition.plugins;

import com.taxonomy.shared.features.FeatureAssembly;

import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import com.taxonomy.export.spi.ExportFormatExtension;
import com.taxonomy.shared.extension.ExtensionKind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** One immutable catalog revision plus startup capabilities; never exposes executable classes or scripts. */
@RestController
@RequestMapping("/api/capabilities")
@Tag(name = "Extensions")
public final class PluginCapabilitiesController {
    private final ExtensionCatalog catalog;
    private final FeatureAssembly.FeatureSet features;
    public PluginCapabilitiesController(ExtensionCatalog catalog, FeatureAssembly.FeatureSet features) {
        this.catalog = catalog; this.features = features;
    }
    public record Format(String reportType, String id, String displayName, String fileExtension,
                         String contentType, boolean binary, PluginIdentity plugin) { }
    public record Capabilities(long revision, Set<String> features, List<Format> exports, List<Format> reports) { }

    @GetMapping
    @Operation(summary = "Discover available feature and format capabilities",
            description = "Authenticated read of a consistent catalog revision. Only formats whose HTTP application service is installed are offered. All plugins use host-owned UI controls; no plugin JavaScript is returned.")
    @ApiResponse(responseCode = "200", description = "Available startup features and exact format artifact identities")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "503", description = "Catalog changed repeatedly; retry the capability read")
    public Capabilities capabilities() {
        for (int attempt = 0; attempt < 3; attempt++) {
            var snapshot = catalog.snapshot();
            var exports = new ArrayList<Format>(); var reports = new ArrayList<Format>();
            try {
                for (var registration : snapshot.extensions()) {
                    if (registration.key().kind() == ExtensionKind.EXPORT_FORMAT && features.has("analysis")) {
                        try (var lease = catalog.acquire(registration.key(), ExportFormatExtension.class)) {
                            if (!lease.plugin().equals(registration.plugin())) throw new ExtensionUnavailableException(registration.key());
                            var d = lease.extension().descriptor();
                            exports.add(new Format(null, d.id(), d.displayName(), d.fileExtension(), d.contentType(), d.binary(), lease.plugin()));
                        }
                    } else if (registration.key().kind() == ExtensionKind.REPORT_RENDERER
                            && features.has("reporting") && features.has("architecture")) {
                        try (var lease = catalog.acquire(registration.key(), ReportRendererExtension.class)) {
                            if (!lease.plugin().equals(registration.plugin())) throw new ExtensionUnavailableException(registration.key());
                            var d = lease.extension().descriptor();
                            reports.add(new Format(lease.extension().reportTypeId(), d.id(), d.displayName(), d.fileExtension(), d.contentType(), d.binary(), lease.plugin()));
                        }
                    }
                }
                if (snapshot.revision() == catalog.snapshot().revision())
                    return new Capabilities(snapshot.revision(), features.installed(), List.copyOf(exports), List.copyOf(reports));
            } catch (ExtensionUnavailableException changed) { /* bounded retry of the complete snapshot */ }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Plugin capabilities are changing; retry");
    }
}
