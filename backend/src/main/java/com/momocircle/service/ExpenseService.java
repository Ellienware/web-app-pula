package com.momocircle.service;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.Circle;
import com.momocircle.domain.CircleMembership;
import com.momocircle.domain.Expense;
import com.momocircle.domain.ExpenseEvent;
import com.momocircle.domain.ExpenseItem;
import com.momocircle.domain.ExpenseShare;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ActivityType;
import com.momocircle.domain.enums.CircleRole;
import com.momocircle.domain.enums.ExpenseStatus;
import com.momocircle.domain.enums.NotificationType;
import com.momocircle.domain.enums.SettlementMethod;
import com.momocircle.domain.enums.ShareStatus;
import com.momocircle.domain.enums.SplitType;
import com.momocircle.domain.ledger.LedgerTransactionEntity;
import com.momocircle.dto.ExpenseDtos.CreateExpenseRequest;
import com.momocircle.dto.ExpenseDtos.EventResponse;
import com.momocircle.dto.ExpenseDtos.ExpenseItemResponse;
import com.momocircle.dto.ExpenseDtos.ExpenseResponse;
import com.momocircle.dto.ExpenseDtos.ExpenseShareResponse;
import com.momocircle.dto.ExpenseDtos.ItemInput;
import com.momocircle.dto.ExpenseDtos.ObligationResponse;
import com.momocircle.dto.ExpenseDtos.PreviewResponse;
import com.momocircle.dto.ExpenseDtos.PreviewShare;
import com.momocircle.dto.ExpenseDtos.ShareInput;
import com.momocircle.dto.ExpenseDtos.UpdateExpenseRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.expense.SplitCalculator;
import com.momocircle.expense.SplitCalculator.SplitException;
import com.momocircle.repository.CircleMembershipRepository;
import com.momocircle.repository.ExpenseEventRepository;
import com.momocircle.repository.ExpenseRepository;
import com.momocircle.repository.ExpenseShareRepository;
import com.momocircle.repository.UserRepository;
import com.momocircle.repository.ledger.LedgerTransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.momocircle.dto.ExpenseDtos.SIMULATED_SETTLEMENT_NOTICE;

/**
 * Split bills and shared expenses (extends the original expense splitting; there is no second expense system).
 *
 * <p>This class owns the RECORD: who owes what, participant consent, edits, cancellation and the audit history. It never moves
 * money and never touches a wallet balance. Simulated settlement is {@link ExpenseSettlementService}, which uses the Phase 9B
 * wallet transfer service and then names the resulting ledger transaction on the share through guarded UPDATEs.
 *
 * <p>Rules enforced here: nobody is debited merely because they were added; a participant can only respond for themselves;
 * allocated shares always add up to the total in exact minor units (see {@link SplitCalculator}); a settled share is never
 * silently rewritten (an edit that would change it is refused until it is explicitly refunded); a cancelled expense never
 * loses its history.
 */
@Service
public class ExpenseService {

    static final int MAX_PARTICIPANTS = 50;
    static final int MAX_ITEMS = 50;
    private static final Set<ShareStatus> OUTSTANDING_FOR_CANCEL = EnumSet.of(
            ShareStatus.PENDING, ShareStatus.ACCEPTED, ShareStatus.DISPUTED, ShareStatus.DECLINED);
    private static final Set<ShareStatus> RESPOND_ACCEPT_FROM = EnumSet.of(ShareStatus.PENDING, ShareStatus.DISPUTED, ShareStatus.DECLINED);
    private static final Set<ShareStatus> RESPOND_DISPUTE_FROM = EnumSet.of(ShareStatus.PENDING, ShareStatus.ACCEPTED, ShareStatus.DECLINED);
    private static final Set<ShareStatus> RESPOND_DECLINE_FROM = EnumSet.of(ShareStatus.PENDING, ShareStatus.ACCEPTED, ShareStatus.DISPUTED);
    private static final Set<ShareStatus> REOPEN_FROM = EnumSet.of(
            ShareStatus.DISPUTED, ShareStatus.DECLINED, ShareStatus.REFUNDED, ShareStatus.REVERSED);
    private static final Set<ShareStatus> RECEIVED_FROM = EnumSet.of(
            ShareStatus.PENDING, ShareStatus.ACCEPTED, ShareStatus.DISPUTED, ShareStatus.DECLINED);

    private final ExpenseRepository expenseRepository;
    private final ExpenseShareRepository shareRepository;
    private final CircleMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final CircleService circleService;
    private final ActivityService activityService;
    private final FinancialValidationService financialValidationService;
    private final ExpenseEventRepository eventRepository;
    private final NotificationService notificationService;
    private final LedgerTransactionRepository ledgerTransactions;
    private final CurrencyProperties currencyProperties;

    public ExpenseService(ExpenseRepository expenseRepository,
                          ExpenseShareRepository shareRepository,
                          CircleMembershipRepository membershipRepository,
                          UserRepository userRepository,
                          CircleService circleService,
                          ActivityService activityService,
                          FinancialValidationService financialValidationService,
                          ExpenseEventRepository eventRepository,
                          NotificationService notificationService,
                          LedgerTransactionRepository ledgerTransactions,
                          CurrencyProperties currencyProperties) {
        this.expenseRepository = expenseRepository;
        this.shareRepository = shareRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
        this.circleService = circleService;
        this.activityService = activityService;
        this.financialValidationService = financialValidationService;
        this.eventRepository = eventRepository;
        this.notificationService = notificationService;
        this.ledgerTransactions = ledgerTransactions;
        this.currencyProperties = currencyProperties;
    }

    // ================================================================== create / preview

    @Transactional
    public ExpenseResponse create(String circleId, User actor, CreateExpenseRequest req) {
        return create(circleId, actor, req, null);
    }

