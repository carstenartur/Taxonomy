package com.taxonomy.exchange;

/** Bounded public failure. Parser diagnostics and external document fragments must not reach logs or HTTP errors. */
public final class ExchangeFormatException extends IllegalArgumentException {
    private final String code;
    public ExchangeFormatException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
