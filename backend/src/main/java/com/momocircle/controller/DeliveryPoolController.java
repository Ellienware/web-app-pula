package com.momocircle.controller;

import com.momocircle.security.CurrentUserService;
import com.momocircle.service.DeliveryPoolService;
import com.momocircle.service.DeliveryPoolService.AddPoolMemberRequest;
import com.momocircle.service.DeliveryPoolService.DriverCapability;
import com.momocircle.service.DeliveryPoolService.PoolMemberResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class DeliveryPoolController {
    private final DeliveryPoolService service;
    private final CurrentUserService currentUser;

    public DeliveryPoolController(DeliveryPoolService service, CurrentUserService currentUser) {
        this.service = service; this.currentUser = currentUser;
    }

    @GetMapping("/api/stock/pool/members")
    public List<PoolMemberResponse> members() { return service.members(currentUser.require()); }

    @PostMapping("/api/stock/pool/members")
    public PoolMemberResponse add(@Valid @RequestBody AddPoolMemberRequest req) {
        return service.add(currentUser.require(), req.phoneNumber());
    }

    @DeleteMapping("/api/stock/pool/members/{memberId}")
    public PoolMemberResponse remove(@PathVariable String memberId) {
        return service.deactivate(currentUser.require(), memberId);
    }

    @GetMapping("/api/drivers/me/pool-invites")
    public List<DeliveryPoolService.PoolInviteResponse> invites() { return service.myInvites(currentUser.require()); }

    @PostMapping("/api/drivers/me/pool-invites/{id}/accept")
    public void accept(@PathVariable String id) { service.respondToInvite(currentUser.require(), id, true); }

    @PostMapping("/api/drivers/me/pool-invites/{id}/decline")
    public void decline(@PathVariable String id) { service.respondToInvite(currentUser.require(), id, false); }

    @GetMapping("/api/drivers/me/capabilities")
    public List<DriverCapability> myCapabilities() { return service.capabilitiesFor(currentUser.require()); }
}