    /**
     * Records an expense and one PENDING share per participant. Nobody is debited. A retried request carrying the same
     * {@code clientRequestKey} returns the original expense instead of creating a second one.
     */
    @Transactional
    public ExpenseResponse create(String circleId, User actor, CreateExpenseRequest req, String clientRequestKey) {
        CircleMembership membership = circleService.requireMembership(circleId, actor);
        Circle circle = membership.getCircle();

        String key = clientRequestKey == null || clientRequestKey.isBlank() ? null : clientRequestKey.trim();
        if (key != null) {
            if (key.length() > 100) throw ApiException.badRequest("Idempotency-Key is too long");
            Expense existing = expenseRepository.findByCreatedByIdAndClientRequestKey(actor.getId(), key).orElse(null);
            if (existing != null) {
                if (!existing.getCircle().getId().equals(circleId)
                        || existing.getAmount().compareTo(req.amount().setScale(2, RoundingMode.HALF_UP)) != 0
                        || !existing.getDescription().equals(req.description().trim())) {
                    throw ApiException.conflict("This Idempotency-Key was already used for a different expense");
                }
                return toResponse(existing, actor);
            }
        }

        Plan plan = plan(circleId, circle, actor, membership, spec(req), null);
        LocalDate date = expenseDate(req.expenseDate());
        LocalDate due = dueDate(req.dueDate(), date);

        Expense expense = new Expense();
        expense.setCircle(circle);
        expense.setPaidBy(plan.payer());
        expense.setCreatedBy(actor);
        expense.setDescription(req.description().trim());
        expense.setNote(clean(req.note()));
        expense.setAmount(BigDecimal.valueOf(plan.totalMinor(), 2));
        expense.setCurrency(circle.getCurrency());
        expense.setSplitType(req.splitType());
        expense.setExpenseDate(date);
        expense.setDueDate(due);
        expense.setClientRequestKey(key);
        applyItems(expense, plan);

        List<ExpenseShare> toNotify = new ArrayList<>();
        for (int i = 0; i < plan.participants().size(); i++) {
            ExpenseShare share = new ExpenseShare();
            share.setExpense(expense);
            share.setDebtor(plan.participants().get(i));
            share.setAmount(BigDecimal.valueOf(plan.minors()[i], 2));
            if (plan.basisPoints() != null) share.setPercentageBp(plan.basisPoints()[i]);
            initialiseStatus(share, plan.payer(), plan.minors()[i], actor);
            expense.getShares().add(share);
            if (share.getStatus() == ShareStatus.PENDING) toNotify.add(share);
        }

        expense = expenseRepository.save(expense);

        recordEvent(expense, null, actor, "EXPENSE_CREATED",
                req.splitType() + " split of " + expense.getAmount().toPlainString() + " " + expense.getCurrency()
                        + " among " + plan.participants().size() + " participant(s)", null);
        for (ExpenseShare s : expense.getShares()) {
            if (s.getStatus() == ShareStatus.PENDING) {
                recordEvent(expense, s, actor, "SHARE_REQUESTED",
                        s.getDebtor().getFullName() + " asked for " + s.getAmount().toPlainString() + " " + expense.getCurrency()
                                + " (nothing is debited until they confirm)", null);
            }
        }
        activityService.record(circle, actor, ActivityType.EXPENSE_ADDED,
                actor.getFullName() + " added expense \"" + expense.getDescription()
                        + "\" for " + expense.getAmount().toPlainString() + " " + circle.getCurrency());
        for (ExpenseShare s : toNotify) {
            notifyUser(actor, s.getDebtor(), NotificationType.EXPENSE_REQUEST,
                    actor.getFullName() + " split \"" + expense.getDescription() + "\" and asked you for "
                            + s.getAmount().toPlainString() + " " + expense.getCurrency()
                            + ". Review it first: nothing is debited until you confirm (simulated demo wallet).", expense.getId());
        }
        return toResponse(expense, actor);
    }

    /** Calculates the shares exactly as {@link #create} would, without saving anything. The single calculation authority. */
    @Transactional(readOnly = true)
    public PreviewResponse preview(String circleId, User actor, CreateExpenseRequest req) {
        CircleMembership membership = circleService.requireMembership(circleId, actor);
        Plan plan = plan(circleId, membership.getCircle(), actor, membership, spec(req), null);
        List<PreviewShare> shares = new ArrayList<>();
        for (int i = 0; i < plan.participants().size(); i++) {
            User u = plan.participants().get(i);
            shares.add(new PreviewShare(u.getId(), u.getFullName(), BigDecimal.valueOf(plan.minors()[i], 2), plan.minors()[i],
                    plan.basisPoints() == null ? null : BigDecimal.valueOf(plan.basisPoints()[i], 2)));
        }
        return new PreviewResponse(BigDecimal.valueOf(plan.totalMinor(), 2), plan.totalMinor(), membership.getCircle().getCurrency(),
                req.splitType(), shares, roundingRule(req.splitType()), true, SIMULATED_SETTLEMENT_NOTICE);
    }

    static String roundingRule(SplitType type) {
        return switch (type) {
            case EQUAL -> "Everyone pays the total divided by the number of people, rounded down; any leftover cents go one each "
                    + "to the first people in the list.";
            case PERCENTAGE -> "Each person pays their percentage of the total, rounded down to the cent; any leftover cents go "
                    + "one each to the largest fractional remainders (ties go to the earlier person in the list).";
            case CUSTOM -> "Amounts are used exactly as entered and must add up to the total. Nothing is rounded.";
            case ITEM_BASED -> "Each item is split equally among the people it is assigned to; leftover cents go one each to the "
                    + "first of those people in the list. Items must add up to the total.";
        };
    }

    // ================================================================== read

    @Transactional(readOnly = true)
    public List<ExpenseResponse> list(String circleId, User viewer) {
        circleService.requireMembership(circleId, viewer);
        Map<String, String> refs = new HashMap<>();
        Set<String> memberIds = memberIds(circleId);
        List<Expense> expenses = expenseRepository.findByCircleIdOrderByCreatedAtDesc(circleId);
        loadReferences(expenses, refs);
        return expenses.stream().map(e -> toResponse(e, viewer, memberIds, refs)).toList();
    }

    /** One expense, for anyone involved in it (creator, payer, participant) or any current member of its circle. */
    @Transactional(readOnly = true)
    public ExpenseResponse get(String expenseId, User viewer) {
        Expense e = requireVisible(expenseId, viewer);
        return toResponse(e, viewer);
    }

    @Transactional(readOnly = true)
    public List<EventResponse> history(String expenseId, User viewer) {
        Expense e = requireVisible(expenseId, viewer);
        Map<String, String> names = new HashMap<>();
        List<EventResponse> out = new ArrayList<>();
        boolean party = isParty(e, viewer);
        for (ExpenseEvent ev : eventRepository.findByExpenseIdOrderByCreatedAtAsc(e.getId())) {
            String actor = ev.getActorUserId() == null ? "PULA" : names.computeIfAbsent(ev.getActorUserId(),
                    id -> userRepository.findById(id).map(User::getFullName).orElse("Former user"));
            out.add(new EventResponse(ev.getId(), ev.getShareId(), actor, ev.getEventType(), ev.getRevision(),
                    ev.getDetails(), party ? ev.getLedgerReference() : null, ev.getCreatedAt()));
        }
        return out;
    }

    /** What the viewer owes ({@code OWE}) or is owed ({@code OWED}) across all of their circles, newest first. */
    @Transactional(readOnly = true)
    public List<ObligationResponse> obligations(User viewer, boolean owed, int size) {
        int safe = Math.max(1, Math.min(size, 100));
        List<ExpenseShare> shares = owed
                ? shareRepository.findOwedTo(viewer.getId(), PageRequest.of(0, safe))
                : shareRepository.findOwedBy(viewer.getId(), PageRequest.of(0, safe));
        Map<String, String> refs = new HashMap<>();
        loadReferences(shares.stream().map(ExpenseShare::getExpense).distinct().toList(), refs);
        Map<String, Boolean> memberCache = new HashMap<>();
        List<ObligationResponse> out = new ArrayList<>();
        for (ExpenseShare s : shares) {
            Expense e = s.getExpense();
            boolean member = memberCache.computeIfAbsent(e.getCircle().getId() + ":" + s.getDebtor().getId(),
                    k -> membershipRepository.existsByCircleIdAndUserId(e.getCircle().getId(), s.getDebtor().getId()));
            ExpenseShareResponse sv = toShareResponse(e, s, viewer, !member, refs);
            out.add(new ObligationResponse(e.getId(), e.getCircle().getId(), e.getCircle().getName(), e.getDescription(),
                    owed ? "OWED" : "OWE", owed ? s.getDebtor().getFullName() : e.getPaidBy().getFullName(), sv, e.getCurrency(),
                    e.getDueDate(), e.getStatus(), e.getCreatedAt()));
        }
        return out;
    }

