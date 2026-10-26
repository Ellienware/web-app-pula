package com.momocircle.repository;

import com.momocircle.domain.CircleInvitation;
import com.momocircle.domain.enums.InvitationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CircleInvitationRepository extends JpaRepository<CircleInvitation, String> {

    List<CircleInvitation> findByCircleIdOrderByCreatedAtDesc(String circleId);

    Optional<CircleInvitation> findByToken(String token);

    List<CircleInvitation> findByInvitedEmailAndStatusOrderByCreatedAtDesc(String invitedEmail,
                                                                           InvitationStatus status);

    boolean existsByCircleIdAndInvitedEmailAndStatus(String circleId,
                                                     String invitedEmail,
                                                     InvitationStatus status);
}
