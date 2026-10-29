package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A seller-uploaded product / dish / service image, stored in the database (see V52). */
@Entity
@Table(name = "uploaded_images")
public class UploadedImage extends BaseEntity {

    @Column(name = "business_id", nullable = true, length = 36)
    private String businessId;

    @Column(name = "uploaded_by", nullable = false, length = 36, updatable = false)
    private String uploadedBy;

    @Column(name = "content_type", nullable = false, length = 30, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private int sizeBytes;

    @Column(name = "data", nullable = false, updatable = false)
    private byte[] data;

    public String getBusinessId() { return businessId; }
    public void setBusinessId(String businessId) { this.businessId = businessId; }
    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public int getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(int sizeBytes) { this.sizeBytes = sizeBytes; }
    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }
}
