package com.momocircle.repository.wallet;

import com.momocircle.domain.wallet.BusinessWalletProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BusinessWalletProfileRepository extends JpaRepository<BusinessWalletProfileEntity, String> {
    Optional<BusinessWalletProfileEntity> findByPublicId(String publicId);
}
