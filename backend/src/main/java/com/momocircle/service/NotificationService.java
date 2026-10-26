package com.momocircle.service;

import com.momocircle.domain.Notification;
import com.momocircle.domain.Order;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.NotificationType;
import com.momocircle.dto.NotificationDtos.NotificationResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Central place to raise and read in-app notifications. Kept provider-agnostic
 * (no push/SMS/email wiring yet) so a real channel can be plugged in later
 * without touching any of the call sites below.
 */
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;

    /** Optional SMS channel (sandbox provider logs only). Field-injected so existing constructors are unchanged. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.sms.SmsProvider smsProvider;

    @org.springframework.beans.factory.annotation.Value("${app.sms.order-notifications:true}")
    private boolean smsOrderNotifications = true;

    /** Notification types worth an SMS for low-data users: orders, payments, delivery and payouts. */
    private static final java.util.Set<NotificationType> SMS_TYPES = java.util.EnumSet.of(
            NotificationType.ORDER_RECEIVED, NotificationType.PAYMENT_SUCCESS,
            NotificationType.PAYMENT_FAILED, NotificationType.DELIVERY_UPDATE);

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    public void notify(User recipient, NotificationType type, String message, String referenceId) {
        Notification n = new Notification();
        n.setRecipient(recipient);
        n.setType(type);
        n.setMessage(message);
        n.setReferenceId(referenceId);
        notificationRepository.save(n);
        sendSmsBestEffort(recipient, type, message);
    }

    /** SMS must never break the business action that triggered it, so all failures are swallowed. */
    private void sendSmsBestEffort(User recipient, NotificationType type, String message) {
        if (smsProvider == null || !smsOrderNotifications || recipient == null || !SMS_TYPES.contains(type)) return;
        String phone = recipient.getPhoneNumber();
        if (phone == null || phone.isBlank()) return;
        try {
            smsProvider.send(phone, "PULA: " + (message.length() > 140 ? message.substring(0, 137) + "..." : message));
        } catch (Exception ignored) {
            // best effort
        }
    }

    public void notifyOrderReceived(Order order) {
        notify(order.getBusiness().getOwner(), NotificationType.ORDER_RECEIVED,
                order.getBuyer().getFullName() + " placed an order for "
                        + order.getTotal().toPlainString() + " " + order.getCurrency(),
                order.getId());
        notify(order.getBuyer(), NotificationType.ORDER_PAID,
                "Your order at " + order.getBusiness().getName() + " was paid successfully",
                order.getId());
    }

    public void notifyOrderStatusChanged(Order order) {
        NotificationType type = switch (order.getStatus()) {
            case PROCESSING -> NotificationType.ORDER_PROCESSING;
            case COMPLETED -> NotificationType.ORDER_COMPLETED;
            case REFUNDED -> NotificationType.REFUND;
            default -> NotificationType.SYSTEM;
        };
        notify(order.getBuyer(), type,
                "Your order at " + order.getBusiness().getName() + " is now " + order.getStatus(),
                order.getId());
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(User user, Pageable pageable) {
        return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(user.getId(), pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public long unreadCount(User user) {
        return notificationRepository.countByRecipientIdAndReadFalse(user.getId());
    }

    @Transactional
    public void markRead(User user, String notificationId) {
        Notification n = notificationRepository.findById(notificationId)
                .orElseThrow(() -> ApiException.notFound("Notification not found"));
        if (!n.getRecipient().getId().equals(user.getId())) {
            throw ApiException.forbidden("This is not your notification");
        }
        n.setRead(true);
        notificationRepository.save(n);
    }

    private NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getMessage(), n.getReferenceId(),
                n.isRead(), n.getCreatedAt());
    }
}
