package com.momocircle.repository;

import com.momocircle.domain.UploadedImage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadedImageRepository extends JpaRepository<UploadedImage, String> {
    long countByBusinessId(String businessId);
}
