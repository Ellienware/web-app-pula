package com.momocircle.domain;

import com.momocircle.domain.enums.ProposalStatus;
import com.momocircle.dto.AssistantDtos.ActionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * The server-side record a {@code ProposedAction} is bound to (Phase D.1).
 *
 * Before this, {@code POST /assistant/execute} trusted the client to send
 * back the same intent/parameters/circleId it was shown — nothing stopped a
 * tampered request from swapping in a different amount or circle between
 * proposal and confirmation. Now {@code interpret()} persists exactly what
 * it proposed here, the client gets back only this row's id, and
 * {@code execute()} loads the stored, already-validated action instead of
 * trusting anything the client resends except which proposal to run and
 * which circle it currently believes it's looking at (used only to detect
 * a changed circle context, never to pick the circle to act on).
 *
 * {@code parametersJson} is a plain JSON blob rather than a typed column set
 * because the shape varies per {@link ActionType} — same tradeoff the
 * in-memory {@code Map<String,Object>} it replaces already had; the
 * difference is this version can't be edited by the client between requests.
 */
@Entity
@Table(name = "assistant_proposals")
public class AssistantProposal extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Null for actions with no circle context yet (e.g. CREATE_CIRCLE). */
    @Column(name = "circle_id")
    private String circleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActionType intent;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String parametersJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProposalStatus status = ProposalStatus.PENDING;

    @Column(nullable = false)
    private Instant expiresAt;

    /**
     * Optimistic-locking column protecting the PENDING -> EXECUTING transition.
     * Two simultaneous confirm requests for the same proposal both read the
     * same version; only the first to flush its EXECUTING write succeeds — the
     * second gets an OptimisticLockException (see
     * AssistantProposalConcurrencyService.claim), never a second execution.
     */
    @Version
    private Long version;

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getCircleId() { return circleId; }
    public void setCircleId(String circleId) { this.circleId = circleId; }
    public ActionType getIntent() { return intent; }
    public void setIntent(ActionType intent) { this.intent = intent; }
    public String getParametersJson() { return parametersJson; }
    public void setParametersJson(String parametersJson) { this.parametersJson = parametersJson; }
    public ProposalStatus getStatus() { return status; }
    public void setStatus(ProposalStatus status) { this.status = status; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
