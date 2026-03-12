package com.taxonomy.archimate;

/**
 * A positioned node in an ArchiMate diagram view.
 * Coordinates are in pixels. {@code lineWidth} is 3 for anchor nodes, 1 otherwise.
 */
public record ArchiMateViewNode(
        String id,
        String elementId,
        int x,
        int y,
        int w,
        int h,
        String label,
        int r,
        int g,
        int b,
        int lineWidth) {}
