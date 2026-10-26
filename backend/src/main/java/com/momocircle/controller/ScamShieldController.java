package com.momocircle.controller;

import com.momocircle.dto.ScamShieldDtos.AssessScamRequest;
import com.momocircle.dto.ScamShieldDtos.ScamAssessmentResponse;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.ScamShieldService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Same no-id, currentUser-only scoping as MoneyController — see that
 * class's javadoc for why. Never accepts or returns a raw OTP/PIN/password;
 * see ScamShieldService#redact.
 */
@RestController
@RequestMapping("/api/money/scams")
public class ScamShieldController {

    private final ScamShieldService scamShieldService;
    private final CurrentUserService currentUser;

    public ScamShieldController(ScamShieldService scamShieldService, CurrentUserService currentUser) {
        this.scamShieldService = scamShieldService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ResponseEntity<ScamAssessmentResponse> assess(@Valid @RequestBody AssessScamRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(scamShieldService.assess(currentUser.require(), req));
    }

    @GetMapping
    public List<ScamAssessmentResponse> history() {
        return scamShieldService.history(currentUser.require());
    }
}
