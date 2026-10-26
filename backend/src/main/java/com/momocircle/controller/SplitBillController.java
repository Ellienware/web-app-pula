package com.momocircle.controller;

import com.momocircle.dto.ExpenseDtos.ConfirmRequest;
import com.momocircle.dto.ExpenseDtos.EventResponse;
import com.momocircle.dto.ExpenseDtos.ExpenseResponse;
import com.momocircle.dto.ExpenseDtos.NoteRequest;
import com.momocircle.dto.ExpenseDtos.ObligationResponse;
import com.momocircle.dto.ExpenseDtos.SettlementResponse;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.ExpenseService;
import com.momocircle.service.ExpenseSettlementService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Split-bill endpoints that are not tied to current circle membership. The acting user always comes from the authenticated
 * principal; a share id in the path is only ever acted on after the service checks that this user is the participant (for
 * responding and paying) or the payer (for asking again, refunding and recording repayment outside PULA).
 * All settlement here is SIMULATED demo-wallet value.
 */
@RestController
@RequestMapping("/api/split-bills")
public class SplitBillController {

    private final ExpenseService expenses;
    private final ExpenseSettlementService settlement;
    private final CurrentUserService currentUser;

    public SplitBillController(ExpenseService expenses, ExpenseSettlementService settlement, CurrentUserService currentUser) {
        this.expenses = expenses;
        this.settlement = settlement;
        this.currentUser = currentUser;
    }

    /** {@code role=OWE} (default): what I owe. {@code role=OWED}: what others owe me. */
    @GetMapping("/mine")
    public List<ObligationResponse> mine(@RequestParam(defaultValue = "OWE") String role, @RequestParam(defaultValue = "50") int size) {
        return expenses.obligations(currentUser.require(), "OWED".equalsIgnoreCase(role), size);
    }

    @GetMapping("/expenses/{expenseId}")
    public ExpenseResponse get(@PathVariable String expenseId) {
        return expenses.get(expenseId, currentUser.require());
    }

    @GetMapping("/expenses/{expenseId}/history")
    public List<EventResponse> history(@PathVariable String expenseId) {
        return expenses.history(expenseId, currentUser.require());
    }

    // ---- participant answers (nothing is debited by any of these) ----

    @PostMapping("/shares/{shareId}/accept")
    public ExpenseResponse accept(@PathVariable String shareId, @Valid @RequestBody(required = false) NoteRequest req) {
        return expenses.accept(shareId, currentUser.require(), req == null ? null : req.note());
    }

    @PostMapping("/shares/{shareId}/dispute")
    public ExpenseResponse dispute(@PathVariable String shareId, @Valid @RequestBody(required = false) NoteRequest req) {
        return expenses.dispute(shareId, currentUser.require(), req == null ? null : req.note());
    }

    @PostMapping("/shares/{shareId}/decline")
    public ExpenseResponse decline(@PathVariable String shareId, @Valid @RequestBody(required = false) NoteRequest req) {
        return expenses.decline(shareId, currentUser.require(), req == null ? null : req.note());
    }

    /** The ONLY endpoint that moves a participant's demo funds, and only with confirm=true and the amount they reviewed. */
    @PostMapping("/shares/{shareId}/pay")
    public SettlementResponse pay(@PathVariable String shareId, @Valid @RequestBody ConfirmRequest req) {
        return settlement.pay(currentUser.require(), shareId, req);
    }

    // ---- payer / creator actions ----

    @PostMapping("/shares/{shareId}/reopen")
    public ExpenseResponse reopen(@PathVariable String shareId, @Valid @RequestBody(required = false) NoteRequest req) {
        return expenses.reopen(shareId, currentUser.require(), req == null ? null : req.note());
    }

    @PostMapping("/shares/{shareId}/mark-received")
    public ExpenseResponse markReceived(@PathVariable String shareId) {
        return expenses.markReceivedExternally(shareId, currentUser.require());
    }

    @PostMapping("/shares/{shareId}/retract-received")
    public ExpenseResponse retractReceived(@PathVariable String shareId) {
        return expenses.retractReceivedExternally(shareId, currentUser.require());
    }

    @PostMapping("/shares/{shareId}/refund")
    public SettlementResponse refund(@PathVariable String shareId, @Valid @RequestBody ConfirmRequest req) {
        return settlement.refund(currentUser.require(), shareId, req);
    }

    // ---- operator ----

    @PostMapping("/admin/shares/{shareId}/reverse")
    public SettlementResponse operatorReverse(@PathVariable String shareId, @Valid @RequestBody NoteRequest req) {
        return settlement.operatorReverse(currentUser.requireAdmin(), shareId, req.note());
    }
}
