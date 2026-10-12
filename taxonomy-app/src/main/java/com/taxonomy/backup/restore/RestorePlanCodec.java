package com.taxonomy.backup.restore;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** A private compiled plan contract, never a generic entity or user-selected type reader. */
final class RestorePlanCodec {
    static final int MAX_BYTES=32*1024*1024;
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(100).maxStringLength(MAX_BYTES).maxNumberLength(100).build()).build())
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    static String encode(RestorePlan plan)throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var limited=new OutputStream(){
            int count;
            @Override public void write(int value)throws IOException{if(count>=MAX_BYTES)throw budget();bytes.write(value);count++;}
            @Override public void write(byte[] buffer,int offset,int length)throws IOException{if(length>MAX_BYTES-count)throw budget();bytes.write(buffer,offset,length);count+=length;}
        }){JSON.writeValue(limited,plan);}
        return bytes.toString(StandardCharsets.UTF_8);
    }
    static RestorePlan decode(String json)throws IOException {
        try{if(json.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw invalid();return JSON.readValue(json,RestorePlan.class);}
        catch(RuntimeException|JsonProcessingException malformed){throw invalid();}
    }
    private static IOException invalid(){return new IOException("Restore plan payload proof is invalid");}
    private static IOException budget(){return new IOException("Restore plan payload budget exceeded");}
}
