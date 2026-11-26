package com.sskings.agendai.notification;

import com.sskings.agendai.common.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** SMTP do provedor (application.yml: spring.mail.*). Falha → 503, sem o detalhe do provedor e sem o endereço no log. */
@Slf4j
@Component
class SmtpMailer implements Mailer {

    static final String UNAVAILABLE = "Email unavailable, try again";

    private final JavaMailSender sender;
    private final String from;

    SmtpMailer(JavaMailSender sender, @Value("${agendai.mail.from}") String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String text) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(to);
        m.setSubject(subject);
        m.setText(text);
        try {
            sender.send(m);
        } catch (MailException e) {
            log.warn("Envio de e-mail falhou: {}", e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
        }
    }
}
