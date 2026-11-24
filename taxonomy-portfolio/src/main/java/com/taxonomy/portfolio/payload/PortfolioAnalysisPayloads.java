package com.taxonomy.portfolio.payload;

import com.taxonomy.exchange.backup.PortableJsonPayloads;
import com.taxonomy.exchange.backup.PortableJsonPayloads.PayloadReader;
import com.taxonomy.portfolio.service.PortfolioException;

import java.io.FilterReader;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;

/** Handles the physical analysis payload in either its legacy JSON or portable frame representation. */
public final class PortfolioAnalysisPayloads {
    private static final String FRAME_FAMILY = "taxonomy-json-payload:";
    private static final char[] FRAME_FAMILY_UNITS = FRAME_FAMILY.toCharArray();
    private static final int MAX_FRAME_CHARACTERS = 24_000_000;

    private PortfolioAnalysisPayloads() { }

    /** Optionally pack the exact serialized JSON within the storage frame limit. */
    public static String store(String original, boolean packedWrites) {
        if (!packedWrites || original == null || original.length() > MAX_FRAME_CHARACTERS) return original;
        try {
            return PortableJsonPayloads.packFast(original, MAX_FRAME_CHARACTERS);
        } catch (IOException failure) {
            throw PortfolioException.analysisFailed("Could not encode portfolio analysis payload", failure);
        }
    }

    /** Return the exact JSON exposed by the compatibility accessors. */
    public static String plain(String stored) {
        if (stored == null || !stored.startsWith(FRAME_FAMILY)) return stored;
        try {
            return PortableJsonPayloads.unpack(stored, MAX_FRAME_CHARACTERS);
        } catch (IOException failure) {
            throw PortfolioException.analysisFailed("Could not decode portfolio analysis payload", failure);
        }
    }

    /** Parse stored evidence while leaving the stored reader with its caller. */
    public static <T> T read(Reader stored, PayloadReader<T> parser) throws IOException {
        if (stored == null) return parser.read(null);
        try (var input = new PushbackReader(new FilterReader(stored) {
            @Override public void close() { }
        }, FRAME_FAMILY_UNITS.length)) {
            for (int matched = 0; matched < FRAME_FAMILY_UNITS.length; matched++) {
                int next = input.read();
                if (next != FRAME_FAMILY_UNITS[matched]) {
                    if (next != -1) input.unread(next);
                    input.unread(FRAME_FAMILY_UNITS, 0, matched);
                    return parser.read(input);
                }
            }

            return PortableJsonPayloads.read(readFrame(input), MAX_FRAME_CHARACTERS, parser);
        }
    }

    private static String readFrame(Reader input) throws IOException {
        var frame = new StringBuilder(FRAME_FAMILY);
        char[] buffer = new char[8192];
        while (true) {
            int remaining = MAX_FRAME_CHARACTERS - frame.length();
            int count = input.read(buffer, 0, Math.min(buffer.length, remaining + 1));
            if (count == -1) return frame.toString();
            if (count > remaining) throw new IOException("Portfolio analysis payload exceeds the stored frame limit");
            frame.append(buffer, 0, count);
        }
    }
}