    // ================================================================== participant consent

    /** The participant agrees they owe the amount. Nothing is debited. */
    @Transactional
    public ExpenseResponse accept(String shareId, User actor, String note) {
        return respond(shareId, actor, ShareStatus.ACCEPTED, RESPOND_ACCEPT_FROM, note, "SHARE_ACCEPTED", "accepted");
    }

    /** The participant disagrees with the amount; the creator is told and must resolve it. */
    @Transactional
    public ExpenseResponse dispute(String shareId, User actor, String note) {
        if (note == null || note.isBlank()) throw ApiException.badRequest("Please say what looks wrong so the creator can fix it");
        return respond(shareId, actor, ShareStatus.DISPUTED, RESPOND_DISPUTE_FROM, note, "SHARE_DISPUTED", "disputed");
    }

    @Transactional
    public ExpenseResponse decline(String shareId, User actor, String note) {
        return respond(shareId, actor, ShareStatus.DECLINED, RESPOND_DECLINE_FROM, note, "SHARE_DECLINED", "declined");
    }

    private ExpenseResponse respond(String shareId, User actor, ShareStatus target, Set<ShareStatus> from, String note,
                                    String eventType, String verb) {
        ExpenseShare share = requireShare(shareId);
        Expense expense = share.getExpense();
        requireDebtor(share, expense, actor);
        requireActive(expense);
        int changed = shareRepository.respond(shareId, actor.getId(), from, target, clean(note), Instant.now());
        if (changed == 0) {
            ExpenseShare now = requireShare(shareId);
            if (now.getStatus() == target) return toResponse(now.getExpense(), actor); // repeating the same answer is harmless
            throw ApiException.conflict("This share can no longer be " + verb + " (it is " + now.getStatus().name().toLowerCase() + ")");
        }
        ExpenseShare after = requireShare(shareId);
        recordEvent(expense, after, actor, eventType, actor.getFullName() + " " + verb + " their share of "
                + after.getAmount().toPlainString() + " " + expense.getCurrency() + (clean(note) == null ? "" : ": " + clean(note)), null);
        if (target != ShareStatus.ACCEPTED) {
            notifyUser(actor, expense.getPaidBy(), NotificationType.EXPENSE_UPDATE,
                    actor.getFullName() + " " + verb + " their share of \"" + expense.getDescription() + "\""
                            + (clean(note) == null ? "." : ": " + clean(note)), expense.getId());
            if (!expense.getCreatedBy().getId().equals(expense.getPaidBy().getId())) {
                notifyUser(actor, expense.getCreatedBy(), NotificationType.EXPENSE_UPDATE,
                        actor.getFullName() + " " + verb + " their share of \"" + expense.getDescription() + "\".", expense.getId());
            }
        }
        return toResponse(expense, actor);
    }

    /** The creator or payer stands by the amount and asks again (disputed/declined), or re-opens a refunded/reversed share. */
    @Transactional
    public ExpenseResponse reopen(String shareId, User actor, String note) {
        ExpenseShare share = requireShare(shareId);
        Expense expense = share.getExpense();
        requireManager(expense, actor);
        requireActive(expense);
        int changed = shareRepository.reopen(shareId, REOPEN_FROM, ShareStatus.PENDING, clean(note), Instant.now());
        if (changed == 0) {
            ExpenseShare now = requireShare(shareId);
            if (now.getStatus() == ShareStatus.PENDING) return toResponse(expense, actor);
            throw ApiException.conflict("This share cannot be re-opened (it is " + now.getStatus().name().toLowerCase() + ")");
        }
        ExpenseShare after = requireShare(shareId);
        recordEvent(expense, after, actor, "SHARE_REOPENED", actor.getFullName() + " asked "
                + after.getDebtor().getFullName() + " again for " + after.getAmount().toPlainString() + " " + expense.getCurrency()
                + (clean(note) == null ? "" : ": " + clean(note)), null);
        notifyUser(actor, after.getDebtor(), NotificationType.EXPENSE_REQUEST,
                actor.getFullName() + " asked you again for " + after.getAmount().toPlainString() + " " + expense.getCurrency()
                        + " for \"" + expense.getDescription() + "\". Review it: nothing is debited until you confirm (simulated demo wallet).",
                expense.getId());
        return toResponse(expense, actor);
    }

    // ================================================================== settled outside PULA

    /**
     * The person who paid confirms they were repaid OUTSIDE PULA (cash, bank transfer, anything else). This replaces the old
     * rule that let the debtor or any admin mark a share paid. It moves nothing, PULA cannot verify it, and it is recorded
     * as {@code EXTERNAL}, never as a wallet settlement.
     */
    @Transactional
    public ExpenseResponse markReceivedExternally(String shareId, User actor) {
        ExpenseShare share = requireShare(shareId);
        Expense expense = share.getExpense();
        requireActive(expense);
        if (!expense.getPaidBy().getId().equals(actor.getId())) {
            throw ApiException.forbidden("Only the person who paid can confirm they were repaid outside PULA");
        }
        if (share.getDebtor().getId().equals(actor.getId())) throw ApiException.badRequest("You cannot mark your own share as repaid");
        int changed = shareRepository.markPaidExternally(shareId, actor.getId(), RECEIVED_FROM, SettlementMethod.EXTERNAL,
                ShareStatus.PAID, Instant.now());
        if (changed == 0) {
            ExpenseShare now = requireShare(shareId);
            if (now.getStatus() == ShareStatus.PAID && now.getSettlementMethod() == SettlementMethod.EXTERNAL) return toResponse(expense, actor);
            throw ApiException.conflict("This share cannot be marked as repaid (it is " + now.getStatus().name().toLowerCase() + ")");
        }
        ExpenseShare after = requireShare(shareId);
        recordEvent(expense, after, actor, "SHARE_RECEIVED_EXTERNAL", actor.getFullName() + " confirmed "
                + after.getDebtor().getFullName() + " repaid " + after.getAmount().toPlainString() + " " + expense.getCurrency()
                + " outside PULA (no demo-wallet transfer; not verified by PULA)", null);
        activityService.record(expense.getCircle(), actor, ActivityType.EXPENSE_SHARE_SETTLED,
                after.getDebtor().getFullName() + " repaid " + after.getAmount().toPlainString() + " " + expense.getCurrency()
                        + " for \"" + expense.getDescription() + "\" (recorded as paid outside PULA)");
        notifyUser(actor, after.getDebtor(), NotificationType.EXPENSE_UPDATE,
                actor.getFullName() + " recorded that you repaid " + after.getAmount().toPlainString() + " " + expense.getCurrency()
                        + " for \"" + expense.getDescription() + "\" outside PULA.", expense.getId());
        return toResponse(expense, actor);
    }

