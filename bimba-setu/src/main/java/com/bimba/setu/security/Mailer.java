package com.bimba.setu.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends the console's e-mails (password reset links) through the SMTP server in BIMBA_SMTP_* (spring.mail.*).
 * Without an SMTP host the message is written to the Setu log instead, so an administrator can still pass the link
 * on while mail is not set up. Mails go out on a background thread: the request answers in the same time whether or
 * not an e-mail was sent, so the answer does not reveal which addresses have an account.
 */
@Component
public class Mailer {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Mail");

    private final ObjectProvider<JavaMailSender> sender;
    private final String host;
    private final String from;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BIMBA-Mail");
        t.setDaemon(true);
        return t;
    });

    public Mailer(ObjectProvider<JavaMailSender> sender, @Value("${spring.mail.host:}") String host,
                  @Value("${bimba.mail-from:}") String from, @Value("${spring.mail.username:}") String user) {
        this.sender = sender;
        this.host = host == null ? "" : host.trim();
        this.from = from != null && !from.isBlank() ? from.trim() : user != null && user.contains("@") ? user.trim() : "bimba-setu@localhost";
    }

    public boolean configured() {
        return !host.isEmpty() && sender.getIfAvailable() != null;
    }

    /** Queues the message; logText = what to log instead when mail is not set up (may contain the link). */
    public void send(String to, String subject, String text, String logText) {
        pool.submit(() -> {
            JavaMailSender s = sender.getIfAvailable();
            if (host.isEmpty() || s == null) {
                log.warn("SMTP is not set up (BIMBA_SMTP_HOST): e-mail to {} not sent. {}", to, logText);
                return;
            }
            try {
                SimpleMailMessage m = new SimpleMailMessage();
                m.setFrom(from);
                m.setTo(to);
                m.setSubject(subject);
                m.setText(text);
                s.send(m);
                log.info("e-mail \"{}\" sent to {}", subject, to);
            } catch (RuntimeException e) {
                log.warn("e-mail \"{}\" to {} could not be sent through {}: {}", subject, to, host, e.getMessage());
            }
        });
    }
}
