package com.momocircle.controller;

import com.momocircle.dto.ExpenseDtos.CreateExpenseRequest;
import com.momocircle.dto.ExpenseDtos.ExpenseResponse;
import com.momocircle.dto.ExpenseDtos.NoteRequest;
import com.momocircle.dto.ExpenseDtos.PreviewResponse;
import com.momocircle.dto.ExpenseDtos.UpdateExpenseRequest;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.ExpenseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Circle-scoped expense endpoints (the original path, extended). Per-share responses and settlement live in
 * {@link SplitBillController} because a participant who has left the circle must still be able to answer and pay.
 * The old {@code POST .../shares/{id}/settle} (debtor or any admin could mark a share paid) was removed: see PHASE_9C_REPORT.md.
 */
@RestController
@RequestMapping("/api/circles/{circleId}/expenses")
public class ExpenseController {

    private final ExpenseService expenseService;
    private final CurrentUserService currentUser;

    public ExpenseController(ExpenseService expenseService, CurrentUserService currentUser) {
        this.expenseService = expenseService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ExpenseResponse> list(@PathVariable String circleId) {
        return expenseService.list(circleId, currentUser.require());
    }

    /** Optional {@code Idempotency-Key}: a retried create returns the first expense instead of making a second. */
    @PostMapping
    public ResponseEntity<ExpenseResponse> create(@PathVariable String circleId,
                                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                  @Valid @RequestBody CreateExpenseRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(expenseService.create(circleId, currentUser.require(), req, idempotencyKey));
    }

    /** Calculates the shares without saving, so clients never do money arithmetic themselves. */
    @PostMapping("/preview")
    public PreviewResponse preview(@PathVariable String circleId, @Valid @RequestBody CreateExpenseRequest req) {
        return expenseService.preview(circleId, currentUser.require(), req);
    }

    @PutMapping("/{expenseId}")
    public ExpenseResponse update(@PathVariable String circleId, @PathVariable String expenseId,
                                  @Valid @RequestBody UpdateExpenseRequest req) {
        return expenseService.update(circleId, expenseId, currentUser.require(), req);
    }

    @PostMapping("/{expenseId}/cancel")
    public ExpenseResponse cancel(@PathVariable String circleId, @PathVariable String expenseId,
                                  @Valid @RequestBody(required = false) NoteRequest req) {
        return expenseService.cancel(circleId, expenseId, currentUser.require(), req == null ? null : req.note());
    }
}
