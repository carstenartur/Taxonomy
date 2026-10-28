package com.taxonomy.export;

import com.taxonomy.visio.VisioDocument;
import com.taxonomy.visio.VisioNativeShape;
import com.taxonomy.visio.VisioPage;

import java.math.BigDecimal;

/** Opens the native drawing, not the adjacent full-text evidence or context block. */
final class VisioDrawingView {
    private VisioDrawingView() { }

    static String windowsXml(VisioDocument document) {
        VisioPage page = document.getPages().stream().filter(p -> !p.isEvidencePage())
                .findFirst().orElse(document.getPages().getFirst());
        double left = Double.POSITIVE_INFINITY, bottom = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY, top = Double.NEGATIVE_INFINITY;
        for (var shape : page.getShapes()) {
            if (VisioNativeShape.isPresentationOnly(shape)) continue;
            left = Math.min(left, shape.getX() - shape.getWidth() / 2);
            right = Math.max(right, shape.getX() + shape.getWidth() / 2);
            bottom = Math.min(bottom, shape.getY() - shape.getHeight() / 2);
            top = Math.max(top, shape.getY() + shape.getHeight() / 2);
        }
        for (var connector : page.getConnects()) {
            var box = connector.getTextBox();
            if (box == null || box.text().isBlank()) continue;
            left = Math.min(left, box.x() - box.width() / 2);
            right = Math.max(right, box.x() + box.width() / 2);
            bottom = Math.min(bottom, box.y() - box.height() / 2);
            top = Math.max(top, box.y() + box.height() / 2);
        }
        if (!Double.isFinite(left)) {
            left = 0; bottom = 0; right = 11; top = 8.5;
        }
        // Target a 960x720 drawing area at 96 dpi, with half an inch of breathing
        // room on each side. Never magnify a small diagram into page-filling text.
        double scale = Math.max(.01, Math.min(1, Math.min(10 / (right - left + 1), 7.5 / (top - bottom + 1))));
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Windows xmlns=\"http://schemas.microsoft.com/office/visio/2012/main\">"
                + "<Window ID=\"0\" WindowType=\"Drawing\" ContainerType=\"Page\" Container=\"" + page.getId()
                + "\" Page=\"" + page.getId() + "\" ViewCenterX=\"" + decimal(left / 2 + right / 2)
                + "\" ViewCenterY=\"" + decimal(bottom / 2 + top / 2)
                + "\" ViewScale=\"" + decimal(scale) + "\"/></Windows>";
    }

    private static String decimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
