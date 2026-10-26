package com.momocircle.controller;

import com.momocircle.dto.NotificationDtos.NotificationResponse;
import com.momocircle.dto.NotificationDtos.UnreadCountResponse;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.NotificationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final CurrentUserService currentUser;

    public NotificationController(NotificationService notificationService, CurrentUserService currentUser) {
        this.notificationService = notificationService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Page<NotificationResponse> list(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return notificationService.list(currentUser.require(), PageRequest.of(page, Math.min(size, 50)));
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount() {
        return new UnreadCountResponse(notificationService.unreadCount(currentUser.require()));
    }

    @PostMapping("/{notificationId}/read")
    public void markRead(@PathVariable String notificationId) {
        notificationService.markRead(currentUser.require(), notificationId);
    }
}
