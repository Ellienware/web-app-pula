package com.momocircle.controller;

import com.momocircle.dto.JourneyDtos.BusinessJourney;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.BusinessJourneyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BusinessJourneyController {
    private final BusinessJourneyService service;
    private final CurrentUserService currentUser;

    public BusinessJourneyController(BusinessJourneyService service, CurrentUserService currentUser) {
        this.service = service; this.currentUser = currentUser;
    }

    @GetMapping("/api/business/journey")
    public BusinessJourney journey() { return service.journey(currentUser.require()); }
}
