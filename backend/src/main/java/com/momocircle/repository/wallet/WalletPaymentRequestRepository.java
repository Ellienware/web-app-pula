package com.momocircle.repository.wallet;

import com.momocircle.domain.wallet.PaymentRequestStatus;
import com.momocircle.domain.wallet.WalletPaymentRequestEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Every status change is ONE guarded UPDATE whose WHERE clause states the legal source state, so two racing requests can
 * never both win: the database serialises them and the loser sees an update count of 0. No read-modify-write.
 */
public interface WalletPaymentRequestRepository extends JpaRepository<WalletPaymentRequestEntity, String> {

    Optional<WalletPaymentRequestEntity> findByPublicRef(String publicRef);

    @Query("select r from WalletPaymentRequestEntity r where r.requesterUserId = :user and r.requesterBusinessId is null order by r.createdAt desc, r.id desc")
    Page<WalletPaymentRequestEntity> findOutgoing(@Param("user") String user, Pageable pageable);

    @Query("select r from WalletPaymentRequestEntity r where r.targetPayerUserId = :user order by r.createdAt desc, r.id desc")
    Page<WalletPaymentRequestEntity> findIncoming(@Param("user") String user, Pageable pageable);

    /** OPEN -> PROCESSING for exactly one payer, only while not expired. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update WalletPaymentRequestEntity r set r.status = :processing, r.payerUserId = :payer, r.updatedAt = :now "
            + "where r.id = :id and r.status = :open and r.expiresAt > :now")
    int claim(@Param("id") String id, @Param("payer") String payer, @Param("now") Instant now,
              @Param("open") PaymentRequestStatus open, @Param("processing") PaymentRequestStatus processing);

    /** PROCESSING -> OPEN after the ledger definitively refused the payment (nothing was posted). Only the claiming payer. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update WalletPaymentRequestEntity r set r.status = :open, r.payerUserId = null, r.updatedAt = :now "
            + "where r.id = :id and r.status = :processing and r.payerUserId = :payer")
    int release(@Param("id") String id, @Param("payer") String payer, @Param("now") Instant now,
                @Param("open") PaymentRequestStatus open, @Param("processing") PaymentRequestStatus processing);

    /** PROCESSING -> PAID, recording the ledger transaction that moved the value. Idempotent for the same payer/tx. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update WalletPaymentRequestEntity r set r.status = :paid, r.ledgerTransactionId = :tx, r.resolvedAt = :now, r.updatedAt = :now "
            + "where r.id = :id and r.status = :processing and r.payerUserId = :payer")
    int markPaid(@Param("id") String id, @Param("payer") String payer, @Param("tx") String txId, @Param("now") Instant now,
                 @Param("processing") PaymentRequestStatus processing, @Param("paid") PaymentRequestStatus paid);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update WalletPaymentRequestEntity r set r.status = :cancelled, r.resolvedAt = :now, r.updatedAt = :now "
            + "where r.id = :id and r.status = :open and r.requesterUserId = :requester and r.requesterBusinessId is null")
    int cancel(@Param("id") String id, @Param("requester") String requester, @Param("now") Instant now,
               @Param("open") PaymentRequestStatus open, @Param("cancelled") PaymentRequestStatus cancelled);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update WalletPaymentRequestEntity r set r.status = :rejected, r.resolvedAt = :now, r.updatedAt = :now "
            + "where r.id = :id and r.status = :open and r.targetPayerUserId = :payer")
    int reject(@Param("id") String id, @Param("payer") String payer, @Param("now") Instant now,
               @Param("open") PaymentRequestStatus open, @Param("rejected") PaymentRequestStatus rejected);

    /** OPEN past its expiry -> EXPIRED. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update WalletPaymentRequestEntity r set r.status = :expired, r.resolvedAt = :now, r.updatedAt = :now "
            + "where r.id = :id and r.status = :open and r.expiresAt <= :now")
    int expire(@Param("id") String id, @Param("now") Instant now,
               @Param("open") PaymentRequestStatus open, @Param("expired") PaymentRequestStatus expired);

    @Query("select r from WalletPaymentRequestEntity r where r.status in :statuses and r.expiresAt <= :now order by r.expiresAt")
    List<WalletPaymentRequestEntity> findDue(@Param("statuses") List<PaymentRequestStatus> statuses, @Param("now") Instant now, Pageable pageable);

    /** Claims that have sat in PROCESSING long enough that no live request can still be completing them. */
    @Query("select r from WalletPaymentRequestEntity r where r.status = :processing and r.updatedAt <= :before order by r.updatedAt")
    List<WalletPaymentRequestEntity> findStaleProcessing(@Param("processing") PaymentRequestStatus processing, @Param("before") Instant before, Pageable pageable);

    @Query("select r from WalletPaymentRequestEntity r where r.requesterBusinessId = :business order by r.createdAt desc, r.id desc")
    Page<WalletPaymentRequestEntity> findForBusiness(@Param("business") String business, Pageable pageable);

    /** Cancel on behalf of a business (the caller has verified the member's REQUEST permission). */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update WalletPaymentRequestEntity r set r.status = :cancelled, r.resolvedAt = :now, r.updatedAt = :now "
            + "where r.id = :id and r.status = :open and r.requesterBusinessId = :business")
    int cancelForBusiness(@Param("id") String id, @Param("business") String business, @Param("now") Instant now,
                          @Param("open") PaymentRequestStatus open, @Param("cancelled") PaymentRequestStatus cancelled);
}
