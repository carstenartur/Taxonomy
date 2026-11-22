package com.taxonomy.mail;

/** One authorized, deduplicable request from a configured intake mailbox. */
public record IncomingMail(String messageId, String sender, String subject, String requirementText) {
}
