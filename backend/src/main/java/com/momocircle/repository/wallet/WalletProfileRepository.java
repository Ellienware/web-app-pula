package com.momocircle.repository.wallet;

import com.momocircle.domain.wallet.WalletProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WalletProfileRepository extends JpaRepository<WalletProfileEntity, String> {
    Optional<WalletProfileEntity> findByPublicId(String publicId);
}
