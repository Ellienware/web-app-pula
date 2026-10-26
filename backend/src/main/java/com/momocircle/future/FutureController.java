package com.momocircle.future;

import com.momocircle.future.FutureDtos.*;
import com.momocircle.security.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** PULA Future. Every endpoint is authenticated (SecurityConfig default) and scoped to the caller. No endpoint moves money. */
@RestController
@RequestMapping("/api/future")
public class FutureController {

    private final FutureService service;
    private final FutureReminderService reminders;
    private final FutureCoachService coach;
    private final CurrentUserService currentUser;

    public FutureController(FutureService service, FutureReminderService reminders, FutureCoachService coach,
                            CurrentUserService currentUser) {
        this.service = service; this.reminders = reminders; this.coach = coach; this.currentUser = currentUser;
    }

    @GetMapping("/dashboard")
    public Dashboard dashboard() { return service.dashboard(currentUser.require()); }

    @PostMapping("/plans")
    public ResponseEntity<PlanResponse> createPlan(@Valid @RequestBody CreatePlanRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createPlan(currentUser.require(), req));
    }

    @GetMapping("/plans")
    public Page<PlanResponse> listPlans(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.listPlans(currentUser.require(), page, size);
    }

    @GetMapping("/plans/{planId}")
    public PlanResponse getPlan(@PathVariable String planId) { return service.getPlan(currentUser.require(), planId); }

    @PostMapping("/plans/{planId}/status")
    public PlanResponse setStatus(@PathVariable String planId, @Valid @RequestBody SetStatusRequest req) {
        return service.setStatus(currentUser.require(), planId, req.status());
    }

    @PostMapping("/plans/{planId}/calculations")
    public CalculationResponse calculate(@PathVariable String planId, @Valid @RequestBody CalculateRequest req) {
        return service.calculate(currentUser.require(), planId, req);
    }

    @GetMapping("/plans/{planId}/calculations")
    public Page<CalculationResponse> history(@PathVariable String planId, @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return service.history(currentUser.require(), planId, page, size);
    }

    @PostMapping("/simulate")
    public SimulationResponse simulate(@Valid @RequestBody SimulateRequest req) {
        currentUser.require();
        return service.simulate(req);
    }

    @PostMapping("/scenarios")
    public ResponseEntity<ScenarioResponse> saveScenario(@Valid @RequestBody SaveScenarioRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveScenario(currentUser.require(), req));
    }

    @GetMapping("/scenarios")
    public Page<ScenarioResponse> scenarios(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.listScenarios(currentUser.require(), page, size);
    }

    @PatchMapping("/goals/{goalId}/meta")
    public DashboardGoal goalMeta(@PathVariable String goalId, @Valid @RequestBody GoalMetaRequest req) {
        return service.updateGoalMeta(currentUser.require(), goalId, req);
    }

    @GetMapping("/goals/{goalId}/schedule")
    public ScheduleResponse schedule(@PathVariable String goalId,
                                     @RequestParam(defaultValue = "MONTHLY") ScheduleCalculator.Frequency frequency) {
        return service.schedule(currentUser.require(), goalId, frequency);
    }

    @GetMapping("/education")
    public EducationIndex education() { currentUser.require(); return service.education(); }

    @GetMapping("/education/{lessonId}")
    public LessonDetail lesson(@PathVariable String lessonId) { currentUser.require(); return service.lesson(lessonId); }

    // ---- business planning ----
    @GetMapping("/businesses")
    public java.util.List<BusinessSummary> businesses() { return service.myBusinesses(currentUser.require()); }

    @GetMapping("/businesses/{businessId}/settlement-income")
    public IncomePreview settlementIncome(@PathVariable String businessId,
                                          @RequestParam java.time.LocalDate from, @RequestParam java.time.LocalDate to) {
        return service.settlementPreview(currentUser.require(), businessId, from, to);
    }

    @PostMapping("/plans/{planId}/items")
    public ResponseEntity<PlanResponse> addItem(@PathVariable String planId, @Valid @RequestBody PlanItemRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addItem(currentUser.require(), planId, req));
    }

    @DeleteMapping("/plans/{planId}/items/{itemId}")
    public PlanResponse removeItem(@PathVariable String planId, @PathVariable String itemId) {
        return service.removeItem(currentUser.require(), planId, itemId);
    }

    // ---- goals ----
    @PostMapping("/goals")
    public ResponseEntity<DashboardGoal> createGoal(@Valid @RequestBody CreateGoalInput req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createGoal(currentUser.require(), req));
    }

    @PostMapping("/goals/{goalId}/pause")
    public DashboardGoal pauseGoal(@PathVariable String goalId) { return service.pauseGoal(currentUser.require(), goalId); }

    @PostMapping("/goals/{goalId}/resume")
    public DashboardGoal resumeGoal(@PathVariable String goalId) { return service.resumeGoal(currentUser.require(), goalId); }

    // ---- stokvels, reminders, coach ----
    @GetMapping("/stokvels")
    public java.util.List<StokvelView> stokvels() { return service.stokvels(currentUser.require()); }

    @GetMapping("/reminders")
    public ReminderSettings reminderSettings() { return reminders.settings(currentUser.require()); }

    @PutMapping("/reminders")
    public ReminderSettings updateReminder(@Valid @RequestBody ReminderUpdate req) {
        return reminders.update(currentUser.require(), req.kind(), req.enabled());
    }

    @PostMapping("/coach")
    public CoachResponse coach(@Valid @RequestBody CoachRequest req) { return coach.ask(currentUser.require(), req); }
}
