package com.momocircle.service;

import com.momocircle.domain.User;
import com.momocircle.dto.SaveToBuyDtos.InsightsView;
import com.momocircle.dto.SaveToBuyDtos.SaveToBuyView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * READ-ONLY explanations for the assistant and the UI. Its only dependency is the read path of SaveToBuyService, so
 * nothing built on it can create, approve, check out or pay. Recommendations stay proposals until a person acts.
 */
@Service
public class SaveToBuyInsightService {

    private final SaveToBuyService saveToBuy;

    public SaveToBuyInsightService(SaveToBuyService saveToBuy) {
        this.saveToBuy = saveToBuy;
    }

    @Transactional
    public InsightsView insights(String requestId, User viewer, LocalDate today) {
        SaveToBuyView v = saveToBuy.get(requestId, viewer);
        String cur = v.currency();
        String summary = "Your goal \"" + v.goalTitle() + "\" has " + cur + " " + v.saved() + " tracked of " + cur + " "
                + v.goalTarget() + ". The planned purchase is " + cur + " " + v.requestedAmount() + " (" + v.purpose() + ").";

        String shortfall;
        if (v.shortfall().signum() == 0) {
            shortfall = v.differenceFromTarget().signum() > 0
                    ? "Your tracked savings cover this purchase. It costs " + cur + " " + v.differenceFromTarget()
                            + " more than the goal target, which is paid at checkout."
                    : "Your tracked savings cover this purchase.";
        } else {
            shortfall = "Tracked savings are " + cur + " " + v.shortfall() + " short of the purchase. "
                    + "You can still check out; the checkout charges the real total and the difference is paid there.";
        }

        String advice = null;
        if (v.shortfall().signum() > 0) {
            // Deadline lives on the goal; the view does not expose it, so advise on a 3-month horizon by default.
            long months = 3;
            BigDecimal perMonth = v.shortfall().divide(BigDecimal.valueOf(months), 2, RoundingMode.CEILING);
            advice = "Saving about " + cur + " " + perMonth + " a month would close the gap in " + months + " months.";
        }
        List<String> suggestions = new ArrayList<>();
        suggestions.add("Compare prices in the marketplace before you check out; a cheaper basket reduces the shortfall.");
        if (v.shortfall().signum() > 0) {
            suggestions.add("Consider a smaller basket, or buy the essentials now and the rest later.");
        }
        return new InsightsView(summary, shortfall, advice, suggestions,
                "This is an explanation only. Nothing is bought or paid until the named buyer completes checkout.");
    }
}
