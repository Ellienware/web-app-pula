package com.momocircle.repository;

import com.momocircle.domain.UploadedImage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadedImageRepository extends JpaRepository<UploadedImage, String> {
    long countByBusinessId(String businessId);

    java.util.Optional<UploadedImage> findByIdAndUploadedByAndBusinessIdIsNull(
            String id, String uploadedBy);

    long countByUploadedByAndBusinessIdIsNull(String uploadedBy);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "delete from UploadedImage i where i.businessId is null and i.createdAt < :cutoff")
    int deletePendingBefore(
            @org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff);
}