    /** The payer takes back a "repaid outside PULA" record that was wrong. Nothing moves. */
    @Transactional
    public ExpenseResponse retractReceivedExternally(String shareId, User actor) {
        ExpenseShare share = requireShare(shareId);
        Expense expense = share.getExpense();
        requireActive(expense);
        if (!expense.getPaidBy().getId().equals(actor.getId())) {
            throw ApiException.forbidden("Only the person who paid can retract a repaid record");
        }
        int changed = shareRepository.retractExternal(shareId, ShareStatus.PAID,
                EnumSet.of(SettlementMethod.EXTERNAL, SettlementMethod.LEGACY), ShareStatus.PENDING, Instant.now());
        if (changed == 0) {
            ExpenseShare now = requireShare(shareId);
            if (now.getStatus() == ShareStatus.PENDING) return toResponse(expense, actor);
            throw ApiException.conflict("Only a share recorded as repaid outside PULA can be retracted");
        }
        ExpenseShare after = requireShare(shareId);
        recordEvent(expense, after, actor, "SHARE_RECEIPT_RETRACTED", actor.getFullName()
                + " retracted the \"repaid outside PULA\" record for " + after.getDebtor().getFullName(), null);
        notifyUser(actor, after.getDebtor(), NotificationType.EXPENSE_REQUEST,
                actor.getFullName() + " retracted the repaid record for \"" + expense.getDescription() + "\"; "
                        + after.getAmount().toPlainString() + " " + expense.getCurrency() + " is owed again.", expense.getId());
        return toResponse(expense, actor);
    }

    // ================================================================== edit

    /**
     * Edits an expense. The creator only. Rules:
     * <ul>
     *   <li>a stale edit (wrong {@code expectedRevision}) or one made while a payment is processing is refused;</li>
     *   <li>a share that is already settled (wallet, outside PULA, or legacy) is NEVER rewritten: if the edit would change its
     *       amount or remove it, the whole edit is refused and the payer must first refund or retract that settlement;</li>
     *   <li>any other share whose amount changes goes back to PENDING and the participant is told: a previously accepted amount
     *       needs fresh acknowledgement;</li>
     *   <li>removed participants keep their share row as CANCELLED so the history stays complete.</li>
     * </ul>
     */
    @Transactional
    public ExpenseResponse update(String circleId, String expenseId, User actor, UpdateExpenseRequest req) {
        CircleMembership membership = circleService.requireMembership(circleId, actor);
        Expense expense = requireInCircle(circleId, expenseId);
        if (!expense.getCreatedBy().getId().equals(actor.getId())) {
            throw ApiException.forbidden("Only the person who recorded this expense can edit it");
        }
        requireActive(expense);
        if (req.expectedRevision() == null || req.expectedRevision() != expense.getRevision()) {
            throw ApiException.conflict("This expense was changed by someone else. Reload it and try again.");
        }
        List<ExpenseShare> locked = shareRepository.lockByExpense(expenseId); // row locks: no payment claim can interleave
        for (ExpenseShare s : locked) {
            if (s.getStatus() == ShareStatus.PROCESSING) {
                throw ApiException.conflict("A payment for this expense is being processed. Try again in a moment.");
            }
        }
        Plan plan = plan(circleId, expense.getCircle(), actor, membership, spec(req), expense);
        Map<String, ExpenseShare> byDebtor = new LinkedHashMap<>();
        for (ExpenseShare s : locked) byDebtor.put(s.getDebtor().getId(), s);
        Set<String> newIds = new LinkedHashSet<>();
        for (User u : plan.participants()) newIds.add(u.getId());

        boolean payerChanged = !expense.getPaidBy().getId().equals(plan.payer().getId());
        for (ExpenseShare s : locked) {
            boolean settledByValue = isSettledByValue(s);
            if (payerChanged && settledByValue) {
                throw ApiException.conflict("The payer cannot be changed while shares are settled. Refund or retract those first.");
            }
            long newMinor = newIds.contains(s.getDebtor().getId()) ? plan.minors()[indexOf(plan, s.getDebtor().getId())] : -1;
            if (settledByValue && newMinor != minor(s.getAmount())) {
                throw ApiException.conflict(s.getDebtor().getFullName() + "'s share is already settled ("
                        + s.getAmount().toPlainString() + " " + expense.getCurrency() + "). It cannot be changed or removed by "
                        + "an edit. Refund or retract that settlement first, then edit.");
            }
        }

        StringBuilder diff = new StringBuilder();
        diff.append(change("title", expense.getDescription(), req.description().trim()));
        diff.append(change("total", expense.getAmount().toPlainString(), BigDecimal.valueOf(plan.totalMinor(), 2).toPlainString()));
        diff.append(change("split", expense.getSplitType().name(), req.splitType().name()));
        if (payerChanged) diff.append(change("paid by", expense.getPaidBy().getFullName(), plan.payer().getFullName()));
        LocalDate date = req.expenseDate() == null ? expense.getExpenseDate() : expenseDate(req.expenseDate());
        LocalDate due = dueDate(req.dueDate(), date);
        diff.append(change("due date", String.valueOf(expense.getDueDate()), String.valueOf(due)));

        expense.setDescription(req.description().trim());
        expense.setNote(clean(req.note()));
        expense.setAmount(BigDecimal.valueOf(plan.totalMinor(), 2));
        expense.setSplitType(req.splitType());
        expense.setExpenseDate(date);
        expense.setDueDate(due);
        expense.setPaidBy(plan.payer());
        expense.setRevision(expense.getRevision() + 1);
        expense.getItems().clear();
        applyItems(expense, plan);

        List<ExpenseShare> toNotify = new ArrayList<>();
        List<ExpenseShare> removed = new ArrayList<>();
        for (int i = 0; i < plan.participants().size(); i++) {
            User u = plan.participants().get(i);
            long newMinor = plan.minors()[i];
            ExpenseShare s = byDebtor.get(u.getId());
            if (s == null) {
                s = new ExpenseShare();
                s.setExpense(expense);
                s.setDebtor(u);
                s.setAmount(BigDecimal.valueOf(newMinor, 2));
                if (plan.basisPoints() != null) s.setPercentageBp(plan.basisPoints()[i]);
                initialiseStatus(s, plan.payer(), newMinor, actor);
                expense.getShares().add(s);
                if (s.getStatus() == ShareStatus.PENDING) toNotify.add(s);
                continue;
            }
            s.setPercentageBp(plan.basisPoints() == null ? null : plan.basisPoints()[i]);
            if (isSettledByValue(s)) continue; // amount is equal (checked above): left untouched
            long oldMinor = minor(s.getAmount());
            boolean wasRemoved = s.getStatus() == ShareStatus.CANCELLED;
            if (!wasRemoved && oldMinor == newMinor && !payerChanged) continue; // unchanged: keeps whatever response it had
            s.setAmount(BigDecimal.valueOf(newMinor, 2));
            reset(s, plan.payer(), newMinor, actor);
            if (s.getStatus() == ShareStatus.PENDING) toNotify.add(s);
        }
        for (ExpenseShare s : locked) {
            if (!newIds.contains(s.getDebtor().getId()) && s.getStatus() != ShareStatus.CANCELLED) {
                s.setStatus(ShareStatus.CANCELLED);
                s.setSettled(false);
                removed.add(s);
            }
        }

        expense = expenseRepository.save(expense);
        recordEvent(expense, null, actor, "EXPENSE_EDITED", "Revision " + expense.getRevision() + ". "
                + (diff.length() == 0 ? "Details updated." : diff.toString().trim()), null);
        for (ExpenseShare s : toNotify) {
            recordEvent(expense, s, actor, "SHARE_CHANGED", s.getDebtor().getFullName() + " must review "
                    + s.getAmount().toPlainString() + " " + expense.getCurrency() + " (acknowledgement needed again)", null);
            notifyUser(actor, s.getDebtor(), NotificationType.EXPENSE_REQUEST,
                    actor.getFullName() + " changed \"" + expense.getDescription() + "\". Your share is now "
                            + s.getAmount().toPlainString() + " " + expense.getCurrency()
                            + ". Please review it again: nothing is debited until you confirm (simulated demo wallet).", expense.getId());
        }
        for (ExpenseShare s : removed) {
            recordEvent(expense, s, actor, "SHARE_REMOVED", s.getDebtor().getFullName() + " was removed from this expense", null);
            notifyUser(actor, s.getDebtor(), NotificationType.EXPENSE_UPDATE,
                    actor.getFullName() + " removed you from \"" + expense.getDescription() + "\". You owe nothing for it.", expense.getId());
        }
        activityService.record(expense.getCircle(), actor, ActivityType.EXPENSE_EDITED,
                actor.getFullName() + " edited expense \"" + expense.getDescription() + "\"");
        return toResponse(expense, actor);
    }

