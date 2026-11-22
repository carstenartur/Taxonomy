package com.taxonomy.mail;

import java.util.List;

public interface MailAnalysisDelivery {

    void sendResult(String recipient, String subject, String summary, List<MailAnalysisAttachment> attachments);

    void sendFailure(String recipient, String subject, String message);
}
