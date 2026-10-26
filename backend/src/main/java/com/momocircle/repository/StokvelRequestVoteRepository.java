package com.momocircle.repository;

import com.momocircle.domain.StokvelRequestVote;
import com.momocircle.domain.enums.VoteDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StokvelRequestVoteRepository extends JpaRepository<StokvelRequestVote, String> {
    List<StokvelRequestVote> findByRequestIdOrderByCreatedAtAsc(String requestId);
    Optional<StokvelRequestVote> findByRequestIdAndVoterId(String requestId, String voterId);
    long countByRequestIdAndDecision(String requestId, VoteDecision decision);
}
