package com.taxonomy.mail;

import com.taxonomy.provenance.service.DocumentLimitException;
import com.taxonomy.provenance.service.DocumentParserService;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import org.jsoup.Jsoup;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

final class MailRequestParser {

    private static final int MAX_SUBJECT_LENGTH = 500;
    private static final int MAX_MIME_DEPTH = 8;
    private static final int MAX_MIME_PARTS = 64;
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final DocumentParserService documentParser;
    private final MailTransportProperties properties;
    private final Set<String> allowedSenders;
    private final String ownAddress;

    MailRequestParser(DocumentParserService documentParser, MailTransportProperties properties) {
        this.documentParser = documentParser;
        this.properties = properties;
        allowedSenders = properties.allowedSenders().stream().map(MailRequestParser::canonicalAddress)
                .collect(Collectors.toUnmodifiableSet());
        ownAddress = canonicalAddress(properties.smtp().from());
    }

    String acceptedSender(Message message) throws MessagingException {
        if (message.getHeader("From") == null || automated(message)) {
            return null;
        }
        Address[] from = message.getFrom();
        if (from == null || from.length != 1 || !(from[0] instanceof InternetAddress internet) || internet.isGroup()) {
            return null;
        }
        try {
            String sender = canonicalAddress(internet.getAddress());
            return allowedSenders.contains(sender) && !sender.equals(ownAddress) ? sender : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static boolean automated(Message message) throws MessagingException {
        return Arrays.stream(headers(message, "Auto-Submitted"))
                    .anyMatch(value -> !"no".equalsIgnoreCase(value.strip()))
                || Arrays.stream(headers(message, "Precedence"))
                    .anyMatch(value -> Set.of("bulk", "list", "junk").contains(value.strip().toLowerCase(Locale.ROOT)))
                || message.getHeader("List-Id") != null
                || Arrays.stream(headers(message, "Return-Path")).anyMatch(value -> "<>".equals(value.strip()));
    }

    private static String[] headers(Message message, String key) throws MessagingException {
        String[] values = message.getHeader(key);
        return values == null ? new String[0] : values;
    }

    static String canonicalAddress(String address) {
        if (address == null || address.length() > 254 || address.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Mail address is invalid");
        }
        try {
            var parsed = new InternetAddress(address.strip(), true);
            parsed.validate();
            if (parsed.isGroup() || parsed.getPersonal() != null || !parsed.getAddress().equals(address.strip())) {
                throw new AddressException("Expected a single mailbox address");
            }
            String mailbox = parsed.getAddress();
            int domain = mailbox.lastIndexOf('@');
            if (domain < 1) {
                throw new AddressException("Expected a mailbox with a domain");
            }
            return mailbox.substring(0, domain) + mailbox.substring(domain).toLowerCase(Locale.ROOT);
        } catch (AddressException invalid) {
            throw new IllegalArgumentException("Mail address is invalid", invalid);
        }
    }

    IncomingMail parse(String messageId, String sender, Message message) throws IOException, MessagingException {
        String subject = message.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT_LENGTH) {
            throw new Rejected("Der Betreff muss den Auftrag in höchstens 500 Zeichen beschreiben.");
        }
        int size = message.getSize();
        if (size > properties.maxMessageBytes()) {
            throw new Rejected("Die Nachricht überschreitet die konfigurierte Größenbegrenzung.");
        }
        var content = new RequirementContent();
        readPart(message, content, 0);
        String requirement = content.body.toString().strip();
        if (!content.attachments.isEmpty()) {
            requirement = (requirement + "\n\n" + content.attachments.toString().strip()).strip();
        }
        if (requirement.isBlank()) {
            throw new Rejected("Der Nachrichtentext und die Anhänge enthalten keine lesbare Anforderung.");
        }
        return new IncomingMail(messageId, sender, subject.strip(), requirement);
    }

    private void readPart(Part part, RequirementContent content, int depth) throws IOException, MessagingException {
        if (depth > MAX_MIME_DEPTH || ++content.parts > MAX_MIME_PARTS) {
            throw new Rejected("Die Nachricht enthält zu viele verschachtelte oder einzelne MIME-Teile.");
        }
        String name = part.getFileName();
        boolean explicitAttachment = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition());
        if (!explicitAttachment && part.isMimeType("image/*") && part.getHeader("Content-ID") != null) {
            return;
        }
        if (explicitAttachment || name != null) {
            readAttachment(part, content, name);
        } else if (part.isMimeType("multipart/*")) {
            readMultipart(part, content, depth);
        } else if (part.isMimeType("text/plain") || part.isMimeType("text/html")) {
            content.append(content.body, readText(part, content));
        } else {
            throw new Rejected("Ein Teil der Nachricht hat kein unterstütztes Textformat.");
        }
    }

