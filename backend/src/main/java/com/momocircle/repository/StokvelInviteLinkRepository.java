package com.momocircle.repository;

import com.momocircle.domain.StokvelInviteLink;
import com.momocircle.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StokvelInviteLinkRepository extends JpaRepository<StokvelInviteLink, String> {

    Optional<StokvelInviteLink> findByCodeHash(String codeHash);

    @Query("select l from StokvelInviteLink l where l.circle.id = :circleId order by l.createdAt desc")
    List<StokvelInviteLink> findForCircle(@Param("circleId") String circleId);

    /**
     * Counts one use, atomically and only while the link is live: not revoked, not expired, uses left. Returns 1 if the use was
     * granted, 0 otherwise, so concurrent redemptions can never exceed max_uses (the CHECK constraint is the backstop).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("update StokvelInviteLink l set l.usesCount = l.usesCount + 1, l.updatedAt = :now "
            + "where l.id = :id and l.revokedAt is null and l.expiresAt > :now and l.usesCount < l.maxUses")
    int consume(@Param("id") String id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("update StokvelInviteLink l set l.revokedAt = :now, l.revokedBy = :by, l.updatedAt = :now "
            + "where l.id = :id and l.circle.id = :circleId and l.revokedAt is null")
    int revoke(@Param("id") String id, @Param("circleId") String circleId, @Param("by") User by, @Param("now") Instant now);
}
