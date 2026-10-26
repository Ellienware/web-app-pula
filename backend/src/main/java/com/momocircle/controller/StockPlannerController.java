package com.momocircle.controller;

import com.momocircle.security.CurrentUserService;
import com.momocircle.service.StockPlannerService;
import com.momocircle.service.StockPlannerService.PlanRequest;
import com.momocircle.service.StockPlannerService.StockPlan;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StockPlannerController {
    private final StockPlannerService service;
    private final CurrentUserService currentUser;

    public StockPlannerController(StockPlannerService service, CurrentUserService currentUser) {
        this.service = service; this.currentUser = currentUser;
    }

    /** Read-only proposal. Buying still requires cart + checkout + payment. */
    @PostMapping("/api/stock/planner")
    public StockPlan plan(@RequestBody PlanRequest req) {
        return service.plan(currentUser.require(), req.budget(), req.category());
    }
}
