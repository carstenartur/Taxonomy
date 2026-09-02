package com.taxonomy.export;

import com.taxonomy.visio.VisioConnect;
import com.taxonomy.visio.VisioDocument;
import com.taxonomy.visio.VisioPage;
import com.taxonomy.visio.VisioShape;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates the bounded in-memory model before any VSDX bytes are emitted. */
final class VisioPackageValidator {

    static final long MAX_VISIO_ID = 0xffff_ffffL;

    private VisioPackageValidator() {
    }

    static void validate(VisioDocument document) {
        if (document == null) {
            throw invalid("Visio document must not be null");
        }
        List<VisioPage> pages = document.getPages();
        if (pages == null) {
            throw invalid("Visio page collection must not be null");
        }

        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            VisioPage page = pages.get(pageIndex);
            if (page == null) {
                throw invalid("Page " + pageIndex + " must not be null");
            }
            validateXmlText(page.getName(), "Page " + pageIndex + " name");

            List<VisioShape> shapes = page.getShapes();
            if (shapes == null) {
                throw invalid("Shape collection on page " + pageIndex + " must not be null");
            }
            List<VisioConnect> connects = page.getConnects();
            if (connects == null) {
                throw invalid("Connect collection on page " + pageIndex + " must not be null");
            }

            Set<Long> shapeIds = new HashSet<>();
            long maximumShapeId = 0;
            for (int shapeIndex = 0; shapeIndex < shapes.size(); shapeIndex++) {
                VisioShape shape = shapes.get(shapeIndex);
                if (shape == null) {
                    throw invalid("Shape " + shapeIndex + " on page " + pageIndex
                            + " must not be null");
                }

                long shapeId = parseShapeId(
                        shape.getId(), "Shape " + shapeIndex + " on page " + pageIndex);
                if (!shapeIds.add(shapeId)) {
                    throw invalid("Duplicate Visio shape ID " + shapeId
                            + " on page " + pageIndex);
                }
                maximumShapeId = Math.max(maximumShapeId, shapeId);

                validateXmlText(shape.getText(), "Text of shape " + shapeId);
                if (shape.getType() != null) {
                    validateXmlText(shape.getType(), "Type of shape " + shapeId);
                }
                validateCoordinate(shape.getX(), "PinX of shape " + shapeId, true);
                validateCoordinate(shape.getY(), "PinY of shape " + shapeId, true);
                validateCoordinate(shape.getWidth(), "Width of shape " + shapeId, false);
                validateCoordinate(shape.getHeight(), "Height of shape " + shapeId, false);
            }

            if (maximumShapeId > MAX_VISIO_ID - connects.size()) {
                throw invalid("Connector IDs overflow the Visio unsigned-int range on page "
                        + pageIndex);
            }

            for (int connectIndex = 0; connectIndex < connects.size(); connectIndex++) {
                VisioConnect connect = connects.get(connectIndex);
                if (connect == null) {
                    throw invalid("Connector " + connectIndex + " on page " + pageIndex
                            + " must not be null");
                }

                long fromId = parseShapeId(
                        connect.getFromShape(), "Source of connector " + connectIndex);
                long toId = parseShapeId(
                        connect.getToShape(), "Target of connector " + connectIndex);
                if (!shapeIds.contains(fromId)) {
                    throw invalid("Connector " + connectIndex
                            + " references missing source shape " + fromId);
                }
                if (!shapeIds.contains(toId)) {
                    throw invalid("Connector " + connectIndex
                            + " references missing target shape " + toId);
                }
                validateXmlText(
                        connect.getRelationType(), "Label of connector " + connectIndex);
            }
        }
    }

    static long parseShapeId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " must contain a positive numeric Visio ID");
        }

        try {
            long id = Long.parseLong(value);
            if (id <= 0 || id > MAX_VISIO_ID) {
                throw invalid(field + " must be between 1 and " + MAX_VISIO_ID
                        + ", but was " + value);
            }
            return id;
        } catch (NumberFormatException exception) {
            throw invalid(field + " must contain a positive numeric Visio ID, but was "
                    + value, exception);
        }
    }

    private static void validateCoordinate(double value, String field, boolean allowZero) {
        boolean inRange = Double.isFinite(value) && (allowZero ? value >= 0 : value > 0);
        if (!inRange) {
            throw invalid(field + " must be "
                    + (allowZero ? "finite and non-negative" : "finite and positive")
                    + ", but was " + value);
        }
    }

    private static void validateXmlText(String value, String field) {
        if (value == null) {
            throw invalid(field + " must not be null");
        }
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!isXml10CodePoint(codePoint)) {
                throw invalid(field + " contains an XML 1.0 control or surrogate character: U+"
                        + Integer.toHexString(codePoint).toUpperCase());
            }
            offset += Character.charCount(codePoint);
        }
    }

    private static boolean isXml10CodePoint(int codePoint) {
        return codePoint == 0x9
                || codePoint == 0xA
                || codePoint == 0xD
                || (codePoint >= 0x20 && codePoint <= 0xD7FF)
                || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
                || (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException invalid(String message, Exception cause) {
        return new IllegalArgumentException(message, cause);
    }
}
