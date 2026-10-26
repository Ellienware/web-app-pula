package com.momocircle.repository;

import com.momocircle.domain.StokvelProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StokvelProfileRepository extends JpaRepository<StokvelProfile, String> {

    Optional<StokvelProfile> findByCircleId(String circleId);

    /** Serialises rotation changes and payout recording for one stokvel. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from StokvelProfile p where p.circle.id = :circleId")
    Optional<StokvelProfile> findByCircleIdForUpdate(@Param("circleId") String circleId);

    @Query("select p from StokvelProfile p join CircleMembership m on m.circle = p.circle where m.user.id = :userId")
    List<StokvelProfile> findForUser(@Param("userId") String userId);
}
