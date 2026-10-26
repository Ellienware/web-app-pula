package com.momocircle.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SANDBOX ONLY — never calls a real telecom gateway. Records sent messages
 * in memory (bounded per recipient) so tests and local development can
 * assert an SMS was "sent" without any external dependency or credentials.
 */
@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "sandbox", matchIfMissing = true)
public class SandboxSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(SandboxSmsProvider.class);
    private static final int MAX_PER_RECIPIENT = 20;

    public record SentMessage(String toMsisdn, String message, Instant sentAt) {}

    private final Map<String, List<SentMessage>> outbox = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "sandbox";
    }

    @Override
    public void send(String toMsisdn, String message) {
        if (toMsisdn == null || toMsisdn.isBlank()) {
            return; // nothing to notify — e.g. a passenger not resolved from a phone number
        }
        log.info("SANDBOX SMS to {}: {}", toMsisdn, message);
        outbox.computeIfAbsent(toMsisdn, k -> new CopyOnWriteArrayList<>())
                .add(new SentMessage(toMsisdn, message, Instant.now()));
        List<SentMessage> messages = outbox.get(toMsisdn);
        while (messages.size() > MAX_PER_RECIPIENT) {
            messages.remove(0);
        }
    }

    /** Test/diagnostic hook — not exposed through any controller. */
    public List<SentMessage> sentTo(String msisdn) {
        return outbox.getOrDefault(msisdn, List.of());
    }
}
