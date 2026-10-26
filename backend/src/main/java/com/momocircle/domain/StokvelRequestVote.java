package com.momocircle.domain;

import jakarta.persistence.*;
import com.momocircle.domain.enums.*;

@Entity
@Table(name = "stokvel_request_votes")
public class StokvelRequestVote extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "request_id", nullable = false)
    private StokvelRequest request;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "voter_id", nullable = false)
    private User voter;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private VoteDecision decision;
    @Column(length = 300)
    private String comment;

    public StokvelRequest getRequest() { return request; }
    public void setRequest(StokvelRequest request) { this.request = request; }
    public User getVoter() { return voter; }
    public void setVoter(User voter) { this.voter = voter; }
    public VoteDecision getDecision() { return decision; }
    public void setDecision(VoteDecision decision) { this.decision = decision; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
}
