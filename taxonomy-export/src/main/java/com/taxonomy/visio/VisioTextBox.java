package com.taxonomy.visio;

/** Upright presentation text in page coordinates, independent of canonical shape data. */
public record VisioTextBox(String text, double x, double y, double width, double height) { }
