package com.taxonomy.dsl.model;

import java.util.Map;
import java.util.UUID;

/** Read-only reference to a source catalogue entry; native tool GUIDs have their own namespace. */
public record CatalogueReference(String uuid, String titleEn, String titleDe) {
    public static final String UUID_PROPERTY = "x-catalogue-uuid";
    public static final String TITLE_EN = "x-catalogue-title-en";
    public static final String TITLE_DE = "x-catalogue-title-de";

    public static CatalogueReference from(String rawUuid, String titleEn, String titleDe) {
        String uuid = null;
        if (rawUuid != null) {
            String text = rawUuid.strip();
            if (text.startsWith("{") && text.endsWith("}")) text = text.substring(1, text.length() - 1);
            try {
                String normalized = UUID.fromString(text).toString();
                if (normalized.equalsIgnoreCase(text)) uuid = normalized;
            } catch (IllegalArgumentException ignored) {
                // Legacy imports may have a non-UUID external key.
            }
        }
        if (uuid == null && (titleDe == null || titleDe.isBlank())) return null;
        return new CatalogueReference(uuid, titleEn, titleDe);
    }

    public void writeTo(Map<String, String> properties) {
        if (uuid != null) properties.put(UUID_PROPERTY, uuid);
        if (titleDe != null && !titleDe.isBlank() && titleEn != null && !titleEn.isBlank()) {
            properties.put(TITLE_EN, titleEn);
            properties.put(TITLE_DE, titleDe);
        }
    }

    public static String displayTitle(ArchitectureElement element, String language) {
        var properties = element.getExtensions();
        if ("de".equals(language) && element.getTitle() != null && element.getTitle().equals(properties.get(TITLE_EN)))
            return properties.getOrDefault(TITLE_DE, element.getTitle());
        return element.getTitle();
    }
}
