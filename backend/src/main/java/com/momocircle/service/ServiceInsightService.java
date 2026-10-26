package com.momocircle.service;

import com.momocircle.domain.ServiceQuote;
import com.momocircle.domain.ServiceRequest;
import com.momocircle.domain.User;
import com.momocircle.dto.ServiceDtos.InsightsView;
import com.momocircle.dto.ServiceDtos.ListingView;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.ServiceQuoteRepository;
import com.momocircle.repository.ServiceRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * READ-ONLY explanations for the assistant and the UI (explain a quote, compare providers). It depends only on read
 * paths, so nothing built on it can accept a quote, book, order materials or pay. People do those, explicitly.
 */
@Service
public class ServiceInsightService {

    static final String NOTICE = "This is an explanation only. Nothing is accepted, booked, ordered or paid until you do it yourself.";

    private final ServiceRequestRepository requests;
    private final ServiceQuoteRepository quotes;
    private final ServiceRequestService requestService;
    private final ServiceDiscoveryService discovery;

    public ServiceInsightService(ServiceRequestRepository requests, ServiceQuoteRepository quotes,
                                 ServiceRequestService requestService, ServiceDiscoveryService discovery) {
        this.requests = requests;
        this.quotes = quotes;
        this.requestService = requestService;
        this.discovery = discovery;
    }

    @Transactional(readOnly = true)
    public InsightsView explainQuote(User viewer, String requestId, String quoteId) {
        ServiceRequest r = requests.findById(requestId).orElseThrow(() -> ApiException.notFound("Request not found"));
        requestService.requireParty(r, viewer);
        ServiceQuote q = quotes.findByIdAndRequestId(quoteId, requestId).orElseThrow(() -> ApiException.notFound("Quote not found"));
        String cur = q.getCurrency();
        List<String> points = new ArrayList<>();
        points.add("Labour: " + cur + " " + q.getLabourAmount() + pct(q.getLabourAmount(), q.getTotal())
                + (q.getHours() != null ? " (" + q.getHours() + " hours at the listed rate)" : ""));
        if (q.getMaterialsAmount().signum() > 0) {
            points.add("Materials: " + cur + " " + q.getMaterialsAmount() + pct(q.getMaterialsAmount(), q.getTotal())
                    + ", priced from PULA Stock when the quote was made.");
        }
        if (q.getDeliveryAmount().signum() > 0) points.add("Delivery: " + cur + " " + q.getDeliveryAmount() + pct(q.getDeliveryAmount(), q.getTotal()));
        if (q.getOtherAmount().signum() > 0) points.add("Other (" + q.getOtherDescription() + "): " + cur + " " + q.getOtherAmount());
        Duration left = Duration.between(Instant.now(), q.getValidUntil());
        points.add(left.isNegative() ? "This quote has expired." : "This quote is valid for another " + left.toHours() + " hours.");
        points.add("The total is calculated by PULA from these parts; it cannot be changed after the quote is sent.");
        return new InsightsView("Quote " + q.getVersionNo() + " totals " + cur + " " + q.getTotal() + " for " + r.getListing().getName() + ".",
                points, NOTICE);
    }

    @Transactional(readOnly = true)
    public InsightsView compare(List<String> listingIds) {
        List<ListingView> ls = discovery.compare(listingIds);
        List<String> points = new ArrayList<>();
        ls.stream().filter(l -> l.price() != null).min(Comparator.comparing(ListingView::price)).ifPresent(l ->
                points.add("Lowest listed price: " + l.businessName() + " at " + l.currency() + " " + l.price() + " (" + l.pricingModel() + ")."));
        ls.stream().filter(l -> l.ratingCount() > 0).max(Comparator.comparing(ListingView::ratingAverage)).ifPresent(l ->
                points.add("Best rated: " + l.businessName() + " (" + l.ratingAverage() + " from " + l.ratingCount() + " reviews)."));
        ls.stream().filter(l -> l.pricingModel() == com.momocircle.domain.enums.ServicePricingModel.QUOTE_REQUIRED).findFirst()
                .ifPresent(l -> points.add("Some of these need a quote first, so their final price is not known until you ask."));
        return new InsightsView("Comparing " + ls.size() + " services.", points, NOTICE);
    }

    private static String pct(BigDecimal part, BigDecimal total) {
        if (total.signum() == 0) return "";
        return " (" + part.multiply(BigDecimal.valueOf(100)).divide(total, 0, RoundingMode.HALF_UP) + "%)";
    }
}
