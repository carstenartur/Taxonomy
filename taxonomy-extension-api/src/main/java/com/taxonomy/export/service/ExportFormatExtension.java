package com.taxonomy.export.service;

import com.taxonomy.shared.extension.TaxonomyExtension;
import com.taxonomy.shared.extension.ExtensionKind;

/**
 * Extension SPI for diagram export formats.
 *
 * <p>Each implementation describes and handles one export format — its file type,
 * content type, and the conversion of a projected {@link com.taxonomy.diagram.DiagramModel}
 * into serialized bytes.  Implementations are registered as Spring {@code @Component}s
 * and discovered automatically by {@link ExportFormatExtensionRegistry}.
 *
 * <p>Example usage:
 * <pre>{@code
 * @Component
 * public class MyFormatExportExtension implements ExportFormatExtension {
 *     private static final ExportFormatDescriptor DESCRIPTOR =
 *             new ExportFormatDescriptor("myformat", "My Format", "myf", "text/plain", false);
 *
 *     @Override
 *     public ExportFormatDescriptor descriptor() { return DESCRIPTOR; }
 *
 *     @Override
 *     public ExportResult export(ExportContext context) {
 *         String output = myService.convert(context.diagram());
 *         return new ExportResult(output.getBytes(StandardCharsets.UTF_8));
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Existing endpoints remain backward-compatible.</strong>  The adapters
 * delegate to the same underlying services ({@code MermaidExportService},
 * {@code ArchiMateXmlExporter}, etc.) so that output is byte-for-byte identical
 * to the previous direct-service calls.
 *
 * @see ExportFormatDescriptor
 * @see ExportContext
 * @see ExportResult
 * @see ExportFormatExtensionRegistry
 */
public interface ExportFormatExtension extends TaxonomyExtension {

    @Override
    default String id() {
        return descriptor().id();
    }

    @Override
    default String displayName() {
        return descriptor().displayName();
    }

    @Override
    default String description() {
        ExportFormatDescriptor descriptor = descriptor();
        return "Exports diagrams as %s".formatted(descriptor.displayName());
    }

    @Override
    default ExtensionKind kind() {
        return ExtensionKind.EXPORT_FORMAT;
    }

    /**
     * Returns the static descriptor for this export format.
     */
    ExportFormatDescriptor descriptor();

    /**
     * Converts the diagram model in the given context to export bytes.
     *
     * @param context carries the {@link com.taxonomy.diagram.DiagramModel} and optional options
     * @return the serialized export content
     */
    ExportResult export(ExportContext context);
}
