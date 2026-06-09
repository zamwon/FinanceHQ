package com.example.finance_hq.notification;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.Emails;
import com.resend.services.emails.model.CreateEmailOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailException;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResendEmailSenderTest {

    @Mock Resend resend;
    @Mock Emails emails;

    ResendEmailSender sender;

    @BeforeEach
    void setUp() {
        when(resend.emails()).thenReturn(emails);
        sender = new ResendEmailSender(resend, "from@example.com");
    }

    @Test
    void send_succeeds_whenResendReturnsNormally() throws ResendException {
        when(emails.send(any(CreateEmailOptions.class))).thenReturn(null);

        assertThatNoException().isThrownBy(
                () -> sender.send("to@example.com", "Subject", "Body"));
    }

    @Test
    void send_throwsMailException_whenResendThrowsResendException() throws ResendException {
        when(emails.send(any(CreateEmailOptions.class))).thenThrow(new ResendException("API error"));

        assertThatThrownBy(() -> sender.send("to@example.com", "Subject", "Body"))
                .isInstanceOf(MailException.class)
                .hasMessageContaining("Resend API error");
    }
}
