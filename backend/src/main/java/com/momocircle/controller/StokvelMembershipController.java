package com.momocircle.controller;

import com.momocircle.domain.User;
import com.momocircle.domain.enums.JoinRequestStatus;
import com.momocircle.domain.enums.RecurringFrequency;
import com.momocircle.domain.enums.StokvelType;
import com.momocircle.dto.StokvelMembershipDtos.AcceptInviteResult;
import com.momocircle.dto.StokvelMembershipDtos.CodeRequest;
import com.momocircle.dto.StokvelMembershipDtos.CreateInviteLink;
import com.momocircle.dto.StokvelMembershipDtos.CreatedInviteLink;
import com.momocircle.dto.StokvelMembershipDtos.DecisionRequest;
import com.momocircle.dto.StokvelMembershipDtos.DiscoverableStokvel;
import com.momocircle.dto.StokvelMembershipDtos.InviteLinkView;
import com.momocircle.dto.StokvelMembershipDtos.InvitePreview;
import com.momocircle.dto.StokvelMembershipDtos.JoinRequestCreate;
import com.momocircle.dto.StokvelMembershipDtos.JoinRequestView;
import com.momocircle.dto.StokvelMembershipDtos.MembershipSettingsView;
import com.momocircle.dto.StokvelMembershipDtos.PageView;
import com.momocircle.dto.StokvelMembershipDtos.UpdateMembershipSettings;
import com.momocircle.exception.ApiException;
import com.momocircle.security.CurrentUserService;
import com.momocircle.security.ServiceRateLimiter;
import com.momocircle.service.StokvelDiscoveryQuery;
import com.momocircle.service.StokvelMembershipService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Stokvel discovery, invite links and membership requests. The acting user always comes from the authenticated principal.
 * Every rule (who may do what, private vs discoverable, capacity, state transitions) is enforced in
 * {@link StokvelMembershipService}; nothing here decides authorisation. Hiding a button is never the control.
 */
@RestController
@RequestMapping("/api/stokvels")
public class StokvelMembershipController {

    private final CurrentUserService currentUser;
    private final StokvelMembershipService service;
    private final ServiceRateLimiter rateLimiter;

    public StokvelMembershipController(CurrentUserService currentUser, StokvelMembershipService service, ServiceRateLimiter rateLimiter) {
        this.currentUser = currentUser;
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    // ---------------------------------------------------------------- discovery (authenticated; discoverable stokvels only)

    @GetMapping("/discover")
    public PageView<DiscoverableStokvel> discover(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String frequency,
            @RequestParam(required = false) BigDecimal minContribution,
            @RequestParam(required = false) BigDecimal maxContribution,
            @RequestParam(required = false) String area,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        User user = currentUser.require();
        rateLimiter.check("stokvel-discover", user.getId(), 60, Duration.ofMinutes(1));
        return service.discover(user, new StokvelDiscoveryQuery.Filter(blankToNull(q), parse(StokvelType.class, type),
                parse(RecurringFrequency.class, frequency), minContribution, maxContribution, blankToNull(area)), page, size);
    }

    @GetMapping("/discover/{circleId}")
    public DiscoverableStokvel discoverOne(@PathVariable String circleId) {
        return service.publicView(circleId, currentUser.require());
    }

    // ---------------------------------------------------------------- membership settings (OWNER/ADMIN)

    @GetMapping("/{circleId}/membership-settings")
    public MembershipSettingsView settings(@PathVariable String circleId) {
        return service.settings(circleId, currentUser.require());
    }

    @PutMapping("/{circleId}/membership-settings")
    public MembershipSettingsView updateSettings(@PathVariable String circleId, @Valid @RequestBody UpdateMembershipSettings req) {
        return service.updateSettings(circleId, currentUser.require(), req);
    }

    // ---------------------------------------------------------------- join requests

    @PostMapping("/{circleId}/join-requests")
    public ResponseEntity<JoinRequestView> requestToJoin(@PathVariable String circleId, @Valid @RequestBody(required = false) JoinRequestCreate req) {
        User user = currentUser.require();
        rateLimiter.check("stokvel-join-request", user.getId(), 10, Duration.ofHours(1));
        return ResponseEntity.status(HttpStatus.CREATED).body(service.requestToJoin(circleId, user, req == null ? null : req.message()));
    }

    /** The signed-in user's own requests, across stokvels. */
    @GetMapping("/join-requests/mine")
    public List<JoinRequestView> mine() {
        return service.myRequests(currentUser.require());
    }

    @GetMapping("/{circleId}/join-requests")
    public List<JoinRequestView> list(@PathVariable String circleId, @RequestParam(required = false) String status) {
        return service.listRequests(circleId, currentUser.require(), parse(JoinRequestStatus.class, status));
    }

    @PostMapping("/{circleId}/join-requests/{requestId}/approve")
    public JoinRequestView approve(@PathVariable String circleId, @PathVariable String requestId,
                                   @Valid @RequestBody(required = false) DecisionRequest req) {
        return service.approve(circleId, requestId, currentUser.require(), req == null ? null : req.note());
    }

    @PostMapping("/{circleId}/join-requests/{requestId}/reject")
    public JoinRequestView reject(@PathVariable String circleId, @PathVariable String requestId,
                                  @Valid @RequestBody(required = false) DecisionRequest req) {
        return service.reject(circleId, requestId, currentUser.require(), req == null ? null : req.note());
    }

    @PostMapping("/{circleId}/join-requests/{requestId}/cancel")
    public JoinRequestView cancel(@PathVariable String circleId, @PathVariable String requestId) {
        return service.cancel(circleId, requestId, currentUser.require());
    }

    // ---------------------------------------------------------------- invite links

    @PostMapping("/{circleId}/invite-links")
    public ResponseEntity<CreatedInviteLink> createLink(@PathVariable String circleId, @Valid @RequestBody(required = false) CreateInviteLink req) {
        User user = currentUser.require();
        rateLimiter.check("stokvel-invite-create", user.getId(), 20, Duration.ofHours(1));
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createLink(circleId, user,
                req == null ? null : req.expiresInDays(), req == null ? null : req.maxUses()));
    }

    @GetMapping("/{circleId}/invite-links")
    public List<InviteLinkView> links(@PathVariable String circleId) {
        return service.listLinks(circleId, currentUser.require());
    }

    @PostMapping("/{circleId}/invite-links/{linkId}/revoke")
    public InviteLinkView revoke(@PathVariable String circleId, @PathVariable String linkId) {
        return service.revokeLink(circleId, linkId, currentUser.require());
    }

    /** What an invite code leads to. Read-only: it adds nobody. POST keeps the code out of URLs and access logs. */
    @PostMapping("/invite-links/preview")
    public InvitePreview preview(@Valid @RequestBody CodeRequest req) {
        User user = currentUser.require();
        rateLimiter.check("stokvel-invite-code", user.getId(), 10, Duration.ofMinutes(1));
        return service.preview(req.code(), user);
    }

    /** Redeem a code: joins immediately or creates a request, according to the stokvel's rule. Idempotent for the same person. */
    @PostMapping("/invite-links/accept")
    public AcceptInviteResult accept(@Valid @RequestBody CodeRequest req) {
        User user = currentUser.require();
        rateLimiter.check("stokvel-invite-code", user.getId(), 10, Duration.ofMinutes(1));
        return service.accept(req.code(), user);
    }

    // ---------------------------------------------------------------- helpers

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown " + type.getSimpleName());
        }
    }
}
