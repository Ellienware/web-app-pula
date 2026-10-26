package com.momocircle.domain;

import com.momocircle.branch.BranchMovementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** Append-only audit row for every change to branch stock. Never updated or deleted by the application. */
@Entity
@Table(name = "branch_stock_movements")
public class BranchStockMovement extends BaseEntity {

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "product_id", nullable = false, length = 36)
    private String productId;

    @Column(name = "from_branch_id", length = 36)
    private String fromBranchId;

    @Column(name = "to_branch_id", length = 36)
    private String toBranchId;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 24)
    private BranchMovementType movementType;

    @Column(name = "order_id", length = 36)
    private String orderId;

    @Column(name = "actor_user_id", length = 36)
    private String actorUserId;

    @Column(length = 255)
    private String note;

    public String getBusinessId() { return businessId; }
    public void setBusinessId(String businessId) { this.businessId = businessId; }
    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public String getFromBranchId() { return fromBranchId; }
    public void setFromBranchId(String fromBranchId) { this.fromBranchId = fromBranchId; }
    public String getToBranchId() { return toBranchId; }
    public void setToBranchId(String toBranchId) { this.toBranchId = toBranchId; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public BranchMovementType getMovementType() { return movementType; }
    public void setMovementType(BranchMovementType movementType) { this.movementType = movementType; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getActorUserId() { return actorUserId; }
    public void setActorUserId(String actorUserId) { this.actorUserId = actorUserId; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
