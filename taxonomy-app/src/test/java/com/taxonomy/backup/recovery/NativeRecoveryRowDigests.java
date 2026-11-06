package com.taxonomy.backup.recovery;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Types;
import java.util.*;

/** Compares logical fixture rows without relying on vendor row order or LOB locator identity. */
final class NativeRecoveryRowDigests {
    private NativeRecoveryRowDigests() { }

    static Map<String, String> tables(Connection connection, List<String> tables) throws Exception {
        var hashes = new TreeMap<String, String>();
        for (String table : tables) {
            var rowHashes = new ArrayList<String>();
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT * FROM \"" + table.replace("\"", "\"\"") + "\"")) {
                var metadata = result.getMetaData();
                while (result.next()) {
                    var digest = MessageDigest.getInstance("SHA-256");
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        String value;
                        switch (metadata.getColumnType(i)) {
                            case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> {
                                try (var stream = result.getBinaryStream(i)) {
                                    if (stream == null) value = null;
                                    else {
                                        var lob = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; int count;
                                        while ((count = stream.read(buffer)) >= 0) lob.update(buffer, 0, count);
                                        value = HexFormat.of().formatHex(lob.digest());
                                    }
                                }
                            }
                            case Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR -> {
                                try (var reader = result.getCharacterStream(i)) {
                                    if (reader == null) value = null;
                                    else {
                                        var text = new StringBuilder(); char[] buffer = new char[8192]; int count;
                                        while ((count = reader.read(buffer)) >= 0) text.append(buffer, 0, count);
                                        value = hash(text.toString().getBytes(StandardCharsets.UTF_8));
                                    }
                                }
                            }
                            case Types.NUMERIC, Types.DECIMAL -> {
                                var number = result.getBigDecimal(i); value = number == null ? null : number.stripTrailingZeros().toPlainString();
                            }
                            default -> value = result.getString(i);
                        }
                        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
                        digest.update(ByteBuffer.allocate(4).putInt(value == null ? -1 : bytes.length).array());
                        digest.update(bytes);
                    }
                    rowHashes.add(HexFormat.of().formatHex(digest.digest()));
                }
            }
            Collections.sort(rowHashes);
            hashes.put(table, hash(String.join("\n", rowHashes).getBytes(StandardCharsets.UTF_8)));
        }
        return hashes;
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