    private static boolean isSettledByValue(ExpenseShare s) {
        return s.getStatus() == ShareStatus.PAID && s.getSettlementMethod() != SettlementMethod.SELF
                && s.getSettlementMethod() != SettlementMethod.NONE;
    }

    // ================================================================== cancel

    /**
     * Cancels an expense. The creator, or a circle owner/admin. Refused while a payment is processing and while ANY share is
     * settled (wallet, outside PULA or legacy): the defined resolution path is to refund each wallet settlement (the payer
     * confirms a compensating transfer) or retract each "repaid outside PULA" record first. Cancelling never deletes history.
     */
    @Transactional
    public ExpenseResponse cancel(String circleId, String expenseId, User actor, String reason) {
        CircleMembership membership = circleService.requireMembership(circleId, actor);
        Expense expense = requireInCircle(circleId, expenseId);
        boolean creator = expense.getCreatedBy().getId().equals(actor.getId());
        boolean admin = membership.getRole() == CircleRole.OWNER || membership.getRole() == CircleRole.ADMIN;
        if (!creator && !admin) throw ApiException.forbidden("Only the person who recorded this expense, or a circle admin, can cancel it");
        if (expense.isCancelled()) return toResponse(expense, actor);
        List<ExpenseShare> locked = shareRepository.lockByExpense(expenseId);
        String blocked = cancelBlockedReason(locked);
        if (blocked != null) throw ApiException.conflict(blocked);
        Instant now = Instant.now();
        shareRepository.cancelOutstanding(expenseId, OUTSTANDING_FOR_CANCEL, ShareStatus.CANCELLED, now);
        Expense fresh = requireInCircle(circleId, expenseId);
        fresh.setStatus(ExpenseStatus.CANCELLED);
        fresh.setCancelledAt(now);
        fresh.setCancelledBy(actor);
        fresh.setCancelReason(clean(reason));
        fresh.setRevision(fresh.getRevision() + 1);
        fresh = expenseRepository.save(fresh);
        recordEvent(fresh, null, actor, "EXPENSE_CANCELLED", actor.getFullName() + " cancelled the expense"
                + (clean(reason) == null ? "" : ": " + clean(reason)), null);
        activityService.record(fresh.getCircle(), actor, ActivityType.EXPENSE_CANCELLED,
                actor.getFullName() + " cancelled expense \"" + fresh.getDescription() + "\"");
        for (ExpenseShare s : fresh.getShares()) {
            if (s.getStatus() == ShareStatus.CANCELLED) {
                notifyUser(actor, s.getDebtor(), NotificationType.EXPENSE_UPDATE,
                        "\"" + fresh.getDescription() + "\" was cancelled. You owe nothing for it.", fresh.getId());
            }
        }
        return toResponse(fresh, actor);
    }

    static String cancelBlockedReason(List<ExpenseShare> shares) {
        boolean ledger = false;
        boolean external = false;
        for (ExpenseShare s : shares) {
            if (s.getStatus() == ShareStatus.PROCESSING) return "A payment for this expense is being processed. Try again in a moment.";
            if (s.getStatus() == ShareStatus.PAID) {
                if (s.getSettlementMethod() == SettlementMethod.LEDGER) ledger = true;
                else if (s.getSettlementMethod() == SettlementMethod.EXTERNAL || s.getSettlementMethod() == SettlementMethod.LEGACY) external = true;
            }
        }
        if (ledger) {
            return "Some participants have already paid this through the demo wallet. Return each payment first (the payer confirms a "
                    + "refund transfer), then cancel. PULA never recreates funds that were already spent.";
        }
        if (external) {
            return "Some shares are recorded as repaid outside PULA. The payer must retract those records first, then cancel.";
        }
        return null;
    }

    // ================================================================== used by the settlement service

    /** Plain data about one share, safe to use outside a transaction. */
    public record ShareSnapshot(String shareId, String expenseId, String circleId, String title, String currency, BigDecimal amount,
                                String debtorId, String debtorName, String payerId, String payerName, boolean payerActive,
                                boolean debtorActive, ShareStatus status, SettlementMethod method, int settleRound,
                                String claimUserId, String settledById, String ledgerTransactionId, String refundTransactionId,
                                ExpenseStatus expenseStatus) {}

    /** The share as the actor may see it: anyone who cannot see the expense is told it does not exist. */
    @Transactional(readOnly = true)
    public ShareSnapshot snapshot(String shareId, User actor) {
        ExpenseShare s = requireShare(shareId);
        Expense e = s.getExpense();
        if (!canView(e, actor)) throw ApiException.notFound("Expense share not found");
        return snapshotOf(s, e);
    }

    /** Snapshot without an actor, for the recovery job. */
    @Transactional(readOnly = true)
    public ShareSnapshot snapshotForRecovery(String shareId) {
        ExpenseShare s = requireShare(shareId);
        return snapshotOf(s, s.getExpense());
    }

    private static ShareSnapshot snapshotOf(ExpenseShare s, Expense e) {
        return new ShareSnapshot(s.getId(), e.getId(), e.getCircle().getId(), e.getDescription(), e.getCurrency(), s.getAmount(),
                s.getDebtor().getId(), s.getDebtor().getFullName(), e.getPaidBy().getId(), e.getPaidBy().getFullName(),
                e.getPaidBy().isActive(), s.getDebtor().isActive(), s.getStatus(), s.getSettlementMethod(), s.getSettleRound(),
                s.getClaimUserId(), s.getSettledById(), s.getLedgerTransactionId(), s.getRefundTransactionId(), e.getStatus());
    }

    @Transactional(readOnly = true)
    public ExpenseResponse viewFor(String expenseId, User viewer) {
        return get(expenseId, viewer);
    }

    @Transactional(readOnly = true)
    public ExpenseShareResponse shareViewFor(String shareId, User viewer) {
        ExpenseShare s = requireShare(shareId);
        Map<String, String> refs = new HashMap<>();
        loadReferences(List.of(s.getExpense()), refs);
        boolean former = !membershipRepository.existsByCircleIdAndUserId(s.getExpense().getCircle().getId(), s.getDebtor().getId());
        return toShareResponse(s.getExpense(), s, viewer, former, refs);
    }

