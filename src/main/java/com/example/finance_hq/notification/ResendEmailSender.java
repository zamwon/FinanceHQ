package com.example.finance_hq.notification;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Service;

@Service
public class ResendEmailSender implements EmailSender {

    private final Resend resend;
    private final String fromAddress;

    @Autowired
    public ResendEmailSender(@Value("${resend.api-key}") String apiKey,
                             @Value("${resend.from-address}") String fromAddress) {
        this.resend = new Resend(apiKey);
        this.fromAddress = fromAddress;
    }

    // package-private for unit testing
    ResendEmailSender(Resend resend, String fromAddress) {
        this.resend = resend;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(String to, String subject, String text) throws MailException {
        try {
            CreateEmailOptions params = CreateEmailOptions.builder()
                    .from(fromAddress)
                    .to(to)
                    .subject(subject)
                    .text(text)
                    .build();
            resend.emails().send(params);
        } catch (ResendException e) {
            throw new MailSendException("Resend API error: " + e.getMessage());
        }
    }
}
