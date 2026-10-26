package com.momocircle.domain;

import com.momocircle.domain.enums.BusinessDocumentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A supporting document attached to a {@link Business}'s profile (ID document,
 * proof of address, CIPC certificate, etc. — brief §7/§8).
 *
 * This is metadata only: {@code fileUrl} points to wherever the owner has
 * already hosted the file (this codebase has no in-app file/object storage
 * to upload into — do not build one for this without checking with the
 * product owner first, per brief §53 "do not overbuild"). The web/mobile
 * clients collect a URL from the user rather than doing a real file upload.
 *
 * Sensitive: documents here can include ID numbers/images. Only the owning
 * business's owner may list, add, or remove these — see
 * {@link com.momocircle.service.BusinessDocumentService}. Never exposed on
 * the public business browse/detail endpoints.
 */
@Entity
@Table(name = "business_documents")
public class BusinessDocument extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 32)
    private BusinessDocumentType documentType;

    @Column(name = "file_url", nullable = false, length = 2000)
    private String fileUrl;

    @Column(length = 500)
    private String note;

    public Business getBusiness() { return business; }
    public void setBusiness(Business business) { this.business = business; }
    public BusinessDocumentType getDocumentType() { return documentType; }
    public void setDocumentType(BusinessDocumentType documentType) { this.documentType = documentType; }
    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
