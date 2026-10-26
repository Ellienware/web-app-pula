package com.momocircle.repository;

import com.momocircle.domain.StokvelInviteLinkUse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StokvelInviteLinkUseRepository extends JpaRepository<StokvelInviteLinkUse, String> {
    Optional<StokvelInviteLinkUse> findByLinkIdAndUserId(String linkId, String userId);
}
