package com.taxonomy.export;

import com.taxonomy.visio.VisioDocument;
import com.taxonomy.visio.VisioPage;
import com.taxonomy.visio.VisioShape;

/** Shared assertions for JUnit and an isolated Java regression run. */
final class VisioGridRegression {
    static void rejectsInvalidCoordinates() {
        for (double value : new double[]{Double.NaN, Double.NEGATIVE_INFINITY,
                -Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.MAX_VALUE}) {
            try {
                VisioPresentation.apply(document(value, 2));
                throw new AssertionError("Non-finite/out-of-grid coordinate accepted: " + value);
            } catch (IllegalArgumentException expected) {
                // Invalid geometry must fail before entering a spatial bucket loop.
            }
        }
    }

    static void acceptsBoundaryCoordinates() {
        VisioPresentation.apply(document(Integer.MAX_VALUE - .05, 2));
        VisioPresentation.apply(document(2, Integer.MAX_VALUE - .05));
        VisioPresentation.apply(document(Integer.MIN_VALUE + .5, 2));
        VisioPresentation.apply(document(2, 2));
    }

    private static VisioDocument document(double x, double y) {
        var document = new VisioDocument();
        var page = new VisioPage("0", "Overview");
        page.getShapes().add(new VisioShape("1", "Node", x, y, .2, .2, "Application", true));
        document.getPages().add(page);
        return document;
    }

    public static void main(String[] args) {
        rejectsInvalidCoordinates();
        acceptsBoundaryCoordinates();
        System.out.println("PASS: invalid geometry rejected; ordinary and integer-boundary cells terminate");
    }
}