    @Transactional
    public void onSettled(String shareId, User actor, String ledgerReference, boolean replayed) {
        if (replayed) return; // the first call already wrote the history, activity and notification
        ExpenseShare s = requireShare(shareId);
        Expense e = s.getExpense();
        recordEvent(e, s, actor, "SHARE_PAID_LEDGER", actor.getFullName() + " confirmed and paid " + s.getAmount().toPlainString()
                + " " + e.getCurrency() + " from their demo wallet (simulated transfer)", ledgerReference);
        activityService.record(e.getCircle(), actor, ActivityType.EXPENSE_SHARE_SETTLED,
                actor.getFullName() + " settled " + s.getAmount().toPlainString() + " " + e.getCurrency() + " for \""
                        + e.getDescription() + "\" with a simulated demo-wallet transfer");
        notifyUser(actor, e.getPaidBy(), NotificationType.PAYMENT_SUCCESS,
                actor.getFullName() + " paid you " + s.getAmount().toPlainString() + " " + e.getCurrency() + " for \""
                        + e.getDescription() + "\" (simulated demo wallet; reference " + ledgerReference + ").", e.getId());
    }

    @Transactional
    public void onRefunded(String shareId, User actor, String ledgerReference, boolean operator, String reason) {
        ExpenseShare s = requireShare(shareId);
        Expense e = s.getExpense();
        String how = operator ? "An operator reversed" : actor.getFullName() + " refunded";
        recordEvent(e, s, actor, operator ? "SHARE_REVERSED_LEDGER" : "SHARE_REFUNDED_LEDGER", how + " the simulated "
                + s.getAmount().toPlainString() + " " + e.getCurrency() + " settlement for " + s.getDebtor().getFullName()
                + (reason == null || reason.isBlank() ? "" : " (" + reason + ")"), ledgerReference);
        activityService.record(e.getCircle(), actor, ActivityType.EXPENSE_SHARE_REFUNDED,
                how + " " + s.getAmount().toPlainString() + " " + e.getCurrency() + " for \"" + e.getDescription() + "\" (simulated)");
        notifyUser(actor, s.getDebtor(), NotificationType.REFUND,
                s.getAmount().toPlainString() + " " + e.getCurrency() + " was returned to your demo wallet for \""
                        + e.getDescription() + "\" (simulated; reference " + ledgerReference + ").", e.getId());
    }

    /** Recorded when the ledger refused a payment so the history shows the attempt without any funds moving. */
    @Transactional
    public void onPaymentRefused(String shareId, User actor, String code) {
        ExpenseShare s = requireShare(shareId);
        recordEvent(s.getExpense(), s, actor, "SHARE_PAYMENT_REFUSED", "A simulated payment was refused (" + code
                + "). Nothing was debited.", null);
    }

    // ================================================================== planning

    private record Spec(BigDecimal amount, String currency, SplitType splitType, String paidByUserId,
                        List<ShareInput> participants, List<ItemInput> items) {}

    private static Spec spec(CreateExpenseRequest r) {
        return new Spec(r.amount(), r.currency(), r.splitType(), r.paidByUserId(), r.participants(), r.items());
    }

    private static Spec spec(UpdateExpenseRequest r) {
        return new Spec(r.amount(), r.currency(), r.splitType(), r.paidByUserId(), r.participants(), r.items());
    }

    private record ItemPlan(String name, long amountMinor, List<String> assigneeIds) {}

    private record Plan(User payer, List<User> participants, long[] minors, int[] basisPoints, List<ItemPlan> items, long totalMinor) {}

    private Plan plan(String circleId, Circle circle, User actor, CircleMembership actorMembership, Spec spec, Expense existing) {
        financialValidationService.requireValidAmount(spec.amount(), "amount");
        if (spec.currency() != null && !spec.currency().isBlank() && !spec.currency().trim().equalsIgnoreCase(circle.getCurrency())) {
            throw ApiException.badRequest("This circle uses " + circle.getCurrency() + "; expenses must use the same currency");
        }
        long total = minor(spec.amount());
        if (total <= 0) throw ApiException.badRequest("Expense amount must be positive");

        // The payer: the caller, unless a circle owner/admin records it on behalf of another member.
        User payer = actor;
        String requestedPayer = spec.paidByUserId();
        if (requestedPayer != null && !requestedPayer.isBlank() && !requestedPayer.equals(actor.getId())) {
            boolean admin = actorMembership.getRole() == CircleRole.OWNER || actorMembership.getRole() == CircleRole.ADMIN;
            if (!admin) {
                throw ApiException.forbidden("Only the person who paid, or a circle admin, can record this expense");
            }
            payer = requireMember(circleId, requestedPayer);
        } else if (existing != null && (requestedPayer == null || requestedPayer.isBlank())) {
            payer = existing.getPaidBy();
        }

        // De-duplicate participants (last entry wins) while preserving order; each must be a member of this circle.
        Map<String, ShareInput> requested = new LinkedHashMap<>();
        for (ShareInput input : spec.participants()) requested.put(input.userId(), input);
        if (requested.isEmpty()) throw ApiException.badRequest("At least one participant is required");
        if (requested.size() > MAX_PARTICIPANTS) throw ApiException.badRequest("An expense can have at most " + MAX_PARTICIPANTS + " participants");
        List<String> ids = new ArrayList<>(requested.keySet());
        List<User> participants = new ArrayList<>();
        for (String id : ids) participants.add(requireMember(circleId, id));

        long[] minors;
        int[] bps = null;
        List<ItemPlan> items = null;
        try {
            switch (spec.splitType()) {
                case EQUAL -> minors = SplitCalculator.equal(total, ids.size());
                case CUSTOM -> {
                    long[] given = new long[ids.size()];
                    for (int i = 0; i < ids.size(); i++) {
                        BigDecimal a = requested.get(ids.get(i)).amount();
                        if (a == null) throw ApiException.badRequest("A custom amount is required for every participant");
                        financialValidationService.requireValidNonNegativeAmount(a, "share amount");
                        given[i] = minor(a);
                    }
                    minors = SplitCalculator.custom(total, given);
                }
                case PERCENTAGE -> {
                    bps = new int[ids.size()];
                    for (int i = 0; i < ids.size(); i++) {
                        BigDecimal p = requested.get(ids.get(i)).percentage();
                        if (p == null) throw ApiException.badRequest("A percentage is required for every participant");
                        if (p.stripTrailingZeros().scale() > 2) {
                            throw ApiException.badRequest("Percentages cannot have more than 2 decimal places");
                        }
                        if (p.compareTo(new BigDecimal("100")) > 0) throw ApiException.badRequest("A percentage cannot exceed 100");
                        bps[i] = p.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).intValueExact();
                    }
                    minors = SplitCalculator.percentage(total, bps);
                }
                case ITEM_BASED -> {
                    if (spec.items() == null || spec.items().isEmpty()) throw ApiException.badRequest("At least one item is required");
                    if (spec.items().size() > MAX_ITEMS) throw ApiException.badRequest("An expense can have at most " + MAX_ITEMS + " items");
                    items = new ArrayList<>();
                    List<SplitCalculator.Item> calc = new ArrayList<>();
                    for (ItemInput in : spec.items()) {
                        financialValidationService.requireValidAmount(in.amount(), "item amount");
                        long m = minor(in.amount());
                        List<String> assignees = new ArrayList<>(new LinkedHashSet<>(in.assigneeUserIds()));
                        items.add(new ItemPlan(in.name().trim(), m, assignees));
                        calc.add(new SplitCalculator.Item(in.name().trim(), m, assignees));
                    }
                    minors = SplitCalculator.items(total, calc, ids);
                }
                default -> throw ApiException.badRequest("Unsupported split type");
            }
        } catch (SplitException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (ArithmeticException e) {
            throw ApiException.badRequest("An amount has more precision than the currency allows");
        }
        if (SplitCalculator.sum(minors) != total) {
            throw new IllegalStateException("Split does not add up"); // defensive: the calculator already guarantees this
        }
        return new Plan(payer, participants, minors, bps, items, total);
    }

