package com.taxonomy.mail;

/** A generated result to return to the request's sender. */
public record MailAnalysisAttachment(String filename, String contentType, byte[] content) {
}
