package com.taxonomy.plugins.sparx;

/** Stable, bounded messages for the host's existing import-error boundary. */
final class QeaFailure extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    QeaFailure(String code, String detail) { super(code + ": " + detail); }
    static QeaFailure fail(String code, String detail) { return new QeaFailure(code, detail); }
}
