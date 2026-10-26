package com.momocircle.repository.wallet;

import com.momocircle.domain.wallet.BusinessWalletMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BusinessWalletMemberRepository extends JpaRepository<BusinessWalletMemberEntity, String> {
    Optional<BusinessWalletMemberEntity> findByBusinessIdAndUserId(String businessId, String userId);
    List<BusinessWalletMemberEntity> findByBusinessIdOrderByCreatedAtAsc(String businessId);
    List<BusinessWalletMemberEntity> findByUserId(String userId);
}