    private void applyItems(Expense expense, Plan plan) {
        if (plan.items() == null) return;
        int pos = 0;
        for (ItemPlan ip : plan.items()) {
            ExpenseItem item = new ExpenseItem();
            item.setExpense(expense);
            item.setPosition(pos++);
            item.setName(ip.name());
            item.setAmountMinor(ip.amountMinor());
            item.setAssigneeIds(new LinkedHashSet<>(ip.assigneeIds()));
            expense.getItems().add(item);
        }
    }

    /** New share: the payer's own portion and zero shares need nothing; everyone else is asked and must respond. */
    private void initialiseStatus(ExpenseShare share, User payer, long minor, User actor) {
        if (share.getDebtor().getId().equals(payer.getId())) {
            share.setStatus(ShareStatus.PAID);
            share.setSettlementMethod(SettlementMethod.SELF);
            share.setSettled(true);
            share.setSettledAt(Instant.now());
            share.setSettledById(actor.getId());
        } else if (minor == 0) {
            share.setStatus(ShareStatus.PAID);
            share.setSettlementMethod(SettlementMethod.NONE);
            share.setSettled(true);
            share.setSettledAt(Instant.now());
            share.setSettledById(actor.getId());
        } else {
            share.setStatus(ShareStatus.PENDING);
        }
    }

    private void reset(ExpenseShare share, User payer, long minor, User actor) {
        share.setSettled(false);
        share.setSettledAt(null);
        share.setSettlementMethod(null);
        share.setSettledById(null);
        share.setClaimUserId(null);
        share.setRespondedAt(null);
        share.setResponseNote(null);
        initialiseStatus(share, payer, minor, actor);
    }

    private static int indexOf(Plan plan, String userId) {
        for (int i = 0; i < plan.participants().size(); i++) if (plan.participants().get(i).getId().equals(userId)) return i;
        return -1;
    }

    // ================================================================== access helpers

