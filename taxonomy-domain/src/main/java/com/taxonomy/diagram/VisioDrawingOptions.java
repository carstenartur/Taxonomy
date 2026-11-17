package com.taxonomy.diagram;

import java.util.Objects;

/** Presentation choices for an editable decision drawing and its matching PDF. */
public record VisioDrawingOptions(Mode mode, Paper paper, boolean appendix, boolean shortReasons, boolean german) {
    public enum Mode { SCREEN, WALL }
    public enum Paper { A4, A3, A2, A1, A0 }

    public VisioDrawingOptions {
        Objects.requireNonNull(mode, "drawing mode");
        Objects.requireNonNull(paper, "paper format");
    }

    public static VisioDrawingOptions screen(boolean german) {
        return new VisioDrawingOptions(Mode.SCREEN, Paper.A1, false, false, german);
    }

    public boolean showsShortReasons() {
        return mode == Mode.WALL && shortReasons;
    }
}
