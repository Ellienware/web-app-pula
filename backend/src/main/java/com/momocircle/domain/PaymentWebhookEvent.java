package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Audit + dedupe record for a verified inbound provider webhook. */
@Entity
@Table(name = "payment_webhook_events")
public class PaymentWebhookEvent extends BaseEntity {
    @Column(nullable = false, length = 32) private String provider;
    @Column(nullable = false, length = 100) private String dedupeKey;
    @Column(length = 64) private String eventType;
    @Column(length = 200) private String reference;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String dedupeKey) { this.dedupeKey = dedupeKey; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
}