    private void readMultipart(Part part, RequirementContent content, int depth) throws IOException, MessagingException {
        if (!(part.getContent() instanceof Multipart multipart) || multipart.getCount() > MAX_MIME_PARTS) {
            throw new Rejected("Die Nachricht enthält zu viele oder ungültige MIME-Teile.");
        }
        if (part.isMimeType("multipart/alternative")) {
            Part html = null;
            for (int index = 0; index < multipart.getCount(); index++) {
                Part alternative = multipart.getBodyPart(index);
                if (alternative.isMimeType("text/plain")) {
                    readPart(alternative, content, depth + 1);
                    return;
                }
                if (alternative.isMimeType("text/html") || alternative.isMimeType("multipart/related")) {
                    html = alternative;
                }
            }
            if (html != null) {
                readPart(html, content, depth + 1);
            }
        } else {
            for (int index = 0; index < multipart.getCount(); index++) {
                readPart(multipart.getBodyPart(index), content, depth + 1);
            }
        }
    }

    private void readAttachment(Part part, RequirementContent content, String filename) throws IOException, MessagingException {
        if (++content.attachmentCount > properties.maxAttachments()) {
            throw new Rejected("Die Nachricht enthält mehr Anhänge als erlaubt.");
        }
        String name = cleanFilename(filename);
        String lower = name.toLowerCase(Locale.ROOT);
        String mime = baseType(part);
        String text;
        if (mime.equals("application/pdf") || mime.equals(DOCX) || lower.endsWith(".pdf") || lower.endsWith(".docx")) {
            byte[] bytes = content.readBytes(part);
            try {
                text = documentParser.extractCompleteText(new DocumentPart(name, mime, bytes));
            } catch (DocumentLimitException | IOException invalid) {
                throw new Rejected("Ein PDF- oder DOCX-Anhang konnte nicht vollständig gelesen werden.");
            }
        } else if (mime.equals("text/plain") || mime.equals("text/markdown") || mime.equals("text/html")
                || lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".html")) {
            text = readText(part, content);
            if (!mime.equals("text/html") && lower.endsWith(".html")) {
                text = Jsoup.parse(text).text();
            }
        } else {
            throw new Rejected("Als Anhänge sind PDF, DOCX, Text, Markdown und HTML möglich.");
        }
        if (text.isBlank()) {
            throw new Rejected("Ein Anhang enthält keinen lesbaren Text.");
        }
        content.append(content.attachments, "Anhang: " + name + "\n" + text);
    }

    private String readText(Part part, RequirementContent content) throws IOException, MessagingException {
        ContentType type = new ContentType(part.getContentType());
        String declared = type.getParameter("charset");
        byte[] bytes = content.readBytes(part);
        String text;
        try {
            Charset charset = declared == null ? StandardCharsets.UTF_8 : Charset.forName(MimeUtility.javaCharset(declared));
            text = charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException invalid) {
            throw new Rejected("Die Zeichenkodierung eines Textteils konnte nicht gelesen werden.");
        }
        if (type.match("text/html")) {
            text = Jsoup.parse(text).text();
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static String baseType(Part part) throws MessagingException {
        return new ContentType(part.getContentType()).getBaseType().toLowerCase(Locale.ROOT);
    }

    static String cleanFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "Anhang";
        }
        String name = filename.replaceAll("[\\p{Cntrl}\\\\/]", "_").strip();
        return name.length() <= 160 ? name : name.substring(0, 80) + name.substring(name.length() - 80);
    }

    static final class Rejected extends IOException {
        Rejected(String message) {
            super(message);
        }
    }

    private final class RequirementContent {
        private final StringBuilder body = new StringBuilder();
        private final StringBuilder attachments = new StringBuilder();
        private int bytes;
        private int parts;
        private int attachmentCount;

        private byte[] readBytes(Part part) throws IOException, MessagingException {
            int remaining = properties.maxMessageBytes() - bytes;
            try (InputStream input = part.getInputStream()) {
                byte[] read = input.readNBytes(remaining + 1);
                if (read.length > remaining) {
                    throw new Rejected("Die Nachricht überschreitet die konfigurierte Größenbegrenzung.");
                }
                bytes += read.length;
                return read;
            }
        }

        private void append(StringBuilder target, String text) throws Rejected {
            if (text.isBlank()) {
                return;
            }
            if (!target.isEmpty()) {
                target.append("\n\n");
            }
            target.append(text);
            int separator = !body.isEmpty() && !attachments.isEmpty() ? 2 : 0;
            if (body.length() + attachments.length() + separator > properties.maxRequirementCharacters()) {
                throw new Rejected("Anforderungstext und Anhänge sind zusammen länger als erlaubt.");
            }
        }
    }

    private record DocumentPart(String filename, String contentType, byte[] bytes) implements MultipartFile {
        @Override public String getName() { return "attachment"; }
        @Override public String getOriginalFilename() { return filename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File destination) throws IOException { Files.write(destination.toPath(), bytes); }
    }
}
