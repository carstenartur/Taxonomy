package com.taxonomy.templates.api;

/** A null change denotes unchanged bytes, as in TemplateDiff's absent change entry. */
public record TemplatePartComparison(
        PartChange change, TemplatePartView before, TemplatePartView after) { }
