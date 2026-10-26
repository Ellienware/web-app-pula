package com.momocircle.controller;

import com.momocircle.domain.User;
import com.momocircle.security.CurrentUserService;
import com.momocircle.settlement.MarketplaceSettlementService;
import com.momocircle.settlement.SettlementDtos.*;
import com.momocircle.settlement.SettlementInsightService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Seller side: a business sees only its OWN settlements (others answer 404). No endpoint here can change a settlement. */
@RestController
@RequestMapping("/api/marketplace/settlements")
public class MarketplaceSettlementController {

    private final MarketplaceSettlementService settlements;
    private final SettlementInsightService insights;
    private final CurrentUserService currentUser;

    public MarketplaceSettlementController(MarketplaceSettlementService settlements, SettlementInsightService insights,
                                           CurrentUserService currentUser) {
        this.settlements = settlements;
        this.insights = insights;
        this.currentUser = currentUser;
    }

    @GetMapping("/mine")
    public List<SettlementView> mine(@RequestParam(defaultValue = "50") int limit) {
        return settlements.mine(currentUser.require(), limit);
    }

    @GetMapping("/mine/summary")
    public SellerSummary summary(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        Instant end = to == null ? Instant.now().plus(1, ChronoUnit.DAYS) : to;
        Instant start = from == null ? end.minus(90, ChronoUnit.DAYS) : from;
        return settlements.sellerSummary(currentUser.require(), start, end);
    }

    @GetMapping("/mine/{id}")
    public SettlementView one(@PathVariable String id) {
        return settlements.mineOne(currentUser.require(), id);
    }

    @GetMapping("/mine/{id}/explain")
    public InsightsView explain(@PathVariable String id) {
        User u = currentUser.require();
        return insights.explainSettlement(u, false, id);
    }
}