    private User requireMember(String circleId, String userId) {
        if (!membershipRepository.existsByCircleIdAndUserId(circleId, userId)) {
            throw ApiException.badRequest("Participant is not a member of this circle");
        }
        return userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("User not found"));
    }

    private Set<String> memberIds(String circleId) {
        Set<String> ids = new HashSet<>();
        for (CircleMembership m : membershipRepository.findByCircleId(circleId)) ids.add(m.getUser().getId());
        return ids;
    }

    private ExpenseShare requireShare(String shareId) {
        return shareRepository.findById(shareId == null ? "" : shareId)
                .orElseThrow(() -> ApiException.notFound("Expense share not found"));
    }

    private Expense requireInCircle(String circleId, String expenseId) {
        Expense expense = expenseRepository.findById(expenseId == null ? "" : expenseId)
                .orElseThrow(() -> ApiException.notFound("Expense not found"));
        if (!expense.getCircle().getId().equals(circleId)) throw ApiException.badRequest("Expense does not belong to this circle");
        return expense;
    }

    private Expense requireVisible(String expenseId, User viewer) {
        Expense e = expenseRepository.findById(expenseId == null ? "" : expenseId)
                .orElseThrow(() -> ApiException.notFound("Expense not found"));
        if (!canView(e, viewer)) throw ApiException.notFound("Expense not found"); // never reveal that it exists
        return e;
    }

    /** Involved people (creator, payer, any participant, including former members) or any current member of the circle. */
    boolean canView(Expense e, User viewer) {
        if (isParty(e, viewer)) return true;
        return membershipRepository.existsByCircleIdAndUserId(e.getCircle().getId(), viewer.getId());
    }

    private static boolean isParty(Expense e, User viewer) {
        return e.getPaidBy().getId().equals(viewer.getId()) || e.getCreatedBy().getId().equals(viewer.getId())
                || e.getShares().stream().anyMatch(s -> s.getDebtor().getId().equals(viewer.getId()));
    }

    /** Only the participant themselves may respond for or pay their share; others who can see it get 403, outsiders 404. */
    private void requireDebtor(ExpenseShare share, Expense expense, User actor) {
        if (share.getDebtor().getId().equals(actor.getId())) return;
        if (canView(expense, actor)) throw ApiException.forbidden("Only the participant can respond to their own share");
        throw ApiException.notFound("Expense share not found");
    }

    /** Creator or payer of the expense (the people who may ask again). */
    private void requireManager(Expense expense, User actor) {
        boolean ok = expense.getPaidBy().getId().equals(actor.getId()) || expense.getCreatedBy().getId().equals(actor.getId());
        if (ok) return;
        if (canView(expense, actor)) throw ApiException.forbidden("Only the person who recorded or paid for this expense can do that");
        throw ApiException.notFound("Expense share not found");
    }

    private static void requireActive(Expense expense) {
        if (expense.isCancelled()) throw ApiException.conflict("This expense was cancelled");
    }

    // ================================================================== small helpers

    private LocalDate expenseDate(LocalDate requested) {
        LocalDate today = LocalDate.now();
        if (requested == null) return today;
        if (requested.isAfter(today)) throw ApiException.badRequest("The expense date cannot be in the future");
        return requested;
    }

    private static LocalDate dueDate(LocalDate due, LocalDate expenseDate) {
        if (due != null && due.isBefore(expenseDate)) throw ApiException.badRequest("The due date cannot be before the expense date");
        return due;
    }

    /** Recorded amounts are held in hundredths; a value with more than two decimals is refused, never rounded. */
    static long minor(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).unscaledValue().longValueExact();
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? null : t;
    }

    private static String change(String what, String before, String after) {
        return before.equals(after) ? "" : what + ": " + before + " -> " + after + ". ";
    }

    private void recordEvent(Expense e, ExpenseShare share, User actor, String type, String details, String ledgerReference) {
        eventRepository.save(ExpenseEvent.of(e.getId(), share == null ? null : share.getId(), actor == null ? null : actor.getId(),
                type, e.getRevision(), details, ledgerReference));
    }

    private void notifyUser(User actor, User to, NotificationType type, String message, String ref) {
        if (to == null || (actor != null && to.getId().equals(actor.getId()))) return;
        try {
            notificationService.notify(to, type, message, ref);
        } catch (RuntimeException ignored) {
            // a notification must never undo the expense action that triggered it
        }
    }

    // ================================================================== views

    private void loadReferences(List<Expense> expenses, Map<String, String> refsById) {
        Set<String> ids = new HashSet<>();
        for (Expense e : expenses) {
            for (ExpenseShare s : e.getShares()) {
                if (s.getLedgerTransactionId() != null) ids.add(s.getLedgerTransactionId());
                if (s.getRefundTransactionId() != null) ids.add(s.getRefundTransactionId());
            }
        }
        if (ids.isEmpty()) return;
        for (LedgerTransactionEntity t : ledgerTransactions.findAllById(ids)) refsById.put(t.getId(), t.getProviderReference());
    }

    private ExpenseResponse toResponse(Expense e, User viewer) {
        Map<String, String> refs = new HashMap<>();
        loadReferences(List.of(e), refs);
        return toResponse(e, viewer, memberIds(e.getCircle().getId()), refs);
    }

    private ExpenseResponse toResponse(Expense e, User viewer, Set<String> memberIds, Map<String, String> refs) {
        List<ExpenseShareResponse> shares = new ArrayList<>();
        long settled = 0;
        long outstanding = 0;
        long transferred = 0;
        long external = 0;
        long refunded = 0;
        boolean anyDispute = false;
        boolean anyOverdue = false;
        boolean anyPaidByValue = false;
        boolean anyUnpaid = false;
        for (ExpenseShare s : e.getShares()) {
            long m = minor(s.getAmount());
            boolean former = !memberIds.contains(s.getDebtor().getId());
            ExpenseShareResponse r = toShareResponse(e, s, viewer, former, refs);
            shares.add(r);
            switch (s.getStatus()) {
                case PAID -> {
                    settled += m;
                    if (s.getSettlementMethod() == SettlementMethod.LEDGER) {
                        transferred += m;
                        anyPaidByValue = true;
                    } else if (s.getSettlementMethod() == SettlementMethod.EXTERNAL || s.getSettlementMethod() == SettlementMethod.LEGACY) {
                        external += m;
                        anyPaidByValue = true;
                    }
                }
                case REFUNDED, REVERSED -> {
                    refunded += m;
                    outstanding += m;
                    anyUnpaid = true;
                }
                case CANCELLED -> { }
                default -> {
                    outstanding += m;
                    anyUnpaid = true;
                }
            }
            if (s.getStatus() == ShareStatus.DISPUTED) anyDispute = true;
            if (r.overdue()) anyOverdue = true;
        }
        if (e.isCancelled()) outstanding = 0;
        String state;
        if (e.isCancelled()) state = "CANCELLED";
        else if (!anyUnpaid) state = "SETTLED";
        else if (anyDispute) state = "DISPUTED";
        else if (anyOverdue) state = "OVERDUE";
        else if (anyPaidByValue) state = "PARTIALLY_SETTLED";
        else state = "OPEN";

        List<ExpenseItemResponse> items = new ArrayList<>();
        Map<String, String> nameById = new HashMap<>();
        for (ExpenseShare s : e.getShares()) nameById.put(s.getDebtor().getId(), s.getDebtor().getFullName());
        for (ExpenseItem it : e.getItems()) {
            List<String> aIds = new ArrayList<>(it.getAssigneeIds());
            items.add(new ExpenseItemResponse(it.getId(), it.getName(), BigDecimal.valueOf(it.getAmountMinor(), 2), it.getAmountMinor(),
                    aIds, aIds.stream().map(id -> nameById.getOrDefault(id, "Former member")).toList()));
        }
        boolean creator = e.getCreatedBy().getId().equals(viewer.getId());
        boolean mayCancel = creator || isAdminOf(e, viewer);
        String blocked = e.isCancelled() ? null : cancelBlockedReason(e.getShares());
        boolean canCancel = !e.isCancelled() && mayCancel && blocked == null;
        boolean canEdit = !e.isCancelled() && creator;
        return new ExpenseResponse(e.getId(), e.getCircle().getId(), e.getPaidBy().getId(), e.getPaidBy().getFullName(),
                e.getDescription(), e.getAmount(), e.getCurrency(), e.getSplitType(),
                BigDecimal.valueOf(settled, 2), BigDecimal.valueOf(outstanding, 2), shares, e.getCreatedAt(),
                e.getCircle().getName(), e.getNote(), minor(e.getAmount()), e.getCreatedBy().getId(), e.getCreatedBy().getFullName(),
                e.getExpenseDate(), e.getDueDate(), e.getStatus(), e.getRevision(),
                e.getAmount(), BigDecimal.valueOf(transferred, 2), BigDecimal.valueOf(external, 2), BigDecimal.valueOf(refunded, 2),
                state, anyOverdue, items, canEdit, canCancel, mayCancel ? blocked : null,
                e.getCancelledAt(), e.getCancelReason(), true, SIMULATED_SETTLEMENT_NOTICE);
    }

    private boolean isAdminOf(Expense e, User viewer) {
        return membershipRepository.findByCircleIdAndUserId(e.getCircle().getId(), viewer.getId())
                .map(m -> m.getRole() == CircleRole.OWNER || m.getRole() == CircleRole.ADMIN).orElse(false);
    }

    private ExpenseShareResponse toShareResponse(Expense e, ExpenseShare s, User viewer, boolean former, Map<String, String> refs) {
        boolean active = !e.isCancelled();
        boolean isDebtor = s.getDebtor().getId().equals(viewer.getId());
        boolean isPayer = e.getPaidBy().getId().equals(viewer.getId());
        boolean isCreator = e.getCreatedBy().getId().equals(viewer.getId());
        boolean party = isDebtor || isPayer || isCreator;
        ShareStatus st = s.getStatus();
        boolean supported = e.getCurrency().equals(currencyProperties.getDefaultCurrency());
        boolean overdue = active && e.getDueDate() != null && e.getDueDate().isBefore(LocalDate.now()) && st.isOutstanding();
        boolean payable = active && isDebtor && !isPayer && st.isPayable();
        String blockedReason = null;
        if (payable && !supported) {
            blockedReason = "The demo wallet only supports " + currencyProperties.getDefaultCurrency()
                    + ", so this " + e.getCurrency() + " share cannot be settled here.";
        } else if (payable && !e.getPaidBy().isActive()) {
            blockedReason = "The person who paid can no longer receive demo-wallet transfers.";
        }
        boolean byLedger = st == ShareStatus.PAID && s.getSettlementMethod() == SettlementMethod.LEDGER;
        boolean external = st == ShareStatus.PAID && (s.getSettlementMethod() == SettlementMethod.EXTERNAL
                || s.getSettlementMethod() == SettlementMethod.LEGACY);
        return new ExpenseShareResponse(s.getId(), s.getDebtor().getId(), s.getDebtor().getFullName(), s.getAmount(), s.isSettled(),
                s.getSettledAt(), minor(s.getAmount()),
                s.getPercentageBp() == null ? null : BigDecimal.valueOf(s.getPercentageBp(), 2),
                st, s.getSettlementMethod(), overdue, isDebtor, former,
                party && s.getLedgerTransactionId() != null ? refs.get(s.getLedgerTransactionId()) : null,
                party && s.getRefundTransactionId() != null ? refs.get(s.getRefundTransactionId()) : null,
                party ? s.getResponseNote() : null, party ? s.getRespondedAt() : null,
                active && isDebtor && RESPOND_ACCEPT_FROM.contains(st),
                active && isDebtor && RESPOND_DISPUTE_FROM.contains(st),
                active && isDebtor && RESPOND_DECLINE_FROM.contains(st),
                payable && blockedReason == null, blockedReason,
                active && (isPayer || isCreator) && REOPEN_FROM.contains(st),
                active && isPayer && !isDebtor && RECEIVED_FROM.contains(st),
                active && isPayer && external,
                active && isPayer && !isDebtor && byLedger,
                true);
    }
}
