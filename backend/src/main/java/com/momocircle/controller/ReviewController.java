package com.momocircle.controller;

import com.momocircle.domain.enums.ReviewTargetType;
import com.momocircle.dto.ReviewDtos.CreateReviewRequest;
import com.momocircle.dto.ReviewDtos.ReviewResponse;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviewService;
    private final CurrentUserService currentUser;

    public ReviewController(ReviewService reviewService, CurrentUserService currentUser) {
        this.reviewService = reviewService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ResponseEntity<ReviewResponse> create(@Valid @RequestBody CreateReviewRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewService.create(currentUser.require(), req));
    }

    @GetMapping
    public Page<ReviewResponse> list(@RequestParam ReviewTargetType targetType,
                                      @RequestParam String targetId,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return reviewService.list(targetType, targetId, PageRequest.of(page, Math.min(size, 50)));
    }

    /** Rating breakdown (PULA verified-order reviews only). Public, like the review list. */
    @GetMapping("/summary")
    public com.momocircle.dto.ReviewDtos.ReviewSummaryResponse summary(@RequestParam ReviewTargetType targetType,
                                                                        @RequestParam String targetId) {
        return reviewService.summary(targetType, targetId);
    }
}
