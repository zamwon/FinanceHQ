package com.example.finance_hq.notification;

import org.springframework.mail.MailException;

public interface EmailSender {

    void send(String to, String subject, String text) throws MailException;
}
